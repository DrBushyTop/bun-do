package fi.bundo.data

import fi.bundo.reminders.ReminderTask
import org.json.JSONObject

/** A local mutation keeps its sequence identity when its receipt assigns server field versions. */
internal class ReminderRevisions(intents: List<SharedIntent>, private val state: SharedWorkspace,
    private val tasks: Map<String, JSONObject>) {
    private val byTarget = intents.filter { it.problem == null && it.status in listOf("PENDING", "SUBMITTED", "ACCEPTED") }
        .groupBy { it.taskId }

    fun stable(task: JSONObject, reminder: ReminderTask): ReminderTask {
        val versions = mutableMapOf("due" to reminder.dueRevision, "snooze" to reminder.snoozeRevision,
            "deletion" to reminder.deletionRevision)
        val relevant = byTarget[reminder.id].orEmpty() + byTarget[reminder.parentId].orEmpty()
        for (intent in relevant.sortedBy { it.sequence.toULong() }) {
            val writes = if (intent.kind == "CreateTask" && intent.taskId == reminder.id) versions.keys
                else SharedChecklistActions.writes(intent, task, tasks).toSet()
            val receipt = intent.receipt?.let { SharedChecklistActions.receiptTask(JSONObject(it), reminder.id) }
            for (group in versions.keys.intersect(writes)) {
                val acceptedVersion = receipt?.let { if (group == "due") it.field(group).toString()
                    else SharedTaskActions.version(it, group) }
                val currentVersion = if (group == "due") task.field(group).toString()
                    else SharedTaskActions.version(task, group)
                if (intent.receipt == null && SharedTaskActions.pending(intent, state.revision) ||
                    acceptedVersion != null && acceptedVersion == currentVersion)
                    versions[group] = "local:${intent.sequence}"
            }
        }
        return reminder.copy(dueRevision = versions.getValue("due"), snoozeRevision = versions.getValue("snooze"),
            deletionRevision = versions.getValue("deletion"))
    }
}
