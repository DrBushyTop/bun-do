package fi.bundo.identity

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Suppress("UNUSED_PARAMETER")
internal fun createTokenProvider(application: Application): TokenProvider = LocalTokens(application)

private class LocalTokens(private val application: Application) : TokenProvider {
    override val issuer = "urn:bun-do:local"
    override val choices = listOf("Alice", "Bob")
    private var selected: String? = null
    override suspend fun restore(): Boolean {
        selected = (application as fi.bundo.BunDoApplication).accounts.active.value?.identity
            ?.takeIf { it.issuer == issuer && it.subject in listOf("alice", "bob") }?.subject
        return selected != null
    }
    override suspend fun signIn(activity: Activity, choice: String?): String {
        require(choice in choices)
        val account = if (choice == "Alice") "alice" else "bob"
        val token = issue(account)
        selected = account
        return token
    }
    override suspend fun refresh(): String = issue(checkNotNull(selected))
    override suspend fun signOut() { selected = null }

    private suspend fun issue(account: String): String = withContext(Dispatchers.IO) {
        val connection = URL("http://10.0.2.2:7275/api/dev/token/$account/valid")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            if (connection.responseCode != 200) throw TokenFailure("local_token_unavailable",
                retryable = connection.responseCode !in setOf(401, 403))
            val bytes = ByteArray(16 * 1024 + 1)
            var count = 0
            connection.inputStream.use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
            }
            check(count <= 16 * 1024)
            JSONObject(String(bytes, 0, count, Charsets.UTF_8)).getString("access_token")
        } finally { connection.disconnect() }
    }
}
