package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject

/** Durable action observations and local projection. No command is rebased on a remote conflict. */
internal object SharedTaskActions {
    const val ORDER_ID = "root-order"
    val cleanupKinds = setOf("RequestCleanup", "RequestSplit", "CancelCleanup", "ApplyCleanup")
    val repeatKinds = setOf("ConfigureRepeat", "StopRepeat")
    val kinds = cleanupKinds + repeatKinds + setOf("ClaimTask", "UnclaimTask", "CompleteTask", "ReopenTask", "CancelTask", "MoveTask", "DeleteTask", "RestoreTask",
        "SplitTask", "AddChildren", "SetSnooze", "ClearSnooze", "SetListPinned")
    val groups = listOf("lifecycle", "claim", "hierarchy", "deletion", "orderIntent", "subtree", "snooze", "urgent", "recurrence")
    fun version(task: JSONObject, group: String): String =
        if (group == "recurrence") task.optJSONObject("repeat")?.decimal("version")?.toString() ?: "0"
        else if (group == "urgent") task.optString("urgencyVersion", "0") else if (task.has("${group}Version")) task.decimal("${group}Version").toString() else "0"
    fun observedGroups(kind: String) = if (kind in repeatKinds) listOf("recurrence", "lifecycle", "hierarchy", "deletion")
        else if (kind in cleanupKinds) listOf("title", "description", "lifecycle", "hierarchy", "deletion") + if (kind == "RequestSplit") emptyList() else listOf("due")
        else if (kind == "MoveTask") listOf("orderIntent", "deletion")
        else listOf("lifecycle", "claim", "hierarchy", "deletion", "subtree", "snooze") +
            if (kind in SharedChecklistActions.splitKinds) listOf("title", "description") else emptyList()
    fun writes(kind: String) = when (kind) {
        "CreateTask" -> groups + listOf("title", "description", "due")
        "SetListPinned" -> listOf("hierarchy", "subtree")
        "ConfigureRepeat", "StopRepeat" -> listOf("recurrence")
        "RequestCleanup", "RequestSplit", "CancelCleanup" -> listOf("cleanup")
        "ApplyCleanup" -> listOf("cleanup", "title", "description", "due")
        "ClaimTask", "UnclaimTask" -> listOf("claim")
        "CompleteTask", "ReopenTask", "CancelTask" -> listOf("lifecycle", "claim", "subtree", "snooze")
        "MoveTask" -> listOf("orderIntent")
        "DeleteTask", "RestoreTask" -> listOf("deletion", "claim", "subtree", "lifecycle")
        "SplitTask", "AddChildren" -> listOf("hierarchy", "subtree", "claim", "lifecycle")
        "SetSnooze", "ClearSnooze" -> listOf("snooze", "claim", "subtree")
        else -> emptyList()
    }
    fun pending(intent: SharedIntent, revision: String) =
        intent.status in listOf("PENDING", "SUBMITTED", "ACCEPTED") &&
            (intent.receipt == null || JSONObject(intent.receipt).decimal("effectRevision") > revision.toULong())

    fun capture(kind: String, task: JSONObject, pending: List<SharedIntent>, payload: JSONObject, actor: String,
        tasks: Map<String, JSONObject> = mapOf(task.getString("id") to task), registration: String = ""): String {
        val versions = JSONObject()
        val after = JSONObject()
        for (group in observedGroups(kind)) {
            versions.put(group, observation(task, group, kind))
            pending.lastOrNull { group in SharedChecklistActions.writes(it, task, tasks) }?.let { after.put(group, it.sequence) }
        }
        if (kind in cleanupKinds) pending.lastOrNull { it.taskId == task.getString("id") && it.kind in cleanupKinds }
            ?.let { after.put("__cleanup", it.sequence) }
        return JSONObject().put("payload", payload).put("versions", versions).put("after", after).put("actor", actor)
            .put("fromLifecycle", task.optString("lifecycle", "OPEN"))
            .put("parentId", task.opt("parentId") ?: JSONObject.NULL).put("registration", registration).toString()
    }
    private fun observation(task: JSONObject, group: String, kind: String) = if (group in listOf("title", "description", "due"))
        if (kind in cleanupKinds) task.field(group).toString() else task.human(group) else version(task, group)

    fun dependencies(intent: SharedIntent): List<String> = intent.taskAction?.let {
        val after = JSONObject(it).getJSONObject("after")
        after.keys().asSequence().map(after::getString).toList()
    }.orEmpty()

