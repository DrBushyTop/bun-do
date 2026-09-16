package fi.bundo.data

import android.annotation.SuppressLint
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

@Entity(tableName = "voice_recordings")
data class VoiceRecording(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val expiresAt: Long,
    val state: String,
    val reason: String = "",
    val checklistScope: String? = null,
    val checklistTaskId: String? = null,
    val workspaceScope: String? = null,
    val committedTaskId: String? = null,
    @ColumnInfo(defaultValue = "1") val keepAudio: Boolean = true,
    @ColumnInfo(defaultValue = "0") val reviewRequired: Boolean = false,
    val review: String? = null,
    val captureContext: String? = null,
)

data class VoiceTarget(val scope: String, val taskId: String? = null)
data class SavedRecording(val taskId: String, val target: VoiceTarget?)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM voice_recordings ORDER BY createdAt")
    fun observe(): Flow<List<VoiceRecording>>
    @Query("SELECT * FROM voice_recordings ORDER BY createdAt")
    suspend fun all(): List<VoiceRecording>
    @Query("SELECT * FROM voice_recordings WHERE id = :id")
    suspend fun get(id: String): VoiceRecording?
    @Insert
    suspend fun insert(recording: VoiceRecording)
    @Query("UPDATE voice_recordings SET state = :state, reason = :reason WHERE id = :id")
    suspend fun update(id: String, state: String, reason: String = "")
    @Query("UPDATE voice_recordings SET state = 'FAILED', reason = :reason WHERE id = :id AND state != 'COMMITTED'")
    suspend fun failUncommitted(id: String, reason: String)
    @Query("UPDATE voice_recordings SET state = 'COMMITTED', committedTaskId = :taskId WHERE id = :id")
    suspend fun markCommitted(id: String, taskId: String)
    @Query("UPDATE voice_recordings SET state = 'REVIEW', review = :review, reason = '' WHERE id = :id AND state != 'COMMITTED'")
    suspend fun saveReview(id: String, review: String)
    @Query("DELETE FROM voice_recordings WHERE id = :id")
    suspend fun delete(id: String)
}

