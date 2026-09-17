package fi.bundo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.nullableString
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
internal fun SharedRepeatControls(task: JSONObject, membership: JSONObject?, enabled: Boolean,
    onSave: (String, String?) -> Unit) {
    if (!task.isNull("parentId") || !task.isNull("deletion")) return
    if (task.optBoolean("repeatPending")) {
        Text(stringResource(R.string.repeat_pending))
        return
    }
    val repeat = task.optJSONObject("repeat")
    val open = task.optString("lifecycle", "OPEN") == "OPEN"
    var basis by remember(task.getString("id")) { mutableStateOf<String?>(null) }
    val active = repeat?.optBoolean("active") == true
    val rule = repeat?.optJSONObject("rule")
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (repeat != null) {
            Text(stringResource(if (active) R.string.repeat_active else R.string.repeat_stopped),
                style = MaterialTheme.typography.titleMedium)
            if (active) Text(if (rule?.optString("frequency") == "WEEKLY")
                stringResource(R.string.repeat_on_weekday, DayOfWeek.of(rule.getInt("weekday")).getDisplayName(TextStyle.FULL, locale))
                else stringResource(R.string.repeat_daily))
        }
        if (open || active) OutlinedButton(enabled = enabled, modifier = Modifier.testTag("repeat-edit"),
            onClick = { basis = if (basis == null) task.toString() else null }) {
            Text(stringResource(if (repeat == null || !active) R.string.repeat_start else R.string.repeat_edit))
        }
        basis?.let { displayed ->
            val original = remember(displayed) { JSONObject(displayed) }
            val savedRepeat = original.optJSONObject("repeat")
            val savedRule = savedRepeat?.optJSONObject("rule")
            var frequency by remember(displayed) { mutableStateOf(savedRule?.optString("frequency") ?: "DAILY") }
            var weekday by remember(displayed) { mutableIntStateOf(savedRule?.optInt("weekday", 1)?.coerceIn(1, 7) ?: 1) }
            var title by remember(displayed) { mutableStateOf(savedRepeat?.optString("title") ?: original.getString("title")) }
            var description by remember(displayed) { mutableStateOf(savedRepeat?.nullableString("description") ?: original.nullableString("description").orEmpty()) }
            val zone = savedRule?.getString("zoneId") ?: membership?.optString("timeZoneId") ?: "Europe/Helsinki"
            Text(stringResource(R.string.repeat_explanation, zone))
            FilterChip(selected = frequency == "DAILY", onClick = { frequency = "DAILY" }, enabled = enabled,
                label = { Text(stringResource(R.string.repeat_daily)) })
            FilterChip(selected = frequency == "WEEKLY", onClick = { frequency = "WEEKLY" }, enabled = enabled,
                modifier = Modifier.testTag("repeat-weekly"), label = { Text(stringResource(R.string.repeat_weekly)) })
            if (frequency == "WEEKLY") for (day in 1..7) FilterChip(selected = weekday == day,
                enabled = enabled, onClick = { weekday = day }, modifier = Modifier.testTag("repeat-day-$day"),
                label = { Text(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale)) })
            OutlinedTextField(value = title, onValueChange = { title = it }, enabled = enabled,
                label = { Text(stringResource(R.string.repeat_title)) }, modifier = Modifier.fillMaxWidth().testTag("repeat-title"))
            OutlinedTextField(value = description, onValueChange = { description = it }, enabled = enabled,
                label = { Text(stringResource(R.string.repeat_description)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.repeat_edit_explanation))
            Button(enabled = enabled && fi.bundo.data.InboxLimits.valid(title, description),
                modifier = Modifier.testTag("repeat-save"), onClick = {
                    onSave(displayed, JSONObject().put("frequency", frequency).put("weekday", if (frequency == "WEEKLY") weekday else JSONObject.NULL)
                        .put("zoneId", zone).put("title", title).put("description", description.ifBlank { null } ?: JSONObject.NULL).toString())
                }) { Text(stringResource(R.string.repeat_save)) }
            if (active) OutlinedButton(enabled = enabled, modifier = Modifier.testTag("repeat-stop"),
                onClick = { onSave(displayed, null) }) { Text(stringResource(R.string.repeat_stop)) }
        }
    }
}
