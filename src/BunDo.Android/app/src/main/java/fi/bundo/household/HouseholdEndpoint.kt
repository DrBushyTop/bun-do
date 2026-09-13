package fi.bundo.household

import fi.bundo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class HouseholdFailure(val code: String) : Exception()

internal fun membershipRequest(action: String, home: Household, invitation: HouseholdInvitation? = null,
    member: HouseholdMember? = null, code: String? = null): JSONObject {
    val version = when (action) {
        "transfer", "delete" -> home.version
        "remove", "leave" -> checkNotNull(member).version
        "approve", "cancel" -> checkNotNull(invitation).version
        else -> home.version
    }
    return JSONObject().put("action", action).put("workspaceId", home.id).put("stateEpoch", home.epoch)
        .put("expectedVersion", version).also { body ->
            invitation?.let { body.put("invitationId", it.id) }
            member?.let { body.put("memberId", it.id) }
            code?.let { body.put("confirmationCode", it) }
        }
}

internal class HouseholdEndpoint {
    suspend fun send(token: String, registration: String, request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/households").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val bytes = request.put("registrationId", registration).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 4096)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { input ->
                val buffer = ByteArray(1024 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                check(count < buffer.size)
                if (count == 0) JSONObject() else JSONObject(String(buffer, 0, count, Charsets.UTF_8))
            } ?: JSONObject()
            if (status !in 200..299) throw HouseholdFailure(response.optString("code", when (status) {
                401 -> "SIGN_IN_REQUIRED"
                else -> "UNAVAILABLE"
            }))
            response
        } finally { connection.disconnect() }
    }
}

internal data class HouseholdMember(val id: String, val version: String, val owner: Boolean, val displayName: String)
internal data class HouseholdInvitation(val id: String, val version: String, val phase: String,
    val expiresAt: String, val code: String, val candidateName: String)
internal data class Household(val id: String, val epoch: String, val version: String, val me: String,
    val owner: Boolean, val active: Boolean, val name: String, val deleted: Boolean,
    val members: List<HouseholdMember>, val invitations: List<HouseholdInvitation>) {
    companion object {
        fun read(json: JSONObject): Household {
            val members = json.getJSONArray("members")
            val invitations = json.getJSONArray("invitations")
            return Household(json.getString("id"), json.getString("stateEpoch"), json.getString("membershipVersion"),
                json.getString("me"), json.getBoolean("owner"), json.getBoolean("active"), json.getString("name"),
                !json.isNull("deletedAt"),
                (0 until members.length()).map { members.getJSONObject(it).let { m ->
                    HouseholdMember(m.getString("id"), m.getString("version"), m.getBoolean("owner"), m.getString("displayName")) } },
                (0 until invitations.length()).map { invitations.getJSONObject(it).let { i ->
                    HouseholdInvitation(i.getString("id"), i.getString("version"), i.getString("phase"),
                        i.getString("expiresAt"), if (i.isNull("confirmationCode")) "" else i.getString("confirmationCode"), i.getString("candidateName")) } })
        }
    }
}