/** The lease guards delayed speech results as well as foreground edits. */
class RecordingStore(
    private val database: InboxDatabase,
    private val directory: File,
    private val lease: DataLease = DataLease(),
    private val encryptionKey: ByteArray? = null,
) {
    private val dao = database.recordings()
    val recordings = dao.observe()
    init { directory.mkdirs() }

    fun audio(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.pcm")
    }

    fun output(id: String): java.io.OutputStream = AccountAudio.output(audio(id), encryptionKey, lease)
    fun readAudio(id: String): ByteArray = AccountAudio.read(audio(id), encryptionKey, lease)
    fun checkActive() = lease.check()

    @SuppressLint("UsableSpace") // Conservative preflight; do not count or evict reclaimable caches.
    suspend fun begin(now: Long = System.currentTimeMillis(), target: VoiceTarget? = null, keepAudio: Boolean = true, reviewRequired: Boolean = false): VoiceRecording = lease.access {
        pruneUnsafe(now)
        val existing = dao.all()
        if (existing.size >= MAX_RECORDINGS ||
            directory.listFiles().orEmpty().sumOf { it.length() } + MAX_AUDIO_BYTES > MAX_RETAINED_BYTES ||
            directory.usableSpace < MAX_AUDIO_BYTES + 8 * 1024 * 1024) throw RecordingStorageFull()
        val record = VoiceRecording(UUID.randomUUID().toString(), now, now + RETAIN_MILLIS, "RECORDING", checklistScope = target?.takeIf { it.taskId != null }?.scope, checklistTaskId = target?.taskId,
            workspaceScope = target?.takeIf { it.taskId == null }?.scope, keepAudio = keepAudio, reviewRequired = reviewRequired, captureContext = SharedProtocol.context(java.time.Instant.ofEpochMilli(now)))
        dao.insert(record) // Persist intent before opening the microphone or creating audio.
        record
    }

    suspend fun recover(now: Long = System.currentTimeMillis()) = lease.access {
        pruneUnsafe(now)
        dao.all().forEach {
            if (it.review != null && !it.keepAudio) deleteAudioUnsafe(it.id)
            if (it.state == "COMMITTED" && (!it.keepAudio || !it.reviewRequired)) deleteUnsafe(it.id)
            else if (!it.keepAudio && it.review == null) deleteUnsafe(it.id)
            else if (it.state in setOf("RECORDING", "TRANSCRIBING"))
                dao.update(it.id, "FAILED", "INTERRUPTED")
        }
        val ids = dao.all().map { it.id }.toSet()
        directory.listFiles()?.filter { it.extension == "pcm" && it.nameWithoutExtension !in ids }
            ?.forEach { it.delete() }
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) = lease.access { pruneUnsafe(now) }
    private suspend fun pruneUnsafe(now: Long) {
        directory.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("\\.import-[a-f0-9-]{36}")) }
            ?.forEach { check(it.deleteRecursively()) { "Interrupted import cleanup failed" } }
        dao.all().filter { it.expiresAt <= now }.forEach {
            if (it.state == "REVIEW") deleteAudioUnsafe(it.id) else deleteUnsafe(it.id)
        }
    }

    /** Explicit migration copy, with a stable local ID so a lost response cannot duplicate a transcript. */
    @SuppressLint("UsableSpace")
    suspend fun importLegacy(id: String, createdAt: Long, expiresAt: Long, pcm: ByteArray) = lease.access {
        val target = audio(id)
        val now = System.currentTimeMillis()
        require(pcm.size.toLong() in 2..MAX_AUDIO_BYTES && pcm.size % 2 == 0)
        require(expiresAt > now && expiresAt <= createdAt + RETAIN_MILLIS)
        if (database.inbox().task("voice-$id") != null) return@access
        val previous = dao.get(id)
        if (previous != null) {
            check(previous.createdAt == createdAt && previous.expiresAt == expiresAt)
            check(readAudio(id).contentEquals(pcm))
            return@access
        }
        pruneUnsafe(now)
        if (dao.all().size >= MAX_RECORDINGS ||
            directory.listFiles().orEmpty().sumOf { it.length() } + pcm.size * 2L > MAX_RETAINED_BYTES ||
            directory.usableSpace < pcm.size * 2L + 8 * 1024 * 1024) throw RecordingStorageFull()
        val staging = File(directory, ".import-$id").apply { mkdirs() }
        val temporary = File(staging, "$id.pcm")
        try {
            AccountAudio.output(temporary, encryptionKey, lease).use { output ->
                var offset = 0
                while (offset < pcm.size) {
                    val count = minOf(8192, pcm.size - offset)
                    output.write(pcm, offset, count)
                    offset += count
                }
            }
            lease.check()
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            database.withTransaction {
                lease.check()
                dao.insert(VoiceRecording(id, createdAt, expiresAt, "FAILED", "INTERRUPTED"))
                lease.check()
            }
        } finally { staging.deleteRecursively() }
    }

    suspend fun available(id: String): Boolean = lease.access {
        val record = dao.get(id) ?: return@access false
        if (record.review != null) return@access false
        if (record.expiresAt <= System.currentTimeMillis()) {
            deleteUnsafe(id)
            return@access false
        }
        record.state != "COMMITTED" && record.review == null
    }

    suspend fun failed(id: String, reason: String) = lease.access {
        val row = dao.get(id) ?: return@access
        if (!row.keepAudio && row.review == null) deleteUnsafe(id)
        else if (row.review == null) dao.failUncommitted(id, reason)
    }
    suspend fun get(id: String) = lease.access { dao.get(id) }
    suspend fun review(id: String, draft: VoiceDraft) = lease.access {
        lease.check()
        val row = checkNotNull(dao.get(id))
        check(row.state != "COMMITTED")
        dao.saveReview(id, draft.json())
        if (!row.keepAudio) deleteAudioUnsafe(id)
    }
    suspend fun exportable(id: String) = lease.access {
        val row = dao.get(id)
        row != null && row.keepAudio && (row.state != "COMMITTED" || row.reviewRequired) && row.expiresAt > System.currentTimeMillis() && audio(id).exists()
    }
    suspend fun transcribing(id: String) = lease.access { dao.update(id, "TRANSCRIBING") }

    suspend fun commit(id: String, transcript: String, draft: VoiceDraft? = null): SavedRecording = lease.access {
        val text = transcript.trim()
        require(text.isNotEmpty() && InboxLimits.length(text) <= InboxLimits.DESCRIPTION)
        require(draft == null || draft.valid)
        var retained = false
        var taskId = "voice-$id"
        var target: VoiceTarget? = null
        database.withTransaction {
            lease.check()
            val record = checkNotNull(dao.get(id))
            check(record.review != null || record.expiresAt > System.currentTimeMillis())
            retained = record.keepAudio && record.reviewRequired
            target = record.workspaceScope?.let { VoiceTarget(it) }
                ?: record.checklistScope?.let { VoiceTarget(it, record.checklistTaskId) }
            if (record.state == "COMMITTED" && record.committedTaskId != null) taskId = record.committedTaskId
            else if (record.workspaceScope != null) {
                val workspace = checkNotNull(database.shared().workspace(record.workspaceScope))
                check(workspace.blocked == null)
                taskId = SharedRepository(database, lease, workspace.scope, workspace.registration).captureRecordingInTransaction(text, record.createdAt, draft, record.captureContext)
            } else if (record.checklistScope != null && record.checklistTaskId != null) {
                val shared = database.shared()
                val workspace = checkNotNull(shared.workspace(record.checklistScope))
                check(workspace.blocked == null)
                val draft = checkNotNull(shared.draft(record.checklistScope, "checklist:${record.checklistTaskId}"))
                taskId = "checklist:${record.checklistScope}:${record.checklistTaskId}"
                if (record.state != "COMMITTED") {
                    val details = draft.details?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
                    val previous = details.optString("instructions")
                    val combined = if (previous.isBlank()) text else "$previous\n$text"
                    if (InboxLimits.length(combined) > 2000) throw TranscriptTooLong()
                    details.put("instructions", combined)
                    shared.saveDraft(draft.copy(details = details.toString(), savedAt = System.currentTimeMillis()))
                }
            } else if (record.state != "COMMITTED" && database.inbox().task(taskId) == null) {
                val original = VoiceDraft.from(text)
                val title = draft?.title ?: original.title
                val description = draft?.description ?: original.description
                require(draft?.items.isNullOrEmpty())
                database.inbox().insertTask(InboxTask(
                    taskId, title, description, original.title, original.description, record.createdAt, record.createdAt,
                ))
                database.inbox().insertIntent(InboxIntent(
                    taskId = taskId, kind = "CaptureInboxTask", title = title,
                    description = description, createdAt = record.createdAt,
                ))
            }
            dao.markCommitted(id, taskId)
        }
        // A crash here is safe. Recovery cleans COMMITTED audio without transcribing twice.
        if (!retained) deleteUnsafe(id)
        SavedRecording(taskId, target)
    }

    suspend fun delete(id: String) = lease.access { deleteUnsafe(id) }
    private fun deleteAudioUnsafe(id: String) {
        val file = audio(id)
        check(!file.exists() || file.delete()) { "Audio cleanup failed" }
    }
    private suspend fun deleteUnsafe(id: String) {
        deleteAudioUnsafe(id)
        dao.delete(id)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
        const val MAX_AUDIO_BYTES = SAMPLE_RATE * MAX_SECONDS * 2L
        const val MAX_RECORDINGS = 20
        const val MAX_RETAINED_BYTES = 256L * 1024 * 1024
        const val RETAIN_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

class RecordingStorageFull : java.io.IOException()

class TranscriptTooLong : IllegalArgumentException()
