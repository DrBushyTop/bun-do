package fi.bundo.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import fi.bundo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

internal data class AdventurePhase(val rootId: String, val name: String, val stars: Int, val minutes: Int) {
    fun json() = JSONObject().put("rootId", rootId).put("name", name).put("stars", stars).put("minutes", minutes)
}
internal data class AdventureDraft(val title: String, val flavor: String, val phases: List<AdventurePhase>) {
    fun json() = JSONObject().put("title", title).put("flavor", flavor).put("phases", JSONArray(phases.map { it.json() }))
    companion object {
        fun read(value: JSONObject, allowEmpty: Boolean): AdventureDraft {
            fun text(value: String, max: Int, empty: Boolean = false) = value.also {
                require((empty || it.isNotBlank()) && it == it.trim() && it.codePointCount(0, it.length) <= max && it.none(Char::isISOControl))
            }
            val array = value.getJSONArray("phases")
            require(array.length() in (if (allowEmpty) 0 else 1)..8)
            val phases = (0 until array.length()).map { index -> array.getJSONObject(index).let {
                AdventurePhase(text(it.getString("rootId"), 200), text(it.getString("name"), 160),
                    it.getInt("stars").also { n -> require(n in 1..3) }, it.getInt("minutes").also { n -> require(n in 1..1440) })
            } }
            require(phases.map { it.rootId }.distinct().size == phases.size)
            return AdventureDraft(text(value.getString("title"), 160), text(value.getString("flavor"), 600, true), phases)
        }
    }
}
internal data class AdventureChoice(val id: String, val draft: AdventureDraft, val artwork: String,
    val batchId: String? = null, val version: String? = null)
internal data class AdventureSnapshot(val workspaceId: String, val epoch: String, val revision: ULong,
    val batchId: String?, val status: String?, val expiresAt: Instant?, val leaseUntil: Instant?, val proposals: List<AdventureChoice>,
    val active: AdventureChoice?, val roots: List<JSONObject>, val tasks: Map<String, JSONObject> = emptyMap(), val creation: JSONObject? = null) {
    val total get() = active?.draft?.phases?.size ?: 0
    val completed get() = active?.draft?.phases?.count { phase -> available(tasks[phase.rootId]) && tasks[phase.rootId]?.optString("lifecycle") == "COMPLETED" } ?: 0
    val complete get() = total > 0 && completed == total
    fun batchStatus(now: Instant): String? = when {
        status == "READY" && (expiresAt == null || now >= expiresAt ||
            proposals.any { it.draft.phases.size < 3 } || proposals.size == 2 &&
            proposals[0].draft.phases.map { it.rootId }.toSet() == proposals[1].draft.phases.map { it.rootId }.toSet()) -> "EXPIRED"
        status == "RUNNING" && (leaseUntil == null || now >= leaseUntil) -> "FAILED"
        else -> status
    }
    fun project(state: SharedWorkspace, base: List<JSONObject>, intents: List<SharedIntent>): AdventureSnapshot {
        val projectedBase = base.associateBy { it.getString("id") }.toMutableMap()
        if (state.revision.toULong() < revision) for (root in roots) {
            val id = root.getString("rootId")
            // A newer read owns these roots until ordinary sync catches up. Never advance the sync cursor here.
            projectedBase.entries.removeAll { it.value.nullableString("parentId") == id }
            if (!root.getBoolean("available")) projectedBase[id] = JSONObject().put("id", id).put("entityType", "PURGED_TASK")
            else {
                projectedBase[id] = root.getJSONObject("task")
                val children = root.getJSONArray("checklist")
                for (i in 0 until children.length()) children.getJSONObject(i).let { projectedBase[it.getString("id")] = it }
            }
        }
        return copy(tasks = SharedProjectionReplay.replay(projectedBase.values.toList(), intents,
            maxOf(state.revision.toULong(), revision)).tasks)
    }
    companion object {
        fun available(task: JSONObject?) = task != null && task.isNull("parentId") && task.isNull("deletion") && task.optString("lifecycle") in listOf("OPEN", "COMPLETED")
        private fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it && it != "00000000-0000-0000-0000-000000000000") }
        fun read(json: JSONObject): AdventureSnapshot {
            val board = json.getJSONObject("board")
            val revision = json.decimal("revision")
            fun choice(value: JSONObject, active: Boolean): AdventureChoice {
                val art = value.getString("artwork").also { require(it.matches(Regex("[a-zA-Z0-9-]{1,100}"))) }
                val version = if (active) value.getString("version").also { require(value.decimal("version") <= revision) } else null
                if (active) Instant.parse(value.getString("acceptedAt"))
                return AdventureChoice(uuid(value.getString("id")), AdventureDraft.read(value.getJSONObject("draft"), active), art,
                    if (active) uuid(value.getString("batchId")) else null, version)
            }
            val batch = board.optJSONObject("batch")
            val status = batch?.getString("status")?.also { require(it in listOf("EMPTY", "RUNNING", "READY", "FAILED", "EXPIRED", "CONSUMED")) }
            val expires = batch?.nullableString("expiresAt")?.let(Instant::parse)
            val lease = batch?.nullableString("leaseUntil")?.let(Instant::parse)
            val proposals = batch?.optJSONArray("proposals")?.let { array ->
                require(array.length() in 1..2 && status == "READY")
                (0 until array.length()).map { choice(array.getJSONObject(it), false) }.also { require(it.map { c -> c.id }.distinct().size == it.size) }
            } ?: emptyList()
            require(status != "READY" || proposals.size in 1..2 && expires != null)
            require(status != "RUNNING" || lease != null)
            val active = board.optJSONObject("active")?.let { choice(it, true) }
            require(active == null || status == "CONSUMED" && batch?.getString("id") == active.batchId)
            val creation = board.optJSONObject("creation")?.also { pending ->
                uuid(pending.getString("id")); uuid(pending.getString("memberId")); uuid(pending.getString("registrationId"))
                require(pending.decimal("revision") <= revision && active == null)
                Instant.parse(pending.getString("startedAt")); GuidedDraft.read(pending.getJSONObject("draft"))
            }
            val progress = json.optJSONObject("progress")
            require((active == null) == (progress == null))
            val roots = progress?.getJSONArray("roots")?.let { array ->
                require(array.length() == active!!.draft.phases.size)
                (0 until array.length()).map { array.getJSONObject(it) }
            } ?: emptyList()
            require(roots.map { it.getString("rootId") } == active?.draft?.phases?.map { it.rootId }.orEmpty())
            for (root in roots) {
                val children = root.getJSONArray("checklist"); require(children.length() <= 64)
                if (root.getBoolean("available")) {
                    val task = root.getJSONObject("task")
                    SharedProtocol.validateTask(task, revision)
                    require(task.getString("id") == root.getString("rootId") && available(task))
                    for (i in 0 until children.length()) {
                        SharedProtocol.validateTask(children.getJSONObject(i), revision)
                        require(children.getJSONObject(i).nullableString("parentId") == task.getString("id"))
                    }
                } else require(root.isNull("task") && children.length() == 0)
            }
            return AdventureSnapshot(uuid(json.getString("workspaceId")), uuid(json.getString("stateEpoch")), revision,
                batch?.getString("id")?.let(::uuid), status, expires, lease, proposals, active, roots, creation = creation)
        }
    }
}

