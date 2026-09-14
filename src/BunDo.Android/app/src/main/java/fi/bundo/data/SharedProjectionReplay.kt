package fi.bundo.data

import org.json.JSONObject

internal object SharedProjectionReplay {
    data class Result(val tasks: Map<String, JSONObject>, val problems: Map<String, String?>)
    fun replay(base: List<JSONObject>, intents: List<SharedIntent>, revision: ULong): Result {
        val purged = base.filter { it.optString("entityType") == "PURGED_TASK" }.map { it.getString("id") }.toSet()
        val projected = base.filter { it.getString("id") !in purged }.associate { it.getString("id") to JSONObject(it.toString()) }.toMutableMap()
        val problems = mutableMapOf<String, String?>()
        val applied = mutableSetOf<String>()
        for (intent in intents) {
            if (intent.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED", "DISMISSED")) continue
            if (intent.receipt != null && JSONObject(intent.receipt).decimal("effectRevision") <= revision) continue
            var task = projected[intent.taskId]
            var problem: String? = null
            if (intent.taskId in purged) problem = "ENTITY_MISSING"
            else if (intent.kind == "CreateTask") {
                if (task == null) { task = SharedProtocol.optimistic(intent); projected[intent.taskId] = task }
            } else if (task == null) problem = "ENTITY_MISSING"
            else if (intent.taskAction != null) problem = SharedTaskActions.project(task, intent, intents, applied, projected)
            else {
                val prerequisite = intent.afterSequence?.let { seq -> intents.find { it.sequence == seq }?.receipt?.let(::JSONObject) }
                fun version(sequence: String?, field: String, observed: String) = sequence?.let { seq ->
                    intents.find { it.sequence == seq }?.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), intent.taskId) }?.human(field)
                } ?: observed
                val titleVersion = version(intent.titleAfterSequence, "title", intent.observedTitle)
                val descriptionVersion = version(intent.descriptionAfterSequence, "description", intent.observedDescription)
                val deletionDependency = intent.deletionAfterSequence?.let { seq -> intents.find { it.sequence == seq } }
                val deletionVersion = deletionDependency?.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), intent.taskId) }
                    ?.decimal("deletionVersion")?.toString() ?: if (deletionDependency?.sequence in applied)
                    task.decimal("deletionVersion").toString() else intent.observedDeletion
                if (prerequisite != null && prerequisite.getString("code") != "ACCEPTED") problem = "BLOCKED_DEPENDENCY"
                else if (deletionDependency != null && deletionDependency.receipt == null && deletionDependency.sequence !in applied)
                    problem = "BLOCKED_DEPENDENCY"
                else if (!task.isNull("deletion")) problem = "TASK_DELETED"
                else if (task.nullableString("parentId")?.let { projected[it]?.isNull("deletion") != true } == true) problem = "PARENT_UNAVAILABLE"
                else if (task.decimal("deletionVersion").toString() != deletionVersion) problem = "DELETION_CONFLICT"
                else if (intent.titleChanged && task.human("title").toULong() > titleVersion.toULong() ||
                    intent.descriptionChanged && task.human("description").toULong() > descriptionVersion.toULong()) problem = "FIELD_CONFLICT"
                else if (SharedTaskDetails.problem(task, intent, intents, applied) != null)
                    problem = SharedTaskDetails.problem(task, intent, intents, applied)
                else {
                    SharedTaskDetails.projectEdit(task, intent)
                    if (intent.titleChanged) task.put("title", intent.title)
                    if (intent.descriptionChanged) task.put("description", intent.description ?: JSONObject.NULL)
                }
            }
            if (problem == null) {
                if (intent.taskAction == null && intent.receipt != null)
                    SharedChecklistActions.applyReceipt(JSONObject(intent.receipt), projected)
                applied += intent.sequence; projected[intent.taskId]?.let { SharedChecklistActions.derive(it, projected) } }
            problems[intent.sequence] = problem
        }
        return Result(projected, problems)
    }
}
