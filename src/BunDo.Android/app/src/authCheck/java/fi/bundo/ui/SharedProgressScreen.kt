package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SharedProgressScreen(progress: String?, activity: Boolean, tasks: Map<String, JSONObject>, membership: JSONObject?,
    onRefresh: () -> Unit, onOpen: (String) -> Unit, onJourney: (() -> Unit)? = null, onAdventure: (() -> Unit)? = null, onQueue: (() -> Unit)? = null, refreshing: Boolean = false, failed: Boolean = false, allowed: Boolean = true) {
    if (activity) {
        SharedActivityScreen(progress, tasks, membership, onRefresh, onOpen, refreshing, failed, allowed)
        return
    }
    val snapshot = progress?.let(::JSONObject)
    val locale = LocalConfiguration.current.locales[0]
    val refreshLabel = stringResource(R.string.refresh_progress)
    RefreshPage(refreshLabel, refreshing, allowed, onRefresh, Modifier.testTag("progress-page")) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RefreshHeading(stringResource(R.string.progress_together), refreshLabel, allowed && !refreshing, onRefresh)
        if (failed) Text(stringResource(R.string.journey_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("progress-error"))
        if (snapshot == null) {
            Text(stringResource(R.string.progress_unavailable))
            if (onAdventure != null) SettingsNavigationRow(stringResource(R.string.adventure_open), onAdventure,
                Modifier.testTag("adventure-open"), icon = ImageVector.vectorResource(R.drawable.adventure_scroll))
            return@Column
        }
        val stats = snapshot.getJSONObject("statistics")
        val zone = ZoneId.of(stats.getString("zoneId"))
        val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
        run {
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
            if (stats.getInt(if (month) "monthCount" else "weekCount") == 0) {
                Text(stringResource(R.string.progress_empty_period))
                if (onQueue != null) Button(onClick = onQueue, modifier = Modifier.testTag("progress-go-tasks")) {
                    Text(stringResource(R.string.inbox))
                }
            }
            else Row(Modifier.fillMaxWidth().testTag("progress-chart"), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom) {
                for (index in 0 until buckets.length()) {
                    val bucket = buckets.getJSONObject(index)
                    val count = bucket.getInt("count")
                    val start = LocalDate.parse(bucket.getString("start"))
                    val shortLabel = if (month) stringResource(R.string.progress_week_number, start.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                        else start.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale)
                    val spokenLabel = if (month) stringResource(R.string.progress_period, date(bucket.getString("start")), date(bucket.getString("end")))
                        else start.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale) + " " + date(bucket.getString("start"))
                    val description = stringResource(R.string.progress_bucket, spokenLabel, count)
                    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.height(144.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            if (count > 0) Box(Modifier.fillMaxWidth(.7f).fillMaxHeight(count.toFloat() / maximum)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                        }
                        Text(shortLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            HorizontalDivider()
            Text(pluralStringResource(R.plurals.progress_streak, stats.getInt("streak"), stats.getInt("streak")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("progress-streak").semantics { heading() })

            Text(pluralStringResource(R.plurals.progress_lifetime, stats.getInt("lifetimeCount"), stats.getInt("lifetimeCount")), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("progress-lifetime").semantics { heading() })
            if (stats.getInt("reachedMilestone") > 0) Text(stringResource(R.string.progress_milestone, stats.getInt("reachedMilestone")))
            if (!stats.isNull("nextMilestone")) Text(stringResource(R.string.progress_next_milestone, stats.getInt("nextMilestone")))
            if (!stats.isNull("nextMilestone")) LinearProgressIndicator(
                progress = { stats.getInt("lifetimeCount").toFloat() / stats.getInt("nextMilestone") },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {}, gapSize = 0.dp, drawStopIndicator = {})
            if (onJourney != null) SettingsNavigationRow(stringResource(R.string.journey_open), onJourney,
                Modifier.testTag("journey-open"), icon = ImageVector.vectorResource(R.drawable.bun_do))
            if (onAdventure != null) SettingsNavigationRow(stringResource(R.string.adventure_open), onAdventure,
                Modifier.testTag("adventure-open"), icon = ImageVector.vectorResource(R.drawable.adventure_scroll))
            HelpDisclosure(stringResource(R.string.progress_how), Modifier.testTag("progress-details")) {
                Text(stringResource(R.string.progress_as_of, dateTime.format(Instant.parse(snapshot.getString("asOf")))))
                Text(stringResource(R.string.progress_streak_explanation))
                Text(stringResource(R.string.progress_counting_explanation, zone.id), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    }
}

internal fun activityLabel(action: String) = when (action) {
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
