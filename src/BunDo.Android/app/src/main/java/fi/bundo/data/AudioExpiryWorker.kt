package fi.bundo.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fi.bundo.BunDoApplication

/** Best-effort background expiry. Opening/retrying/exporting also enforces the deadline. */
class AudioExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val accounts = (applicationContext as BunDoApplication).accounts
        val account = accounts.matching(inputData.getString("owner"), inputData.getString("generation"))
            ?: return Result.success()
        return try {
            account.recordings.prune()
            Result.success()
        } catch (_: kotlinx.coroutines.CancellationException) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
