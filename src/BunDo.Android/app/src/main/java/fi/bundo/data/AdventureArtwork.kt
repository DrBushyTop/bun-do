package fi.bundo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import fi.bundo.BuildConfig
import fi.bundo.BunDoApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class ArtworkResult(val status: String, val image: Bitmap? = null)
internal class ArtworkCache(private val directory: File, private val lease: DataLease) {
    private fun file(key: String): File { require(key.matches(Regex("dojo-v1-(home|storage|garden|kitchen|clean)"))); return File(directory, "$key.jpg") }
    suspend fun read(key: String): Bitmap? = lease.access {
        val file = file(key)
        val image = if (!file.isFile || file.length() > 8 * 1024 * 1024) null else decode(file.readBytes())
        lease.check()
        image
    }
    suspend fun save(key: String, bytes: ByteArray): Bitmap = lease.access {
        val image = checkNotNull(decode(bytes))
        directory.mkdirs(); val target = file(key); val temp = File(directory, "${UUID.randomUUID()}.tmp")
        try { temp.writeBytes(bytes); lease.check(); check(temp.renameTo(target)); image }
        finally { temp.delete() }
    }
    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size > 8 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048 || bounds.outMimeType != "image/jpeg") return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
internal object AdventureArtworkClient {
    suspend fun load(context: Context, repository: SharedRepository, data: AccountData, choice: AdventureChoice, retry: Boolean): ArtworkResult = withContext(Dispatchers.IO) {
        val cache = ArtworkCache(File(checkNotNull(data.directory), "adventure-artwork"), data.lease)
        val current = repository.artworkChoice(choice.id) ?: return@withContext ArtworkResult("UNAVAILABLE")
        if (!retry && current.artwork != "dojo-garden") cache.read(current.artwork)?.let { return@withContext ArtworkResult("READY", it) }
        val workspace = repository.prepareAdventureRead() ?: return@withContext ArtworkResult("UNAVAILABLE")
        (context.applicationContext as BunDoApplication).withAccountToken(data) { token ->
            fetch(repository, workspace, current, retry, cache, token).also { data.lease.check() }
        }
    }

    internal suspend fun fetch(repository: SharedRepository, workspace: SharedWorkspace, choice: AdventureChoice,
        retry: Boolean, cache: ArtworkCache, token: String,
        sendRequest: suspend (String, JSONObject) -> ByteArray = { bearer, body -> send(bearer, body) }): ArtworkResult {
        try {
            val snapshot = AdventureSnapshot.read(JSONObject(checkNotNull(workspace.adventure)))
            val body = JSONObject().put("workspaceId", workspace.workspaceId).put("stateEpoch", workspace.epoch)
                .put("registrationId", workspace.registration).put("batchId", choice.batchId ?: snapshot.batchId).put("choiceId", choice.id)
            body.put("action", if (retry) "retry" else "read")
            val reply = JSONObject(String(sendRequest(token, body), Charsets.UTF_8))
            val status = reply.getString("status"); val key = reply.getString("key")
            val image = if (status == "READY") cache.read(key) ?: cache.save(key, sendRequest(token, body.put("action", "image"))) else null
            check(repository.applyAdventureRead(workspace, reply.getJSONObject("snapshot")))
            return ArtworkResult(status, image)
        } catch (failure: SyncFailure) {
            if (failure.code in listOf("FORBIDDEN", "REGISTRATION_RETIRED", "EPOCH_CHANGED")) repository.blockAdventureRead(workspace, failure.code)
            throw failure
        }
    }
    private fun send(token: String, body: JSONObject): ByteArray {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/adventure-artwork").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.requestMethod = "POST"; connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json"); connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val bytes = (if (status == 200) connection.inputStream else connection.errorStream)?.use { stream ->
                val buffer = ByteArray(8 * 1024 * 1024 + 1); var count = 0
                while (count < buffer.size) { val n = stream.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                require(count < buffer.size); buffer.copyOf(count)
            } ?: ByteArray(0)
            if (status != 200) throw SyncFailure(runCatching { JSONObject(String(bytes, Charsets.UTF_8)).getString("code") }.getOrDefault("UNAVAILABLE"))
            return bytes
        } finally { connection.disconnect() }
    }
}
