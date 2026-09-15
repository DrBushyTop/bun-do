package fi.bundo.data

import androidx.room.withTransaction
import fi.bundo.reminders.ReminderCandidate
import fi.bundo.reminders.ReminderPolicy
import fi.bundo.reminders.ReminderTask
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime

internal interface ReminderSink {
    fun permitted(): Boolean
    fun cancel()
    fun visible(): Boolean
    fun post(candidates: List<ReminderCandidate>, window: Long, silent: Boolean): Boolean
    fun schedule(at: Instant?)
}

/** The lease and Room transaction keep rechecking, posting and ledger updates ordered with local edits. */
internal class ReminderCoordinator(private val data: AccountData, private val sink: ReminderSink) {
    suspend fun reconcile(now: Instant = Instant.now()) = data.lease.access {
        val dao = data.database.reminders()
        val settings = dao.settings() ?: ReminderSettings()
        if (data.identity == null || !settings.enabled || !sink.permitted()) {
            data.lease.whileActive { sink.cancel(); sink.schedule(null) }
            return@access
        }
        data.database.withTransaction {
            val candidates = candidates(data, settings)
            val eligible = candidates.filter { it.at <= now && it.at >= now.minusSeconds(86_400) }
            val delivered = dao.deliveries().map { it.key }.toSet()
            val visibleKeys = delivered.filter { it.startsWith("visible:") }.map { it.removePrefix("visible:") }.toSet()
            val missed = ReminderPolicy.missed(candidates, delivered, now)
            val window = now.epochSecond / 900
            val usedWindow = "window:$window" in delivered
            var posted = false
            var batch = missed
            val consumed = data.lease.whileActive {
                // Newly eligible tasks do not invalidate a displayed summary. Removed ones do.
                val invalidated = !eligible.map { it.key }.toSet().containsAll(visibleKeys)
                if (invalidated) sink.cancel()
                val visible = !invalidated && sink.visible()
                if (usedWindow && visible) batch = eligible.filter { it.key in visibleKeys || it in missed }
                // Dismissal or invalidation must not produce another summary in the same window.
                val consumed = missed.isNotEmpty() && if (usedWindow && !visible) true
                    else sink.post(batch, window, silent = usedWindow).also { posted = it }
                sink.schedule(if (sink.permitted()) ReminderPolicy.next(candidates, now) else null)
                consumed
            }
            // Posting and this commit cannot be atomic. A crash can replace the same onlyAlertOnce ID.
            if (consumed) dao.delivered((missed.map { it.key } + "window:$window").map { ReminderDelivery(it, now.toEpochMilli()) })
            if (posted || !sink.visible()) {
                dao.clearVisible()
                if (posted) dao.delivered(batch.map { ReminderDelivery("visible:${it.key}", now.toEpochMilli()) })
            }
            data.lease.check()
        }
    }

    companion object {
        suspend fun updateSettings(data: AccountData, update: (ReminderSettings) -> ReminderSettings) = data.lease.access {
            data.database.withTransaction {
                val previous = data.database.reminders().settings() ?: ReminderSettings()
                val next = update(previous)
                LocalTime.parse(next.dateOnlyTime)
                require(next.dateOnlyTime.matches(Regex("[0-9]{2}:[0-9]{2}")))
                val changed = previous.enabled != next.enabled || previous.allTasks != next.allTasks ||
                    previous.dateOnlyTime != next.dateOnlyTime
                data.database.reminders().saveSettings(next.copy(revision = previous.revision + if (changed) 1 else 0))
                data.lease.check()
            }
        }

        internal suspend fun candidates(data: AccountData, settings: ReminderSettings): List<ReminderCandidate> =
            data.database.shared().workspaces(data.registrationId.orEmpty()).flatMap { state ->
                if (state.blocked != null) return@flatMap emptyList()
                val membership = state.membership?.let(::JSONObject) ?: return@flatMap emptyList()
                val me = membership.getString("me")
                val members = membership.getJSONArray("members")
                val active = (0 until members.length()).map(members::getJSONObject)
                    .filter { it.optBoolean("active") }.map { it.getString("id") }.toSet()
                if (me !in active) return@flatMap emptyList()
                val projected = data.database.shared().projectionRows(state.scope, state.projectionGeneration)
                    .map { JSONObject(it.snapshot) }.filter { it.has("title") }.associateBy { it.getString("id") }
                val revisions = ReminderRevisions(data.database.shared().intents(state.scope), state, projected)
                val tasks = projected.values.map { task ->
                    revisions.stable(task, fromProjection(state.scope, task, settings.dateOnlyTime, active))
                }
                ReminderPolicy.candidates(tasks, me, settings.allTasks, settings.revision)
            }.sortedWith(compareBy<ReminderCandidate> { it.at }.thenBy { it.task.scope }.thenBy { it.task.id })

        fun fromProjection(scope: String, task: JSONObject, time: String, activeMembers: Set<String>): ReminderTask {
            val due = task.optJSONObject("due")
            val instant = due?.let {
                if (it.getString("kind") == "DATE_ONLY")
                    ReminderPolicy.dateOnly(it.getString("localDate"), time,
                        it.optString("dateOnlyReminderZoneId", it.getString("zoneId")))
                else Instant.parse(it.getString("instant"))
            }
            return ReminderTask(scope, task.getString("id"), task.getString("title"), instant,
                task.optJSONObject("dueVersion")?.optString("fieldVersion", "0") ?: "0",
                task.nullableString("snoozedUntil")?.let(Instant::parse),
                task.optString("snoozeVersion", "0"), task.optString("deletionVersion", "0"),
                task.optString("lifecycle", "OPEN") == "OPEN", !task.isNull("deletion"),
                task.nullableString("claimantId")?.takeIf { it in activeMembers }, task.nullableString("parentId"))
        }
    }
}
