package fi.bundo.reminders

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** No Android clock, database or notification service is needed to decide eligibility. */
data class ReminderTask(
    val scope: String,
    val id: String,
    val title: String,
    val dueAt: Instant?,
    val dueRevision: String,
    val snoozeAt: Instant? = null,
    val snoozeRevision: String = "0",
    val deletionRevision: String = "0",
    val open: Boolean = true,
    val deleted: Boolean = false,
    val claimant: String? = null,
    val parentId: String? = null,
)

data class ReminderCandidate(val key: String, val task: ReminderTask, val at: Instant)

object ReminderPolicy {
    fun dateOnly(date: String, time: String, zone: String): Instant =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of(zone))
            .withEarlierOffsetAtOverlap().toInstant()

    fun candidates(tasks: List<ReminderTask>, me: String, allTasks: Boolean, revision: Long): List<ReminderCandidate> {
        val byId = tasks.associateBy { it.id }
        return tasks.mapNotNull { task ->
            if (!task.open || task.deleted || (!allTasks && task.claimant != null && task.claimant != me))
                return@mapNotNull null
            val parent = task.parentId?.let(byId::get)
            if (task.parentId != null && (parent == null || !parent.open || parent.deleted)) return@mapNotNull null
            val due = task.dueAt ?: task.snoozeAt ?: return@mapNotNull null
            val at = listOfNotNull(due, task.snoozeAt, parent?.snoozeAt).max()
            // Include values as well as versions because offline edits have not received server versions.
            val identity = listOf(task.scope, task.id, task.dueRevision, task.dueAt,
                task.snoozeRevision, task.snoozeAt, task.deletionRevision,
                parent?.id, parent?.snoozeRevision, parent?.snoozeAt, parent?.deletionRevision, revision)
            val key = MessageDigest.getInstance("SHA-256").digest(identity.joinToString("\n").toByteArray())
                .joinToString("") { "%02x".format(it) }
            ReminderCandidate(key, task, at)
        }.sortedWith(compareBy<ReminderCandidate> { it.at }.thenBy { it.task.scope }.thenBy { it.task.id })
    }

    fun missed(candidates: List<ReminderCandidate>, delivered: Set<String>, now: Instant) =
        candidates.filter { it.at <= now && it.at >= now.minusSeconds(24 * 60 * 60) && it.key !in delivered }

    fun next(candidates: List<ReminderCandidate>, now: Instant): Instant? =
        candidates.firstOrNull { it.at > now }?.at
}
