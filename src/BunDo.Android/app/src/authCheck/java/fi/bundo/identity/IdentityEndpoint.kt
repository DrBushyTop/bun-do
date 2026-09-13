package fi.bundo.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import fi.bundo.BuildConfig
import java.util.UUID

internal class IdentityRejected(val status: Int) : Exception()

/** This development endpoint cannot be replaced by an intent extra or a redirect. */
internal class IdentityEndpoint(private val expectedIssuer: String) {
    suspend fun verify(accessToken: String): ValidatedIdentity = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/identity").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) throw IdentityRejected(connection.responseCode)
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(4097)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            check(bytes.size <= 4096)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val issuer = json.getString("issuer")
            val subject = json.getString("subject")
            check(issuer == expectedIssuer && subject.isNotBlank())
            ValidatedIdentity(issuer, subject)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun register(accessToken: String, installationId: String, revoke: String? = null): String = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/identity/registrations").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val request = JSONObject().put("installationId", installationId)
            if (revoke != null) request.put("revokeRegistrationId", revoke)
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val buffer = ByteArray(8193)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count <= 8192)
                buffer.copyOf(count)
            } ?: ByteArray(0)
            if (status == 409) {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val list = json.optJSONArray("activeRegistrations")
                throw RegistrationRejected(json.getString("code"), (0 until (list?.length() ?: 0)).map {
                    list!!.getJSONObject(it).getString("registrationId").also(UUID::fromString)
                })
            }
            if (status != 200) throw IdentityRejected(status)
            JSONObject(String(bytes, Charsets.UTF_8)).getString("registrationId").also(UUID::fromString)
        } finally { connection.disconnect() }
    }

    companion object {
        const val ISSUER = "https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0"
    }
}
internal class RegistrationRejected(val code: String, val active: List<String>) : Exception()
