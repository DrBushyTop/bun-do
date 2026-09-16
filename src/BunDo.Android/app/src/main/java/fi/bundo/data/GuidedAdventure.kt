package fi.bundo.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class GuidedPhase(val rootId: String?, val taskTitle: String?, val name: String, val stars: Int, val minutes: Int) {
    fun json() = JSONObject().put("rootId", rootId ?: JSONObject.NULL).put("taskTitle", taskTitle ?: JSONObject.NULL)
        .put("name", name).put("stars", stars).put("minutes", minutes)
}
internal data class GuidedDraft(val title: String, val flavor: String, val phases: List<GuidedPhase>) {
    fun json() = JSONObject().put("title", title).put("flavor", flavor).put("phases", JSONArray(phases.map { it.json() }))
    companion object {
        fun read(json: JSONObject): GuidedDraft {
            val array = json.getJSONArray("phases")
            require(array.length() in 1..8)
            val phases = (0 until array.length()).map { array.getJSONObject(it).let { p ->
                GuidedPhase(p.nullableString("rootId"), p.nullableString("taskTitle"), p.getString("name"), p.getInt("stars"), p.getInt("minutes")) } }
            AdventureDraft.read(AdventureDraft(json.getString("title"), json.getString("flavor"), phases.mapIndexed { i, p ->
                AdventurePhase(p.rootId ?: "new-$i", p.name, p.stars, p.minutes) }).json(), false)
            require(phases.all { if (it.rootId != null) it.taskTitle == null else it.taskTitle?.let { t ->
                t.isNotBlank() && t == t.trim() && t.codePointCount(0, t.length) <= 160 && t.none(Char::isISOControl) } == true })
            return GuidedDraft(json.getString("title"), json.getString("flavor"), phases)
        }
    }
}
internal data class GuidedCreation(val id: String, val version: String, val draft: GuidedDraft, val stage: String,
    val roots: List<String>? = null) {
    fun json() = JSONObject().put("id", id).put("version", version).put("draft", draft.json()).put("stage", stage)
        .put("roots", roots?.let(::JSONArray) ?: JSONObject.NULL)
    companion object {
        fun read(text: String): GuidedCreation = JSONObject(text).let { json ->
            GuidedCreation(json.getString("id"), json.getString("version"), GuidedDraft.read(json.getJSONObject("draft")), json.getString("stage"),
                json.optJSONArray("roots")?.let { a -> (0 until a.length()).map(a::getString) })
        }
    }
}

/** The approval checkpoint and queued task IDs survive process death. Only ordinary sync creates tasks. */
internal object GuidedAdventure {
    suspend fun plan(context: Context, repository: SharedRepository, token: String, outcome: String, minutes: Int?,
        send: suspend (String, SharedWorkspace, JSONObject) -> JSONObject = AdventureEndpoint()::send) {
        val request = prepare(context, repository)
        try {
            val result = send(token, request.workspace, JSONObject().put("action", "plan").put("outcome", outcome).put("minutes", minutes ?: JSONObject.NULL))
            repository.saveGuidedPlan(request, result.getJSONObject("snapshot"), GuidedDraft.read(result.getJSONObject("draft")))
        } catch (failure: SyncFailure) { block(repository, request, failure); throw failure }
        finally { repository.release(request) }
    }

    suspend fun resume(context: Context, repository: SharedRepository, token: String,
        send: suspend (String, SharedWorkspace, JSONObject) -> JSONObject = AdventureEndpoint()::send) {
        val request = prepare(context, repository)
        try {
            var local = repository.guidedCreation() ?: return
            require(local.stage in listOf("APPROVED", "QUEUED"))
            var result = send(token, request.workspace, JSONObject().put("action", "read"))
            check(repository.applyAdventure(request, result))
            var snapshot = AdventureSnapshot.read(result)
            if (snapshot.active?.id == local.id) { repository.endGuidedCreation(request, local.id); return }
            if (local.stage == "QUEUED" && snapshot.creation?.getString("id") != local.id) {
                repository.stopGuidedCreation(request, local.id); return
            }
            if (snapshot.creation == null) {
                result = send(token, request.workspace, JSONObject().put("action", "beginCreation").put("creationId", local.id)
                    .put("version", local.version).put("draft", local.draft.json()))
                check(repository.applyAdventure(request, result)); snapshot = AdventureSnapshot.read(result)
            }
            if (snapshot.creation == null) { repository.endGuidedCreation(request, local.id); return }
            require(snapshot.creation.getString("id") == local.id && snapshot.creation.getString("registrationId") == request.workspace.registration)
            local = repository.queueGuidedCreation(request, local.id)
            try {
                result = send(token, request.workspace, JSONObject().put("action", "finishCreation").put("creationId", local.id).put("roots", JSONArray(local.roots)))
                check(repository.applyAdventure(request, result))
                if (AdventureSnapshot.read(result).active?.id == local.id) repository.endGuidedCreation(request, local.id)
            } catch (failure: SyncFailure) { if (failure.code != "TASKS_PENDING") throw failure }
        } catch (failure: SyncFailure) {
            if (failure.code in listOf("ADVENTURE_CHANGED", "ADVENTURE_ACTIVE", "SOURCE_UNAVAILABLE", "CREATION_PENDING"))
                repository.resetUnstartedGuidedCreation(request)
            block(repository, request, failure); throw failure
        }
        finally { repository.release(request) }
    }
    private suspend fun prepare(context: Context, repository: SharedRepository) = checkNotNull(repository.prepareJourney(
        SystemClock.elapsedRealtime(), Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0))) { "Sync busy" }
    private suspend fun block(repository: SharedRepository, request: SharedRequest, failure: SyncFailure) {
        if (failure.code in listOf("FORBIDDEN", "REGISTRATION_RETIRED", "EPOCH_CHANGED")) repository.block(request, failure.code)
    }
}
