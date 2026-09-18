package fi.bundo.data

import android.content.Context
import fi.bundo.household.HouseholdEndpoint
import org.json.JSONObject
import java.util.UUID

internal data class VisibilityPreview(val source: SharedWorkspace, val target: SharedWorkspace, val tasks: List<JSONObject>, val id: String = UUID.randomUUID().toString()) {
    fun request() = JSONObject().put("action", "visibility").put("transferId", id)
        .put("workspaceId", source.workspaceId).put("stateEpoch", source.epoch)
        .put("expectedRevision", source.revision).put("taskId", tasks.first().getString("id"))
        .put("targetWorkspaceId", target.workspaceId).put("targetEpoch", target.epoch)
}

internal object SharedVisibility {
    suspend fun send(context: Context, data: AccountData, repository: SharedRepository, preview: VisibilityPreview, token: String): String {
        val request = preview.request()
        repository.saveVisibilityRequest(request)
        val code = deliver(data, repository.scope, request, token)
        SharedSyncWorker.request(context, data)
        return code
    }

    private suspend fun deliver(data: AccountData, scope: String, request: JSONObject, token: String): String {
        val reply = HouseholdEndpoint().send(token, checkNotNull(data.registrationId), request)
        val code = reply.getString("code")
        if (code !in listOf("BUSY", "TASK_LIMIT", "STORAGE_FULL")) data.lease.access {
            data.database.shared().deleteDraft(scope, "visibility:${request.getString("transferId")}")
        }
        return code
    }

    suspend fun pending(data: AccountData, token: String): List<JSONObject> {
        val array = HouseholdEndpoint().send(token, checkNotNull(data.registrationId), JSONObject().put("action", "visibilityPending")).getJSONArray("pending")
        return (0 until array.length()).map(array::getJSONObject)
    }

    suspend fun cancel(data: AccountData, token: String, id: String): String =
        HouseholdEndpoint().send(token, checkNotNull(data.registrationId), JSONObject().put("action", "visibilityCancel").put("transferId", id)).getString("code")

    suspend fun retry(data: AccountData, token: String) {
        val drafts = data.lease.access { data.database.shared().allDrafts().filter { it.key.startsWith("visibility:") } }
        for (draft in drafts) deliver(data, draft.scope, JSONObject(draft.description), token)
        // A private server journal also recovers a transfer after reinstall or household removal.
        val personal = data.lease.access { data.database.shared().workspaces(checkNotNull(data.registrationId)).firstOrNull { it.personal } } ?: return
        val pending = HouseholdEndpoint().send(token, checkNotNull(data.registrationId), JSONObject().put("action", "visibilityPending")).getJSONArray("pending")
        for (index in 0 until pending.length()) {
            val item = pending.getJSONObject(index)
            val saved = item.getJSONObject("request")
            val request = JSONObject().put("action", "visibility").put("transferId", saved.getString("id"))
                .put("workspaceId", saved.getString("source")).put("stateEpoch", saved.getString("sourceEpoch"))
                .put("taskId", saved.getString("taskId")).put("expectedRevision", saved.getString("expectedRevision"))
                .put("targetWorkspaceId", saved.getString("target")).put("targetEpoch", saved.getString("targetEpoch"))
            deliver(data, personal.scope, request, token)
        }
    }
}
