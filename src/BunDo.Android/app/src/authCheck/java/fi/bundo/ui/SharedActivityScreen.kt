package fi.bundo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.nullableString
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SharedActivityScreen(progress: String?, tasks: Map<String, JSONObject>, membership: JSONObject?,
    onRefresh: () -> Unit, onOpen: (String) -> Unit) {
    val snapshot = remember(progress) { progress?.let(::JSONObject) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = snapshot?.getJSONObject("statistics")?.getString("zoneId")?.let(ZoneId::of)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    val fullDate = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
    val events = snapshot?.getJSONArray("activity")
    LazyColumn(Modifier.fillMaxSize().testTag("activity-feed"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.progress_activity), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("progress-refresh")) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.household_refresh))
                }
            }
        }
        if (snapshot == null) {
            item { Text(stringResource(R.string.progress_unavailable), Modifier.padding(vertical = 12.dp)) }
        } else {
            item {
                Text(stringResource(R.string.progress_as_of, fullDate.format(Instant.parse(snapshot.getString("asOf")))),
                    Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (events!!.length() == 0) item {
                Text(stringResource(R.string.progress_activity_empty), Modifier.padding(vertical = 12.dp))
            }
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                val acceptedAt = Instant.parse(event.getString("acceptedAt"))
                val day = acceptedAt.atZone(zone).toLocalDate()
                val previousDay = if (index == 0) null else
                    Instant.parse(events.getJSONObject(index - 1).getString("acceptedAt")).atZone(zone).toLocalDate()
                if (day != previousDay) item {
                    Text(date.format(acceptedAt), Modifier.padding(top = 12.dp, bottom = 4.dp)
                        .testTag("activity-date-$day").semantics { heading() },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "event-${event.getString("revision")}-$index") {
                    val taskId = event.getString("taskId")
                    val actor = event.nullableString("actorId")?.let { memberName(membership, it) }
                        ?: stringResource(R.string.app_name)
                    ActivityEventRow(event.getString("action"), actor, tasks[taskId]?.optString("title"),
                        time.format(acceptedAt), fullDate.format(acceptedAt), taskId, { onOpen(taskId) })
                }
            }
        }
    }
}

@Composable
private fun ActivityEventRow(action: String, actor: String, taskTitle: String?, time: String,
    acceptedAt: String, taskId: String, onOpen: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = taskTitle ?: stringResource(R.string.progress_task_unavailable)
    val description = "${stringResource(activityLabel(action), actor)}. $title. $acceptedAt"
    val detailsLabel = stringResource(if (expanded) R.string.activity_hide_details else R.string.activity_show_details)
    val large = LocalConfiguration.current.fontScale >= 1.5f
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("activity-row-$taskId"),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)
                .testTag("activity-details-$taskId")) {
                Icon(activityIcon(action), contentDescription = "$detailsLabel. $description",
                    modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (taskTitle != null) Modifier.testTag("activity-task-$taskId")
                    .clickable(onClick = onOpen, role = Role.Button) else Modifier)
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium,
                        color = if (taskTitle != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (large) 2 else 1, overflow = TextOverflow.Ellipsis)
                    if (large) Text(actor, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!large) Text(actor, Modifier.widthIn(max = 64.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) Text(description, Modifier.padding(start = 56.dp, bottom = 12.dp)
            .testTag("activity-expanded-$taskId"), style = MaterialTheme.typography.bodySmall)
    }
}

private fun activityIcon(action: String) = when (action) {
    "CompleteTask" -> Icons.Outlined.CheckCircle
    "CreateTask", "GenerateRepeat" -> Icons.Outlined.Add
    "CancelTask", "CancelCleanup", "StopRepeat" -> Icons.Outlined.Close
    "DeleteTask" -> Icons.Outlined.Delete
    "ReopenTask", "RestoreTask", "ClearSnooze", "ConfigureRepeat" -> Icons.Outlined.Refresh
    "ClaimTask", "UnclaimTask" -> Icons.Outlined.Person
    "SplitTask", "AddChildren" -> Icons.AutoMirrored.Outlined.List
    "SetSnooze" -> Icons.Outlined.DateRange
    "MoveTask" -> Icons.Outlined.KeyboardArrowUp
    else -> Icons.Outlined.Edit
}
