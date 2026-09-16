package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.nullableString
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SharedProgressScreen(progress: String?, activity: Boolean, tasks: Map<String, JSONObject>, membership: JSONObject?,
    onRefresh: () -> Unit, onOpen: (String) -> Unit, onJourney: (() -> Unit)? = null) {
    val snapshot = progress?.let(::JSONObject)
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (activity) R.string.progress_activity else R.string.progress_together),
            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        TextButton(onClick = onRefresh, modifier = Modifier.testTag("progress-refresh")) { Text(stringResource(R.string.household_refresh)) }
        if (!activity && onJourney != null) OutlinedButton(onClick = onJourney,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("journey-open")) { Text(stringResource(R.string.journey_open)) }
        if (snapshot == null) {
            Text(stringResource(R.string.progress_unavailable))
            return@Column
        }
        val stats = snapshot.getJSONObject("statistics")
        val zone = ZoneId.of(stats.getString("zoneId"))
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
        Text(stringResource(R.string.progress_as_of, dateTime.format(Instant.parse(snapshot.getString("asOf")))),
            style = MaterialTheme.typography.bodySmall)
        if (activity) {
            val events = snapshot.getJSONArray("activity")
            if (events.length() == 0) Text(stringResource(R.string.progress_activity_empty))
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                val actor = event.nullableString("actorId")?.let { memberName(membership, it) } ?: stringResource(R.string.app_name)
                val title = tasks[event.getString("taskId")]?.optString("title")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(activityLabel(event.getString("action")), actor))
                    if (title != null) TextButton(onClick = { onOpen(event.getString("taskId")) },
                        contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.testTag("activity-task-${event.getString("taskId")}")) { Text(title) }
                    else Text(stringResource(R.string.progress_task_unavailable), style = MaterialTheme.typography.bodyMedium)
                    Text(dateTime.format(Instant.parse(event.getString("acceptedAt"))), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        } else {
            var month by rememberSaveable { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = !month, onClick = { month = false }, modifier = Modifier.testTag("progress-week"),
                    label = { Text(stringResource(R.string.progress_week)) })
                FilterChip(selected = month, onClick = { month = true }, modifier = Modifier.testTag("progress-month"),
                    label = { Text(stringResource(R.string.progress_month)) })
            }
            Text(pluralStringResource(R.plurals.progress_completed, stats.getInt(if (month) "monthCount" else "weekCount"), stats.getInt(if (month) "monthCount" else "weekCount")),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("progress-period-count"))
            val buckets = stats.getJSONArray(if (month) "monthWeeks" else "weekDays")
            val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
            fun date(value: String) = format.format(LocalDate.parse(value))
            Text(stringResource(R.string.progress_period, date(buckets.getJSONObject(0).getString("start")),
                date(buckets.getJSONObject(buckets.length() - 1).getString("end"))))
            val maximum = (0 until buckets.length()).maxOf { buckets.getJSONObject(it).getInt("count") }.coerceAtLeast(1)
            for (index in 0 until buckets.length()) {
                val bucket = buckets.getJSONObject(index)
                val label = if (month) stringResource(R.string.progress_period, date(bucket.getString("start")), date(bucket.getString("end")))
                    else LocalDate.parse(bucket.getString("start")).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, locale) +
                        " " + date(bucket.getString("start"))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.progress_bucket, label, bucket.getInt("count")), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { bucket.getInt("count").toFloat() / maximum },
                        gapSize = 0.dp, drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
                }
            }
            HorizontalDivider()
            Text(pluralStringResource(R.plurals.progress_streak, stats.getInt("streak"), stats.getInt("streak")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("progress-streak").semantics { heading() })
            Text(stringResource(R.string.progress_streak_explanation))
            Text(pluralStringResource(R.plurals.progress_lifetime, stats.getInt("lifetimeCount"), stats.getInt("lifetimeCount")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("progress-lifetime").semantics { heading() })
            if (stats.getInt("reachedMilestone") > 0) Text(stringResource(R.string.progress_milestone, stats.getInt("reachedMilestone")))
            if (!stats.isNull("nextMilestone")) Text(stringResource(R.string.progress_next_milestone, stats.getInt("nextMilestone")))
            Text(stringResource(R.string.progress_counting_explanation, zone.id), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun activityLabel(action: String) = when (action) {
    "CreateTask" -> R.string.activity_created
    "CompleteTask" -> R.string.activity_completed
    "CancelTask" -> R.string.activity_cancelled
    "ReopenTask" -> R.string.activity_reopened
    "DeleteTask" -> R.string.activity_deleted
    "RestoreTask" -> R.string.activity_restored
    "ClaimTask" -> R.string.activity_claimed
    "UnclaimTask" -> R.string.activity_unclaimed
    "MoveTask" -> R.string.activity_moved
    "ConfigureRepeat" -> R.string.activity_repeat_saved
    "StopRepeat" -> R.string.activity_repeat_stopped
    "GenerateRepeat" -> R.string.activity_repeat_created
    "RequestCleanup", "RequestSplit" -> R.string.activity_ai_requested
    "CancelCleanup" -> R.string.activity_ai_cancelled
    "ApplyCleanup" -> R.string.activity_ai_applied
    "SplitTask", "AddChildren" -> R.string.activity_checklist
    "SetSnooze" -> R.string.activity_snoozed
    "ClearSnooze" -> R.string.activity_unsnoozed
    else -> R.string.activity_edited
}