    fun freeze(intent: SharedIntent, receipts: Map<String, JSONObject>, payload: JSONObject, observed: JSONObject) {
        val action = JSONObject(checkNotNull(intent.taskAction))
        val values = action.getJSONObject("payload")
        values.keys().forEach { payload.put(it, values.get(it)) }
        val after = action.getJSONObject("after")
        if (intent.kind in cleanupKinds && after.has("__cleanup")) payload.put("requestId",
            SharedChecklistActions.receiptTask(checkNotNull(receipts[after.getString("__cleanup")]), intent.taskId)
                ?.optJSONObject("cleanup")?.opt("id") ?: JSONObject.NULL)
        for (group in observedGroups(intent.kind)) {
            val value = if (after.has(group)) observation(checkNotNull(SharedChecklistActions.receiptTask(
                checkNotNull(receipts[after.getString(group)]), intent.taskId)), group, intent.kind)
                else action.getJSONObject("versions").optString(group, "0")
            observed.put(group, JSONObject().put(if (group in listOf("title", "description", "due") && intent.kind !in cleanupKinds) "humanVersion" else "fieldVersion", value))
            action.optJSONObject("exactText")?.nullableString(group)?.let { observed.getJSONObject(group).put("fieldVersion", it) }
        }
    }

    fun project(task: JSONObject, intent: SharedIntent, intents: List<SharedIntent>, applied: Set<String>,
        tasks: MutableMap<String, JSONObject> = mutableMapOf(task.getString("id") to task)): String? {
        SharedChecklistActions.guard(task, intent, tasks)?.let { return it }
        val kind = intent.kind
        val before = JSONObject(task.toString())
        val action = JSONObject(checkNotNull(intent.taskAction))
        val after = action.getJSONObject("after")
        if (kind in cleanupKinds) {
            val expectedRequest = if (after.has("__cleanup")) {
                val predecessor = intents.find { it.sequence == after.getString("__cleanup") }
                predecessor?.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), intent.taskId) }
                    ?.optJSONObject("cleanup")?.nullableString("id")
                    ?: if (predecessor?.sequence in applied) task.optJSONObject("cleanup")?.nullableString("id") else return "BLOCKED_DEPENDENCY"
            } else action.getJSONObject("payload").nullableString("requestId")
            if (task.optJSONObject("cleanup")?.nullableString("id") != expectedRequest) return "CLEANUP_CONFLICT"
        }
        for (group in observedGroups(intent.kind)) {
            val dependency = if (after.has(group)) intents.find { it.sequence == after.getString(group) } else null
            if (dependency?.status in listOf("REJECTED", "QUARANTINED", "BLOCKED_DEPENDENCY", "DISMISSED"))
                return "BLOCKED_DEPENDENCY"
            val expected = dependency?.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), intent.taskId) }
                ?.let { observation(it, group, intent.kind) } ?: if (after.has(group)) {
                    // An unresolved output refers to the predecessor we just replayed, not its
                    // old numeric observation. Failed predecessors must not authorize a rebase.
                    if (dependency?.sequence !in applied) return "BLOCKED_DEPENDENCY"
                    observation(task, group, kind)
                } else action.getJSONObject("versions").optString(group, "0")
            if (observation(task, group, kind) != expected) return when (group) {
                "claim" -> "CLAIM_CONFLICT"; "orderIntent" -> "ORDER_CONFLICT"
                "deletion" -> "DELETION_CONFLICT"; "subtree" -> "SUBTREE_CONFLICT"; "snooze" -> "SNOOZE_CONFLICT"
                "title", "description", "due" -> "FIELD_CONFLICT"; else -> "LIFECYCLE_CONFLICT"
            }
        }
        if (intent.kind == "RestoreTask") {
            if (task.isNull("deletion")) return "TASK_NOT_DELETED"
            if (task.getJSONObject("deletion").optBoolean("purging")) return "TASK_PURGING"
        } else if (!task.isNull("deletion")) return "TASK_DELETED"
        val receipt = intent.receipt?.let(::JSONObject)?.optJSONObject("task")
        if (receipt != null) {
            // A receipt can reveal concurrent state changes as well as this action's own effect.
            for (group in groups - "recurrence") task.put(if (group == "urgent") "urgencyVersion" else "${group}Version", version(receipt, group))
            for (key in listOf("lifecycle", "claimantId", "lifecycleActorId", "lifecycleAt", "firstCompletion", "deletion", "snoozedUntil", "repeat"))
                if (receipt.has(key)) task.put(key, receipt.get(key))
        } else when (intent.kind) {
            "SetListPinned" -> task.put("listPinned", action.getJSONObject("payload").getBoolean("pinned"))
            "ConfigureRepeat", "StopRepeat" -> task.put("repeatPending", true)
            "RequestCleanup", "RequestSplit" -> task.put("cleanup", JSONObject().put("status", "PENDING")
                .put("id", "pending:${intent.sequence}").put("mode", if (intent.kind == "RequestSplit") "SPLIT" else "CLEANUP"))
            "CancelCleanup" -> task.optJSONObject("cleanup")?.put("status", "SUPERSEDED")
            "ApplyCleanup" -> task.optJSONObject("cleanup")?.optJSONObject("proposal")?.let {
                task.put("title", it.getString("title")).put("description", it.opt("description") ?: JSONObject.NULL)
                it.optJSONObject("due")?.let { due -> task.put("due", due) }
                task.optJSONObject("cleanup")?.put("status", "APPLIED")
            }
            "ClaimTask" -> task.put("claimantId", action.getString("actor"))
            "UnclaimTask" -> task.put("claimantId", JSONObject.NULL)
            "DeleteTask" -> task.put("claimantId", JSONObject.NULL).put("deletion", JSONObject()
                .put("groupId", "pending:${intent.sequence}").put("purging", false)
                .put("deletedAt", JSONObject(intent.captureContext).getString("capturedInstant")))
            "RestoreTask" -> task.put("claimantId", JSONObject.NULL).put("deletion", JSONObject.NULL)
            "CompleteTask", "ReopenTask", "CancelTask" -> task
                .put("lifecycle", when (intent.kind) { "CompleteTask" -> "COMPLETED"; "ReopenTask" -> "OPEN"; else -> "CANCELLED" })
                .put("claimantId", JSONObject.NULL).put("lifecycleActorId", action.getString("actor"))
                .put("lifecycleAt", JSONObject.NULL) // Completion credit and acceptance time belong to the server.
        }
        SharedChecklistActions.apply(before, task, intent, tasks)
        if (intent.receipt == null && intent.kind !in listOf("RequestCleanup", "RequestSplit", "CancelCleanup") && intent.kind !in repeatKinds)
            SharedTaskDetails.markPending(task, intent)
        return null
    }

    fun ordered(rows: List<SharedProjection>, intents: List<SharedIntent>, state: SharedWorkspace?): List<JSONObject> {
        val entities = rows.associate { it.id to JSONObject(it.snapshot) }
        val tasks = entities.filterKeys { it != ORDER_ID }
        val active = tasks.filterValues { it.isNull("parentId") && it.optString("lifecycle", "OPEN") == "OPEN" && it.isNull("deletion") }
        // Updating an intent can change its physical row position. Replay the user's sequence.
        val queued = intents.filter { state != null && it.scope == state.scope && pending(it, state.revision) && it.problem == null }
            .sortedBy { it.sequence.toULong() }
        val inserted = queued.filter { it.kind in listOf("CreateTask", "ReopenTask", "RestoreTask") }.map { it.taskId }.toSet()
        val order = entities[ORDER_ID]?.getJSONArray("taskIds")?.let { array ->
            (0 until array.length()).map(array::getString).filter { it in tasks }.toMutableList()
        } ?: active.keys.sorted().toMutableList()
        active.values.sortedWith(compareBy<JSONObject> { SharedProtocol.inbox(it).createdAt }.thenBy { it.getString("id") })
            .forEach { if (it.getString("id") !in order && it.getString("id") !in inserted) order += it.getString("id") }
        for (intent in queued) {
            if (intent.kind in listOf("CompleteTask", "CancelTask", "DeleteTask")) { order.remove(intent.taskId); continue }
            if (intent.kind in listOf("CreateTask", "RestoreTask") || intent.kind == "ReopenTask" &&
                JSONObject(checkNotNull(intent.taskAction)).optString("fromLifecycle") != "OPEN") {
                order.remove(intent.taskId)
                if (intent.taskId in tasks) {
                    val placement = intent.details?.let(::JSONObject)?.optJSONObject("placement")
                    val after = order.indexOf(placement?.nullableString("afterTaskId"))
                    val before = order.indexOf(placement?.nullableString("beforeTaskId"))
                    order.add(if (after >= 0) after + 1 else if (before >= 0) before else order.size, intent.taskId)
                }
                continue
            }
            if (intent.kind != "MoveTask" || intent.taskId !in order) continue
            val payload = JSONObject(checkNotNull(intent.taskAction)).getJSONObject("payload")
            order.remove(intent.taskId)
            val after = order.indexOf(payload.nullableString("afterTaskId"))
            val before = order.indexOf(payload.nullableString("beforeTaskId"))
            order.add(if (after >= 0) after + 1 else if (before >= 0) before else order.size, intent.taskId)
        }
        return order.filter { it in active }.mapNotNull(tasks::get) + tasks.filterKeys { it !in active }.values
    }

    fun orderEntity(ids: JSONArray, revision: String): JSONObject = JSONObject().put("id", ORDER_ID)
        .put("entityType", "ROOT_ORDER").put("version", revision).put("taskIds", ids)
}