internal object SharedAdventure {
    suspend fun send(context: Context, repository: SharedRepository, token: String, action: JSONObject,
        sendRequest: suspend (String, SharedWorkspace, JSONObject) -> JSONObject = AdventureEndpoint()::send) {
        val request = checkNotNull(repository.prepareJourney(SystemClock.elapsedRealtime(),
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))) { "Sync busy" }
        try {
            if (action.getString("action") == "visit") {
                val read = sendRequest(token, request.workspace, JSONObject().put("action", "read"))
                check(repository.applyAdventure(request, read))
                val snapshot = AdventureSnapshot.read(read)
                if (snapshot.active == null && snapshot.creation == null && snapshot.batchStatus(Instant.now()) in listOf(null, "EMPTY", "EXPIRED", "CONSUMED"))
                    check(repository.applyAdventure(request, sendRequest(token, request.workspace, JSONObject().put("action", "refresh")
                        .put("batchId", snapshot.batchId ?: JSONObject.NULL))))
            } else {
                try {
                    val result = sendRequest(token, request.workspace, action)
                    check(repository.applyAdventure(request, result))
                    if (action.getString("action") in listOf("leave", "dismiss")) {
                        val closed = AdventureSnapshot.read(result)
                        if (closed.active == null) check(repository.applyAdventure(request,
                            sendRequest(token, request.workspace, JSONObject().put("action", "refresh").put("batchId", closed.batchId ?: JSONObject.NULL))))
                    }
                }
                catch (failure: SyncFailure) {
                    if (failure.code in listOf("ADVENTURE_CHANGED", "ADVENTURE_ACTIVE", "SUGGESTIONS_UNAVAILABLE", "SOURCE_UNAVAILABLE", "ADVENTURE_NOT_COMPLETE"))
                        repository.applyAdventure(request, sendRequest(token, request.workspace, JSONObject().put("action", "read")))
                    throw failure
                }
            }
        } catch (failure: SyncFailure) {
            if (failure.code in listOf("FORBIDDEN", "REGISTRATION_RETIRED", "EPOCH_CHANGED")) repository.block(request, failure.code)
            throw failure
        } finally { repository.release(request) }
    }
}

internal class AdventureEndpoint {
    suspend fun send(token: String, state: SharedWorkspace, action: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("${BuildConfig.IDENTITY_API_BASE}/adventure").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 110_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token"); connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            val body = JSONObject(action.toString()).put("workspaceId", state.workspaceId).put("stateEpoch", state.epoch)
                .put("registrationId", state.registration).toString().toByteArray(Charsets.UTF_8)
            require(body.size <= 16 * 1024); connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val response = (if (status == 200) connection.inputStream else connection.errorStream)?.use { stream ->
                val bytes = ByteArray(4 * 1024 * 1024 + 1); var count = 0
                while (count < bytes.size) { val read = stream.read(bytes, count, bytes.size - count); if (read < 0) break; count += read }
                require(count < bytes.size)
                if (count == 0) JSONObject() else JSONObject(String(bytes, 0, count, Charsets.UTF_8))
            } ?: JSONObject()
            if (status != 200) throw SyncFailure(response.optString("code", "UNAVAILABLE"))
            response
        } finally { connection.disconnect() }
    }
}
