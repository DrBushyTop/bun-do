package fi.bundo.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import fi.bundo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

internal data class JourneyProgress(
    val enabledAt: String, val credits: Int, val routeId: String, val locationId: String,
    val locationIndex: Int, val locationCount: Int, val locationCompletions: Int,
    val completionsPerLocation: Int, val completedRoutes: List<String>, val resting: Boolean,
) {
    /** Published routes are append-only, so a newer reply cannot move Bun back along a path. */
    fun follows(previous: JourneyProgress): Boolean {
        if (enabledAt != previous.enabledAt || credits < previous.credits ||
            completedRoutes.take(previous.completedRoutes.size) != previous.completedRoutes) return false
        if (routeId != previous.routeId) {
            if (routeId in previous.completedRoutes || previous.routeId !in completedRoutes) return false
            return previous.resting || completedRoutes.getOrNull(previous.completedRoutes.size) == previous.routeId
        }
        if (locationCount != previous.locationCount || completionsPerLocation != previous.completionsPerLocation ||
            locationIndex < previous.locationIndex || previous.resting && !resting) return false
        if (locationIndex == previous.locationIndex &&
            (locationId != previous.locationId || locationCompletions < previous.locationCompletions)) return false
        val history = if (resting && !previous.resting) previous.completedRoutes + routeId else previous.completedRoutes
        return completedRoutes == history
    }

    companion object {
        fun read(snapshot: JSONObject): JourneyProgress? {
            if (snapshot.isNull("journey")) return null
            val value = snapshot.getJSONObject("journey")
            fun id(key: String) = value.getString(key).also { require(it.matches(Regex("[a-z0-9-]{1,80}"))) }
            val history = value.getJSONArray("completedRouteIds")
            require(history.length() <= 128)
            val routes = (0 until history.length()).map { history.getString(it).also { route ->
                require(route.matches(Regex("[a-z0-9-]{1,80}")))
            } }
            require(routes.distinct().size == routes.size)
            val result = JourneyProgress(value.getString("enabledAt"), value.getInt("credits"),
                id("routeId"), id("locationId"), value.getInt("locationIndex"), value.getInt("locationCount"),
                value.getInt("locationCompletions"), value.getInt("completionsPerLocation"),
                routes, value.getBoolean("resting"))
            require(Instant.parse(result.enabledAt) <= Instant.parse(snapshot.getString("asOf")))
            require(result.credits in 0..snapshot.getJSONObject("statistics").getInt("lifetimeCount"))
            require(result.locationCount in 1..128 && result.locationIndex in 0 until result.locationCount)
            require(result.completionsPerLocation in 1..1000 && result.locationCompletions in 0..result.completionsPerLocation)
            require(result.credits >= result.locationIndex * result.completionsPerLocation + result.locationCompletions)
            require(!result.resting || result.locationIndex == result.locationCount - 1 &&
                result.locationCompletions == result.completionsPerLocation && routes.lastOrNull() == result.routeId)
            require(result.resting || result.routeId !in routes && result.locationCompletions < result.completionsPerLocation)
            return result
        }
    }
}

internal object SharedJourney {
    suspend fun refresh(context: Context, repository: SharedRepository, token: String, enable: Boolean) {
        val request = checkNotNull(repository.prepareJourney(SystemClock.elapsedRealtime(),
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))) { "Sync busy" }
        try {
            check(repository.applyJourney(request, JourneyEndpoint().send(token, request.workspace, enable))) { "Session changed" }
        } catch (failure: SyncFailure) {
            if (failure.code in listOf("FORBIDDEN", "REGISTRATION_RETIRED", "EPOCH_CHANGED"))
                repository.block(request, failure.code)
            throw failure
        } finally { repository.release(request) }
    }
}

internal class JourneyEndpoint {
    suspend fun send(token: String, state: SharedWorkspace, enable: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/journey").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val body = JSONObject().put("action", if (enable) "enable" else "read").put("workspaceId", state.workspaceId)
                .put("stateEpoch", state.epoch).put("registrationId", state.registration).toString().toByteArray(Charsets.UTF_8)
            require(body.size <= 1024)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val response = stream?.use {
                val buffer = ByteArray(1024 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count < buffer.size)
                if (count == 0) JSONObject() else JSONObject(String(buffer, 0, count, Charsets.UTF_8))
            } ?: JSONObject()
            if (status != 200) throw SyncFailure(response.optString("code", "UNAVAILABLE"))
            response
        } finally { connection.disconnect() }
    }
}
