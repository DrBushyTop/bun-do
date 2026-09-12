package fi.bundo.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

/** Best-effort background expiry. Opening/retrying/exporting also enforces the deadline. */
class AudioExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val database = InboxDatabase.open(applicationContext)
        return try {
            RecordingStore(database, File(applicationContext.noBackupFilesDir, "anonymous-audio")).prune()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally { database.close() }
    }
}
