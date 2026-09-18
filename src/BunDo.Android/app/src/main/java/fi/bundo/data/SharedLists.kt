package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class HouseholdListItem(val title: String, val notes: String? = null)
internal data class SavedHouseholdList(val id: String, val title: String, val notes: String?, val items: List<HouseholdListItem>, val pinned: Boolean = false) {
    fun valid() = runCatching { UUID.fromString(id) }.isSuccess && title.isNotBlank() && InboxLimits.length(title) <= 160 &&
        InboxLimits.length(notes.orEmpty()) <= 4000 && items.size in 1..SharedChecklistActions.MAX_ITEMS &&
        json().toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024 &&
        items.all { it.title.isNotBlank() && InboxLimits.length(it.title) <= 160 && InboxLimits.length(it.notes.orEmpty()) <= 4000 }
    fun json() = JSONObject().put("id", id).put("title", title).put("notes", notes ?: JSONObject.NULL).put("pinned", pinned)
        .put("items", JSONArray(items.map { JSONObject().put("title", it.title).put("notes", it.notes ?: JSONObject.NULL) }))
    companion object {
        fun read(value: JSONObject): SavedHouseholdList {
            val items = value.getJSONArray("items")
            return SavedHouseholdList(value.getString("id"), value.getString("title"), value.nullableString("notes"),
                (0 until items.length()).map { items.getJSONObject(it).let { item -> HouseholdListItem(item.getString("title"), item.nullableString("notes")) } }, value.optBoolean("pinned"))
        }
        fun fromTask(root: JSONObject, tasks: Map<String, JSONObject>) = SavedHouseholdList(UUID.randomUUID().toString(),
            root.getString("title"), root.nullableString("description"), SharedChecklistActions.childIds(root).mapNotNull(tasks::get)
                .filter { it.isNull("deletion") && it.optString("lifecycle") != "CANCELLED" }
                .map { HouseholdListItem(it.getString("title"), it.nullableString("description")) })
    }
}

internal object SharedLists {
    /** Keep the exact request for ambiguous failures. A new version is never substituted on retry. */
    suspend fun send(repository: SharedRepository, token: String, command: JSONObject? = null, retry: Boolean = false,
        sendRequest: suspend (String, SharedWorkspace, JSONObject) -> JSONObject = { auth, state, action -> AdventureEndpoint("lists").send(auth, state, action) }) {
        val state = repository.prepareAdventureRead() ?: throw SyncFailure("WORKSPACE_UNAVAILABLE")
        val pending = repository.listDraft("pending")
        val action = if (retry) JSONObject(checkNotNull(pending)) else if (command != null) {
            check(pending == null) { "Resolve the previous save first" }
            JSONObject().put("action", "save").put("command", command).also { repository.saveListDraft("pending", it.toString()) }
        } else JSONObject().put("action", "read")
        try {
            val response = sendRequest(token, state, action)
            check(repository.applyListLibrary(state, response))
            if (action.getString("action") == "save") repository.saveListDraft("pending", null)
        } catch (failure: SyncFailure) {
            if (failure.code in listOf("FORBIDDEN", "REGISTRATION_RETIRED", "EPOCH_CHANGED")) repository.blockAdventureRead(state, failure.code)
            if (failure.code in listOf("INVALID_LIST", "LIST_LIMIT", "WORKSPACE_FULL", "STORAGE_FULL", "OPERATION_ID_REUSED", "INVALID_REQUEST")) repository.saveListDraft("pending", null)
            if (failure.code == "LIST_CHANGED") {
                repository.applyListLibrary(state, sendRequest(token, state, JSONObject().put("action", "read")))
                // Retain the preview, but stop offering an obsolete write as a retry.
                repository.saveListDraft("pending", null)
            }
            throw failure
        }
    }
    fun command(library: JSONObject?, id: String, value: SavedHouseholdList?) = JSONObject()
        .put("operationId", UUID.randomUUID().toString()).put("expectedVersion", library?.getString("version") ?: "0")
        .put("id", id).put("value", value?.json() ?: JSONObject.NULL)
}
