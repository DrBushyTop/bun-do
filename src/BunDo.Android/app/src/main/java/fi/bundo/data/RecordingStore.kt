package fi.bundo.data

import android.annotation.SuppressLint
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
)

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
    suspend fun begin(now: Long = System.currentTimeMillis()): VoiceRecording = lease.access {
        pruneUnsafe(now)
        val existing = dao.all()
        if (existing.size >= MAX_RECORDINGS ||
            directory.listFiles().orEmpty().sumOf { it.length() } + MAX_AUDIO_BYTES > MAX_RETAINED_BYTES ||
            directory.usableSpace < MAX_AUDIO_BYTES + 8 * 1024 * 1024) throw RecordingStorageFull()
        val record = VoiceRecording(UUID.randomUUID().toString(), now, now + RETAIN_MILLIS, "RECORDING")
        dao.insert(record) // Persist intent before opening the microphone or creating audio.
        record
    }

    suspend fun recover(now: Long = System.currentTimeMillis()) = lease.access {
        pruneUnsafe(now)
        dao.all().forEach {
            if (it.state == "COMMITTED") deleteUnsafe(it.id)
            else if (it.state in setOf("RECORDING", "TRANSCRIBING"))
                dao.update(it.id, "FAILED", "INTERRUPTED")
        }
        val ids = dao.all().map { it.id }.toSet()
        directory.listFiles()?.filter { it.extension == "pcm" && it.nameWithoutExtension !in ids }
            ?.forEach { it.delete() }
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) = lease.access { pruneUnsafe(now) }
    private suspend fun pruneUnsafe(now: Long) {
        dao.all().filter { it.expiresAt <= now }.forEach { deleteUnsafe(it.id) }
    }

    suspend fun available(id: String): Boolean = lease.access {
        val record = dao.get(id) ?: return@access false
        if (record.expiresAt <= System.currentTimeMillis()) {
            deleteUnsafe(id)
            return@access false
        }
        record.state != "COMMITTED"
    }

    suspend fun failed(id: String, reason: String) = lease.access { dao.failUncommitted(id, reason) }
    suspend fun transcribing(id: String) = lease.access { dao.update(id, "TRANSCRIBING") }

    suspend fun commit(id: String, transcript: String): String = lease.access {
        val text = transcript.trim()
        require(text.isNotEmpty() && InboxLimits.length(text) <= InboxLimits.DESCRIPTION)
        val taskId = "voice-$id"
        database.withTransaction {
            lease.check()
            val record = checkNotNull(dao.get(id))
            check(record.expiresAt > System.currentTimeMillis())
            if (record.state != "COMMITTED" && database.inbox().task(taskId) == null) {
                val title = text.substring(0, text.offsetByCodePoints(0, minOf(InboxLimits.length(text), InboxLimits.TITLE)))
                val description = if (title == text) "" else text
                database.inbox().insertTask(InboxTask(
                    taskId, title, description, title, description, record.createdAt, record.createdAt,
                ))
                database.inbox().insertIntent(InboxIntent(
                    taskId = taskId, kind = "CaptureInboxTask", title = title,
                    description = description, createdAt = record.createdAt,
                ))
            }
            dao.update(id, "COMMITTED")
        }
        // A crash here is safe. Recovery cleans COMMITTED audio without transcribing twice.
        deleteUnsafe(id)
        taskId
    }

    suspend fun delete(id: String) = lease.access { deleteUnsafe(id) }
    private suspend fun deleteUnsafe(id: String) {
        val file = audio(id)
        check(!file.exists() || file.delete()) { "Audio cleanup failed" }
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
