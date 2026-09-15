package fi.bundo.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Uses the ordinary fenced sync path, not a second write API or an offline schedule prediction. */
internal object SharedRepeatSetup {
    suspend fun checkConnection(context: Context, repository: SharedRepository, token: String) {
        repeat(40) {
            val request = checkNotNull(repository.prepare(SystemClock.elapsedRealtime(),
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))) { "Sync busy" }
            try {
                val reply = SharedEndpoint().send(token, request)
                check(reply.getString("code") == "ACCEPTED")
                if (!repository.apply(request, reply)) return
            } finally { repository.release(request) }
        }
        error("Sync unfinished")
    }
}
