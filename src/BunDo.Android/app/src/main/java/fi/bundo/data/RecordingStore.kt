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

/** The caller serializes audio work. This store belongs to the anonymous inbox only. */
class RecordingStore(private val database: InboxDatabase, private val directory: File) {
    private val dao = database.recordings()
    val recordings = dao.observe()
    init { directory.mkdirs() }

    fun audio(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.pcm")
    }

    @SuppressLint("UsableSpace") // Conservative preflight; do not count or evict reclaimable caches.
    suspend fun begin(now: Long = System.currentTimeMillis()): VoiceRecording {
        prune(now)
        val existing = dao.all()
        if (existing.size >= MAX_RECORDINGS ||
            directory.listFiles().orEmpty().sumOf { it.length() } + MAX_AUDIO_BYTES > MAX_RETAINED_BYTES ||
            directory.usableSpace < MAX_AUDIO_BYTES + 8 * 1024 * 1024) throw RecordingStorageFull()
        val record = VoiceRecording(UUID.randomUUID().toString(), now, now + RETAIN_MILLIS, "RECORDING")
        dao.insert(record) // Persist intent before opening the microphone or creating audio.
        return record
    }

    suspend fun recover(now: Long = System.currentTimeMillis()) {
        prune(now)
        dao.all().forEach {
            if (it.state == "COMMITTED") delete(it.id)
            else if (it.state in setOf("RECORDING", "TRANSCRIBING"))
                dao.update(it.id, "FAILED", "INTERRUPTED")
        }
        val ids = dao.all().map { it.id }.toSet()
        directory.listFiles()?.filter { it.extension == "pcm" && it.nameWithoutExtension !in ids }
            ?.forEach { it.delete() }
    }

    suspend fun prune(now: Long = System.currentTimeMillis()) {
        dao.all().filter { it.expiresAt <= now }.forEach { delete(it.id) }
    }

    suspend fun available(id: String): Boolean {
        val record = dao.get(id) ?: return false
        if (record.expiresAt <= System.currentTimeMillis()) {
            delete(id)
            return false
        }
        return record.state != "COMMITTED"
    }

    suspend fun failed(id: String, reason: String) = dao.failUncommitted(id, reason)
    suspend fun transcribing(id: String) = dao.update(id, "TRANSCRIBING")

    suspend fun commit(id: String, transcript: String): String {
        val text = transcript.trim()
        require(text.isNotEmpty() && InboxLimits.length(text) <= InboxLimits.DESCRIPTION)
        val taskId = "voice-$id"
        database.withTransaction {
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
        delete(id)
        return taskId
    }

    suspend fun delete(id: String) {
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
