package fi.bundo.data

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import fi.bundo.speech.exportWave
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import java.util.UUID

data class LegacyRecording(val source: String, val recording: VoiceRecording, val readable: Boolean)
data class LegacyRecordings(val recordings: List<LegacyRecording>, val expiredCount: Int)

/** Only the known flat pre-identity anonymous layout is eligible. Never search encrypted account folders. */
class LegacyRecordingRecovery(private val context: Context, private val namespace: String) {
    private val mutex = Mutex()
    private val notices = context.getSharedPreferences("$namespace-legacy-audio", Context.MODE_PRIVATE)
    private data class Source(val key: String, val database: File, val audio: File, val record: VoiceRecording)

    private fun sources(): List<Source> {
        val result = mutableListOf<Source>()
        // Android exposes the app directory through /data/user/0 as well as /data/data.
        // Normalize that trusted parent before rejecting links inside retained material.
        for (root in context.noBackupFilesDir.canonicalFile.listFiles().orEmpty().filter {
            it.isDirectory && it.name.startsWith("$namespace-unexpected-") && it.canonicalFile == it.absoluteFile
        }) {
            val database = File(root, InboxDatabase.FILE_NAME)
            if (!database.isFile || database.canonicalFile != database.absoluteFile) continue
            // Do not try opening arbitrary encrypted or damaged account material as an anonymous database.
            val header = ByteArray(16)
            if (database.inputStream().use { it.read(header) } != header.size) continue
            if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) continue
            try {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT id, createdAt, expiresAt, state, reason FROM voice_recordings", null).use { rows ->
                        while (rows.moveToNext()) {
                            val id = rows.getString(0)
                            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) continue
                            val created = rows.getLong(1)
                            if (created <= 0 || created > Long.MAX_VALUE - RecordingStore.RETAIN_MILLIS) continue
                            val state = rows.getString(3)
                            if (state !in listOf("RECORDING", "TRANSCRIBING", "FAILED", "COMMITTED")) continue
                            val file = File(root, "anonymous-audio/$id.pcm")
                            if (file.canonicalFile != file.absoluteFile) continue
                            val expires = minOf(rows.getLong(2), created + RecordingStore.RETAIN_MILLIS)
                            result += Source("${root.name}/$id", database, file,
                                VoiceRecording(id, created, expires, state, rows.getString(4).orEmpty()))
                        }
                    }
                }
            } catch (_: android.database.sqlite.SQLiteException) {
                // Unknown schema/encryption is not permission to import or delete anything.
            }
        }
        return result
    }

    private fun erase(source: Source) {
        check(!source.audio.exists() || source.audio.delete()) { "Recording cleanup failed" }
        SQLiteDatabase.openDatabase(source.database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.delete("voice_recordings", "id = ?", arrayOf(source.record.id))
        }
    }

    @SuppressLint("UseKtx") // A failed durable notice write must stop cleanup.
    suspend fun preview(data: AccountData, now: Long = System.currentTimeMillis()): LegacyRecordings = mutex.withLock {
        data.lease.access {
            val remaining = mutableListOf<LegacyRecording>()
            for (source in sources()) {
                data.lease.check()
                if (source.record.expiresAt <= now || source.record.state == "COMMITTED") {
                    if (source.record.expiresAt <= now && source.record.state != "COMMITTED") {
                        // Record first, keyed by source, so interruption cannot lose or duplicate the notice.
                        val expired = notices.getStringSet("expired_sources", emptySet()).orEmpty() + source.key
                        check(notices.edit().putStringSet("expired_sources", expired).commit())
                    }
                    erase(source)
                } else remaining += LegacyRecording(source.key, source.record,
                    source.audio.isFile && source.audio.length() in 2..RecordingStore.MAX_AUDIO_BYTES && source.audio.length() % 2 == 0L)
            }
            data.lease.check()
            LegacyRecordings(remaining.sortedBy { it.recording.createdAt },
                notices.getStringSet("expired_sources", emptySet()).orEmpty().size)
        }
    }

    @SuppressLint("UseKtx") // Report a failed durable acknowledgement rather than silently losing it.
    suspend fun acknowledgeExpiry(data: AccountData) = mutex.withLock {
        data.lease.access { check(notices.edit().remove("expired_sources").commit()) }
    }

    private fun source(key: String): Source = checkNotNull(sources().singleOrNull { it.key == key })
    private fun read(source: Source): ByteArray {
        check(source.record.expiresAt > System.currentTimeMillis() && source.record.state != "COMMITTED")
        require(source.audio.isFile && source.audio.length() in 2..RecordingStore.MAX_AUDIO_BYTES && source.audio.length() % 2 == 0L)
        return source.audio.readBytes().also { require(it.size.toLong() <= RecordingStore.MAX_AUDIO_BYTES) }
    }

    suspend fun export(data: AccountData, key: String, output: OutputStream) = mutex.withLock {
        data.lease.access {
            val pcm = read(source(key))
            exportWave(pcm, output) { data.lease.check() }
            data.lease.check()
        }
    }

    suspend fun delete(data: AccountData, key: String) = mutex.withLock {
        data.lease.access { data.lease.check(); erase(source(key)) }
    }

    /** A chosen copy becomes ordinary account-local audio. Only then can the legacy source be removed. */
    suspend fun recover(data: AccountData, key: String): String = mutex.withLock {
        val (source, pcm) = data.lease.access { source(key).let { it to read(it) } }
        val id = UUID.nameUUIDFromBytes("legacy-recording/$key".toByteArray(Charsets.UTF_8)).toString()
        data.recordings.importLegacy(id, source.record.createdAt, source.record.expiresAt, pcm)
        data.lease.access { data.lease.check(); erase(source) }
        id
    }
}
