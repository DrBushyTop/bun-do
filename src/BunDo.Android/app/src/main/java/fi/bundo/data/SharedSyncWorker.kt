package fi.bundo.data

import android.content.Context
import android.os.SystemClock
import android.os.StatFs
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fi.bundo.BuildConfig
import fi.bundo.BunDoApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal class SyncFailure(val code: String) : Exception()

internal class SharedEndpoint {
    fun recovery(token: String): SnapshotTransport = object : SnapshotTransport {
        override suspend fun manifest(state: SharedWorkspace, id: String): JSONObject =
            JSONObject(String(read(token, "snapshots/$id/manifest?${query(state)}", "POST"), Charsets.UTF_8))
        override suspend fun chunk(state: SharedWorkspace, id: String, index: Int): ByteArray =
            read(token, "snapshots/$id/$index?${query(state)}", "GET")
        override suspend fun outcomes(state: SharedWorkspace, first: String, count: Int): JSONObject =
            JSONObject(String(read(token, "devices/self/outcomes?${query(state)}&firstSequence=$first&count=$count", "GET"), Charsets.UTF_8))
    }

    private fun query(state: SharedWorkspace): String {
        // All query components are locally validated UUIDs, never arbitrary labels or text.
        for (id in listOf(state.workspaceId, state.epoch, state.registration)) require(java.util.UUID.fromString(id).toString() == id)
        return "workspaceId=${state.workspaceId}&stateEpoch=${state.epoch}&registrationId=${state.registration}"
    }

    private suspend fun read(token: String, path: String, method: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/$path").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val bytes = stream?.use {
                val buffer = ByteArray(4 * 1024 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count < buffer.size)
                buffer.copyOf(count)
            } ?: ByteArray(0)
            if (status != 200) throw SyncFailure(runCatching { JSONObject(String(bytes, Charsets.UTF_8)).getString("code") }.getOrDefault("UNAVAILABLE"))
            bytes
        } finally { connection.disconnect() }
    }

    suspend fun send(token: String, request: SharedRequest): JSONObject = withContext(Dispatchers.IO) {
        val state = request.workspace
        val envelopes = JSONArray()
        request.envelope?.let { envelopes.put(Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) }
        val body = JSONObject().put("workspaceId", state.workspaceId).put("stateEpoch", state.epoch)
            .put("registrationId", state.registration).put("cursor", state.cursor ?: JSONObject.NULL)
            .put("envelopes", envelopes).put("acknowledgedThrough", state.acknowledged).toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 1024 * 1024)
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/sync").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val reply = stream?.use {
                val buffer = ByteArray(4 * 1024 * 1024 + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count < buffer.size)
                if (count == 0) JSONObject() else JSONObject(String(buffer, 0, count, Charsets.UTF_8))
            } ?: JSONObject()
            if (status != 200) throw SyncFailure(reply.optString("code", "UNAVAILABLE"))
            reply
        } finally { connection.disconnect() }
    }
}

class SharedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BunDoApplication
        val data = app.accounts.matching(inputData.getString("owner"), inputData.getString("generation"))
            ?: return Result.success()
        if (data.identity == null || data.registrationId == null) return Result.success()
        return try {
            val scopes = data.lease.access { data.database.shared().workspaces(data.registrationId) }
            if (scopes.none { it.blocked == null }) return Result.success()
            app.withAccountToken(data) { token ->
                for (scope in scopes.filter { it.blocked == null }) {
                    val repository = SharedRepository(data.database, data.lease, scope.scope, data.registrationId)
                    val recovery = SharedSnapshotRecovery(data.database, data.lease, scope.scope)
                    val finished = runScope(repository, recovery, checkNotNull(data.database.openHelper.writableDatabase.path), token)
                    if (!finished) return@withAccountToken false
                }
                true
            }.let { if (it) Result.success() else Result.retry() }
        } catch (_: CancellationException) { Result.success() }
        catch (_: Exception) { Result.retry() }
    }

    private suspend fun runScope(repository: SharedRepository, recovery: SharedSnapshotRecovery, databasePath: String, token: String): Boolean {
        repeat(40) {
            val request = repository.prepare(SystemClock.elapsedRealtime(),
                Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT, 0)) ?: return true
            try {
                if (recovery.pending()) {
                    recovery.step(request, SharedEndpoint().recovery(token),
                        StatFs(applicationContext.noBackupFilesDir.path).availableBytes, java.io.File(databasePath).length())
                    return@repeat
                }
                val reply = SharedEndpoint().send(token, request)
                val code = reply.getString("code")
                if (code in listOf("REGISTRATION_RETIRED", "EPOCH_CHANGED", "OUTCOME_EXPIRED", "FORBIDDEN")) {
                    repository.block(request, code)
                    return true
                }
                val more = repository.apply(request, reply)
                if (code in listOf("BUSY", "SEQUENCE_GAP", "WORKSPACE_FULL")) return false
                if (!more) return true
            } catch (error: SyncFailure) {
                if (error.code == "SNAPSHOT_REQUIRED") {
                    recovery.begin(request)
                    return@repeat
                }
                if (error.code in listOf("REGISTRATION_RETIRED", "EPOCH_CHANGED", "FORBIDDEN")) {
                    repository.block(request, error.code)
                    return true
                }
                throw error
            } finally { repository.release(request) }
        }
        return false
    }

    companion object {
        fun request(context: Context, data: AccountData) {
            if (data.identity == null || !data.lease.active) return
            WorkManager.getInstance(context).enqueueUniqueWork("shared-sync:${data.lease.owner}",
                ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<SharedSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag("account:${data.lease.owner}")
                    .setInputData(workDataOf("owner" to data.lease.owner, "generation" to data.lease.generation)).build())
        }
    }
}
