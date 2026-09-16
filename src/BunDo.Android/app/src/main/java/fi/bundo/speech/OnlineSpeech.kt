package fi.bundo.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.bundo.BuildConfig
import fi.bundo.data.InboxLimits
import fi.bundo.data.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechFailure(val code: String) : Exception()

/** Authentication rejection needs a visible sign-in action, not silent model fallback. */
internal suspend fun authenticatedSpeech(operation: suspend () -> String): String = try {
    operation()
} catch (error: fi.bundo.identity.TokenFailure) {
    throw SpeechFailure(if (error.retryable) "ONLINE_FAILED" else "SIGN_IN_REQUIRED")
} catch (error: fi.bundo.identity.IdentityRejected) {
    throw SpeechFailure(if (error.status in setOf(401, 403)) "SIGN_IN_REQUIRED" else "ONLINE_FAILED")
}

fun speechNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

/** One explicit upload. No redirects or automatic retry of an uncertain paid request. */
class OnlineSpeech(
    private val base: String = BuildConfig.IDENTITY_API_BASE,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun transcribe(token: String, registration: String, pcm: ByteArray, locale: String): String =
        withTimeout(120_000) {
            require(pcm.size.toLong() in 2..RecordingStore.MAX_AUDIO_BYTES && pcm.size % 2 == 0)
            require(locale in setOf("fi-FI", "en-US"))
            request(token, registration, pcm, "transcribe", "application/octet-stream", locale).getString("text").also {
                if (InboxLimits.length(it) > InboxLimits.DESCRIPTION) throw SpeechFailure("TOO_LONG")
            }
        }

    suspend fun analyze(token: String, registration: String, transcript: String): fi.bundo.data.VoiceDraft = withTimeout(110_000) {
        require(transcript.isNotBlank() && InboxLimits.length(transcript) <= InboxLimits.DESCRIPTION)
        val bytes = JSONObject().put("transcript", transcript).toString().toByteArray(Charsets.UTF_8)
        try {
            val result = request(token, registration, bytes, "analyze", "application/json", null)
            val items = result.getJSONArray("items")
            fi.bundo.data.VoiceDraft(transcript, result.getString("title"), result.getString("description"),
                (0 until items.length()).map(items::getString)).also { require(it.valid) }
        } finally { bytes.fill(0) }
    }

    private suspend fun request(token: String, registration: String, body: ByteArray, route: String,
        contentType: String, locale: String?): JSONObject {
            val url = URL("$base/speech/$route")
            require(url.protocol == "https" || BuildConfig.DEBUG && url.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
            val connection = connectionFactory(url)
            return coroutineScope {
                suspendCancellableCoroutine { continuation ->
                    // Disconnect wakes a blocked upload/read; the controller still fences any late result.
                    continuation.invokeOnCancellation { connection.disconnect() }
                    launch(Dispatchers.IO) {
                        try {
                            if (!continuation.isActive) return@launch
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 110_000
                            connection.requestMethod = "POST"
                            connection.setRequestProperty("Authorization", "Bearer $token")
                            connection.setRequestProperty("X-BunDo-Registration", registration)
                            if (locale != null) connection.setRequestProperty("X-BunDo-Locale", locale)
                            connection.setRequestProperty("Content-Type", contentType)
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(body.size)
                            connection.outputStream.use { it.write(body) }
                            val status = connection.responseCode
                            if (status != 200) throw SpeechFailure(when (status) {
                                401, 403 -> "SIGN_IN_REQUIRED"
                                413, 422 -> "TOO_LONG"
                                else -> "ONLINE_FAILED"
                            })
                            val text = connection.inputStream.use { input ->
                                val buffer = ByteArray(64 * 1024 + 1)
                                var count = 0
                                while (count < buffer.size) {
                                    val read = input.read(buffer, count, buffer.size - count)
                                    if (read < 0) break
                                    count += read
                                }
                                check(count < buffer.size)
                                try { JSONObject(String(buffer, 0, count, Charsets.UTF_8)) }
                                finally { buffer.fill(0) }
                            }
                            continuation.resume(text)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (error is SpeechFailure) error else SpeechFailure("ONLINE_FAILED"))
                        } finally { connection.disconnect() }
                    }
                }
            }
        }
}
