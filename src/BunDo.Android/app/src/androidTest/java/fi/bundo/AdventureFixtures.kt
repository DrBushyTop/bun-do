package fi.bundo

import fi.bundo.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal fun adventureTask(title: String = "Shopping list", done: Boolean = false) = JSONObject()
    .put("id", UUID.randomUUID().toString()).put("title", title).put("description", JSONObject.NULL)
    .put("titleVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
    .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
    .put("deletionVersion", "1").put("lifecycle", if (done) "COMPLETED" else "OPEN").put("lifecycleVersion", "1")
    .put("claimantId", JSONObject.NULL).put("claimVersion", "1").put("hierarchyVersion", "1")
    .put("orderIntentVersion", "1").put("subtreeVersion", "1").put("firstCompletion", JSONObject.NULL)

internal fun adventureFixture(state: SharedWorkspace, root: JSONObject = adventureTask(), active: Boolean = true, revision: String = "3"): JSONObject {
    val batch = UUID.randomUUID().toString()
    val draft = AdventureDraft("A little kitchen adventure", "Make room for a quiet evening.", listOf(AdventurePhase(root.getString("id"), "Gather the good things", 1, 15)))
    var proposalIndex = 0
    fun proposal(): JSONObject {
        val choice = if (active) draft else draft.copy(phases = draft.phases + listOf(
            AdventurePhase("extra-one", "Make room", 1, 5), AdventurePhase("extra-${++proposalIndex}", "Settle in", 1, 5)))
        return JSONObject().put("id", UUID.randomUUID().toString()).put("draft", choice.json()).put("artwork", "dojo-garden")
    }
    val batchJson = JSONObject().put("id", batch).put("status", if (active) "CONSUMED" else "READY")
        .put("createdAt", Instant.now().toString()).put("expiresAt", Instant.now().plusSeconds(86400).toString())
        .put("proposals", if (active) JSONObject.NULL else JSONArray().put(proposal()).put(proposal()))
    val available = AdventureSnapshot.available(root)
    val done = available && root.optString("lifecycle") == "COMPLETED"
    return JSONObject().put("workspaceId", state.workspaceId).put("stateEpoch", state.epoch).put("revision", revision)
        .put("board", JSONObject().put("batch", batchJson).put("active", if (!active) JSONObject.NULL else proposal().put("batchId", batch)
            .put("version", revision).put("acceptedAt", Instant.now().toString())))
        .put("progress", if (!active) JSONObject.NULL else JSONObject().put("total", 1).put("completed", if (done) 1 else 0).put("isComplete", done)
            .put("roots", JSONArray().put(JSONObject().put("rootId", root.getString("id")).put("available", available)
                .put("task", if (available) root else JSONObject.NULL).put("checklist", JSONArray()))))
}
internal fun adventureWorkspace(): SharedWorkspace {
    val workspace = UUID.randomUUID().toString(); val epoch = UUID.randomUUID().toString(); val registration = UUID.randomUUID().toString()
    return SharedWorkspace("$workspace/$epoch/$registration", workspace, epoch, registration, "Family")
}
