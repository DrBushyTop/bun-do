package fi.bundo.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Bounded direct-item effects shared by ordinary replay and snapshot recovery. */
internal object SharedChecklistActions {
    const val MAX_ITEMS = 16
    val splitKinds = setOf("SplitTask", "AddChildren")
    fun childIds(task: JSONObject): List<String> = task.optJSONArray("childOrder")?.let { a ->
        (0 until a.length()).map(a::getString)
    }.orEmpty()
    fun childIds(intent: SharedIntent, registration: String): List<String> = if (intent.kind in splitKinds)
        (1..JSONObject(checkNotNull(intent.taskAction)).getJSONObject("payload").getJSONArray("items").length())
            .map { SharedProtocol.taskId(registration, intent.sequence, it) } else emptyList()
    fun receiptTask(receipt: JSONObject, id: String): JSONObject? {
        receipt.optJSONObject("task")?.takeIf { it.optString("id") == id }?.let { return it }
        val related = receipt.optJSONArray("relatedTasks") ?: return null
        return (0 until related.length()).map(related::getJSONObject).find { it.getString("id") == id }
    }
    fun writes(intent: SharedIntent, target: JSONObject, tasks: Map<String, JSONObject>): List<String> {
        val id = target.getString("id")
        if (intent.taskId == id) return SharedTaskActions.writes(intent.kind) + SharedTaskDetails.writes(intent) +
            listOfNotNull("title".takeIf { intent.titleChanged }, "description".takeIf { intent.descriptionChanged },
                "subtree".takeIf { target.optBoolean("isChecklist") && intent.kind in listOf("EditTask", "MoveTask") })
        if (tasks[intent.taskId]?.nullableString("parentId") == id) return listOf("subtree", "lifecycle")
        if (target.nullableString("parentId") != intent.taskId) return emptyList()
        return when (intent.kind) {
            "SplitTask", "AddChildren" -> SharedTaskActions.groups + listOf("title", "description", "due")
            "DeleteTask", "RestoreTask" -> listOf("deletion", "claim")
            "CancelTask", "ReopenTask" -> listOf("lifecycle", "claim", "snooze")
            "SetSnooze" -> listOf("claim")
            else -> emptyList()
        }
    }
    fun snoozed(task: JSONObject, tasks: Map<String, JSONObject>, now: Instant = Instant.now()): Boolean =
        listOfNotNull(task, task.nullableString("parentId")?.let(tasks::get)).any { value ->
            value.nullableString("snoozedUntil")?.let { runCatching { Instant.parse(it) > now }.getOrDefault(false) } == true
        }
    fun guard(task: JSONObject, intent: SharedIntent, tasks: Map<String, JSONObject>): String? {
        val parent = task.nullableString("parentId")
        if (parent != null && (tasks[parent] == null || !tasks.getValue(parent).isNull("deletion"))) return "PARENT_UNAVAILABLE"
        if (intent.kind in listOf("ClaimTask", "CompleteTask") && snoozed(task, tasks)) return "TASK_SNOOZED"
        if (task.optBoolean("isChecklist") && intent.kind in listOf("ClaimTask", "UnclaimTask", "CompleteTask")) return "CHECKLIST_ROOT"
        if (intent.kind in splitKinds) {
            val source = intent.taskAction?.let(::JSONObject)?.optJSONObject("sourceText")
            if (source != null && (task.getString("title") != source.getString("title") || task.nullableString("description") != source.nullableString("description"))) return "FIELD_CONFLICT"
            val exact = intent.taskAction?.let(::JSONObject)?.optJSONObject("exactText")
            if (exact != null && listOf("title", "description").any { task.field(it).toString() != exact.getString(it) }) return "FIELD_CONFLICT"
            if (parent != null) return "CHECKLIST_DEPTH"
            if (task.optString("lifecycle", "OPEN") != "OPEN" && !(intent.kind == "AddChildren" && task.optBoolean("emptyChecklist"))) return "TASK_NOT_OPEN"
            if ((intent.kind == "SplitTask") == task.optBoolean("isChecklist")) return "HIERARCHY_CONFLICT"
        }
        return null
    }
    fun receiptTasks(receipt: JSONObject): List<JSONObject> {
        val related = receipt.optJSONArray("relatedTasks")
        return (listOfNotNull(receipt.optJSONObject("task")) +
            (0 until (related?.length() ?: 0)).map { related!!.getJSONObject(it) }).distinctBy { it.getString("id") }
    }
    fun validateReceipt(receipt: JSONObject) {
        val tasks = receiptTasks(receipt)
        require(tasks.size <= MAX_ITEMS + 1)
        tasks.forEach { SharedProtocol.validateTask(it, receipt.decimal("effectRevision")) }
    }
    fun applyReceipt(receipt: JSONObject, tasks: MutableMap<String, JSONObject>) {
        for (task in receiptTasks(receipt)) tasks[task.getString("id")] = JSONObject(task.toString())
    }
    fun apply(before: JSONObject, task: JSONObject, intent: SharedIntent, tasks: MutableMap<String, JSONObject>) {
        if (intent.receipt != null) {
            applyReceipt(JSONObject(intent.receipt), tasks)
            return
        }
        val action = JSONObject(checkNotNull(intent.taskAction))
        val payload = action.getJSONObject("payload")
        val children = childIds(before).mapNotNull(tasks::get)
        val group = "pending:${intent.sequence}"
        if (intent.kind in splitKinds) {
            val items = payload.getJSONArray("items")
            val ids = childIds(intent, action.getString("registration"))
            for (index in ids.indices) {
                val child = SharedProtocol.optimistic(intent.copy(taskId = ids[index], title = items.getString(index), description = null))
                    .put("parentId", task.getString("id"))
                tasks[ids[index]] = child
            }
            if (task.optJSONObject("cleanup")?.optString("mode") == "SPLIT") task.getJSONObject("cleanup")
                .put("status", "APPLIED").put("proposal", JSONObject.NULL).put("splitSource", JSONObject.NULL)
            task.put("isChecklist", true).put("childOrder", JSONArray(childIds(before) + ids)).put("claimantId", JSONObject.NULL)
        }
        if (intent.kind == "SetSnooze" || intent.kind == "ClearSnooze") {
            task.put("snoozedUntil", if (intent.kind == "SetSnooze") payload.getString("until") else JSONObject.NULL)
                .put("claimantId", JSONObject.NULL)
        }
        if (!before.optBoolean("isChecklist") && intent.kind in listOf("CancelTask", "ReopenTask", "CompleteTask"))
            task.put("cancellationGroupId", JSONObject.NULL)
        if (intent.kind == "CancelTask") task.put("snoozedUntil", JSONObject.NULL)
        if (before.optBoolean("isChecklist")) {
            for (child in children) when (intent.kind) {
                "DeleteTask" -> if (child.isNull("deletion")) child.put("deletion", JSONObject(task.getJSONObject("deletion").toString())).put("claimantId", JSONObject.NULL)
                "RestoreTask" -> if (child.optJSONObject("deletion")?.optString("groupId") == before.optJSONObject("deletion")?.optString("groupId"))
                    child.put("deletion", JSONObject.NULL).put("claimantId", JSONObject.NULL)
                "SetSnooze" -> if (child.isNull("deletion")) child.put("claimantId", JSONObject.NULL)
                "CancelTask" -> if (child.isNull("deletion") && child.optString("lifecycle", "OPEN") != "CANCELLED")
                    child.put("lifecycle", "CANCELLED").put("cancellationGroupId", group).put("claimantId", JSONObject.NULL).put("snoozedUntil", JSONObject.NULL)
                "ReopenTask" -> if (child.isNull("deletion") && !before.isNull("cancellationGroupId") &&
                    child.nullableString("cancellationGroupId") == before.nullableString("cancellationGroupId"))
                    child.put("lifecycle", "OPEN").put("cancellationGroupId", JSONObject.NULL).put("claimantId", JSONObject.NULL)
            }
            if (intent.kind == "CancelTask" && before.nullableString("cancellationGroupId") == null) task.put("cancellationGroupId", group)
            if (intent.kind == "ReopenTask") task.put("cancellationGroupId", JSONObject.NULL)
        }
        if (intent.kind == "MoveTask" && task.nullableString("parentId") != null) {
            val root = tasks.getValue(task.getString("parentId"))
            val order = childIds(root).toMutableList()
            order.remove(task.getString("id"))
            val after = order.indexOf(payload.nullableString("afterTaskId"))
            val beforeIndex = order.indexOf(payload.nullableString("beforeTaskId"))
            order.add(if (after >= 0) after + 1 else if (beforeIndex >= 0) beforeIndex else order.size, task.getString("id"))
            root.put("childOrder", JSONArray(order))
        }
        derive(task, tasks)
    }
    fun derive(task: JSONObject, tasks: MutableMap<String, JSONObject>) {
        val root = task.nullableString("parentId")?.let(tasks::get) ?: task.takeIf { it.optBoolean("isChecklist") } ?: return
        if (!root.isNull("deletion")) return
        val eligible = childIds(root).mapNotNull(tasks::get).filter { it.isNull("deletion") && it.optString("lifecycle", "OPEN") != "CANCELLED" }
        val lifecycle = if (eligible.isEmpty()) "CANCELLED" else if (eligible.all { it.optString("lifecycle") == "COMPLETED" }) "COMPLETED" else "OPEN"
        if (root.optString("lifecycle", "OPEN") != lifecycle) root.put("lifecycleAt", JSONObject.NULL)
        root.put("lifecycle", lifecycle).put("emptyChecklist", eligible.isEmpty() && root.isNull("cancellationGroupId"))
    }
}
