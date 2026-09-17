package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.SharedTaskDetails
import fi.bundo.data.nullableString
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun dueLabel(due: JSONObject): String {
    val locale = LocalConfiguration.current.locales[0]
    val date = LocalDate.parse(due.getString("localDate")).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    return date + if (due.getString("kind") == "DATE_TIME") " " + due.getString("localTime") + " · " + due.getString("zoneId") else " · " + due.getString("zoneId")
}

@Composable
internal fun SharedDueSummary(task: JSONObject) {
    if (task.optBoolean("urgent")) Text(stringResource(R.string.detail_urgent), style = MaterialTheme.typography.labelLarge)
    task.optJSONObject("due")?.let { due ->
        Text(dueLabel(due), style = MaterialTheme.typography.bodyMedium)
        when (due.optString("adjustment")) {
            "GAP_FORWARD" -> Text(stringResource(R.string.detail_gap), style = MaterialTheme.typography.bodySmall)
            "OVERLAP_EARLIER" -> Text(stringResource(R.string.detail_overlap), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SharedTaskAttribution(task: JSONObject, membership: JSONObject?) {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.of(membership?.optString("timeZoneId", "Europe/Helsinki") ?: "Europe/Helsinki")
    fun time(value: String) = runCatching { Instant.parse(value).atZone(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) + " · " + zone.id }.getOrDefault(value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        task.optJSONObject("creation")?.let { creation ->
            val captured = creation.nullableString("capturedAt")?.let(::time) ?: stringResource(R.string.detail_unknown_capture)
            val actor = creation.nullableString("actorId")
            Text(if (actor == null) stringResource(R.string.detail_anonymous_created, captured)
                else stringResource(R.string.detail_created, memberName(membership, actor), captured),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task-creation"))
        }
        task.optJSONObject("lastChange")?.let { change ->
            val at = time(change.getString("at"))
            Text(when (change.getString("source")) {
                "SYSTEM" -> stringResource(R.string.detail_system_changed, at)
                "AI" -> stringResource(R.string.detail_ai_changed, at)
                "LOCAL" -> stringResource(R.string.detail_pending_changed, memberName(membership, change.nullableString("actorId")), at)
                else -> stringResource(R.string.detail_changed, memberName(membership, change.nullableString("actorId")), at)
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("task-last-change"))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SharedSnoozePresets(task: JSONObject, membership: JSONObject?, enabled: Boolean, onAction: (SharedTaskAction) -> Unit) {
    val context = LocalContext.current
    val zone = membership?.optString("timeZoneId", "Europe/Helsinki") ?: "Europe/Helsinki"
    val today = LocalDate.now(ZoneId.of(zone))
    var invalidTime by remember { mutableStateOf(false) }
    fun snooze(date: LocalDate, time: LocalTime) {
        val due = SharedTaskDetails.normalize(JSONObject().put("kind", "DATE_TIME").put("localDate", date.toString())
            .put("localTime", time.format(DateTimeFormatter.ofPattern("HH:mm"))).put("zoneId", zone))
        invalidTime = !Instant.parse(due.getString("instant")).isAfter(Instant.now())
        if (!invalidTime) onAction(SharedTaskAction("SetSnooze", task.toString(), until = due.getString("instant")))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, modifier = Modifier.testTag("snooze-tomorrow"), onClick = { snooze(today.plusDays(1), LocalTime.of(9, 0)) }) {
            Text(stringResource(R.string.detail_snooze_tomorrow))
        }
        OutlinedButton(enabled = enabled, modifier = Modifier.testTag("snooze-week"), onClick = { snooze(today.plusWeeks(1), LocalTime.of(9, 0)) }) {
            Text(stringResource(R.string.detail_snooze_week))
        }
        OutlinedButton(enabled = enabled, modifier = Modifier.testTag("snooze-custom"), onClick = {
            chooseDate(context, today.plusDays(1)) { date -> chooseTime(context, LocalTime.of(9, 0)) { time -> snooze(date, time) } }
        }) { Text(stringResource(R.string.detail_snooze_choose)) }
    }
    if (invalidTime) Text(stringResource(R.string.detail_snooze_future), color = MaterialTheme.colorScheme.error)
    task.nullableString("snoozedUntil")?.let { until ->
        val locale = LocalConfiguration.current.locales[0]
        val text = Instant.parse(until).atZone(ZoneId.of(zone))
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)) + " · " + zone
        Text(stringResource(R.string.detail_snooze_until, text), style = MaterialTheme.typography.bodySmall)
    }
}
