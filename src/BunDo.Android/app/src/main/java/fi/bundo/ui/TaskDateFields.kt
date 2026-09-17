package fi.bundo.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.SharedTaskDetails
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun chooseDate(context: Context, current: LocalDate, selected: (LocalDate) -> Unit) {
    DatePickerDialog(context, { _, year, month, day -> selected(LocalDate.of(year, month + 1, day)) },
        current.year, current.monthValue - 1, current.dayOfMonth).show()
}
internal fun chooseTime(context: Context, current: LocalTime, selected: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, hour, minute -> selected(LocalTime.of(hour, minute)) },
        current.hour, current.minute, DateFormat.is24HourFormat(context)).show()
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TaskDateFields(details: String, creating: Boolean, enabled: Boolean, onChange: (String) -> Unit) {
    val value = JSONObject(details)
    val due = value.optJSONObject("due")
    val zone = due?.getString("zoneId") ?: value.optString("zoneId", "Europe/Helsinki")
    val today = LocalDate.now(ZoneId.of(zone))
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun change(date: LocalDate?, time: LocalTime? = due?.optString("localTime")?.takeIf { it != "null" && it.isNotBlank() }?.let(LocalTime::parse)) {
        value.put("due", if (date == null) JSONObject.NULL else JSONObject().put("kind", if (time == null) "DATE_ONLY" else "DATE_TIME")
            .put("localDate", date.toString()).put("localTime", time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: JSONObject.NULL).put("zoneId", zone))
        onChange(value.toString())
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.detail_due), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled = enabled, modifier = Modifier.testTag("due-date"), onClick = {
            chooseDate(context, due?.getString("localDate")?.let(LocalDate::parse) ?: today) { change(it) }
        }) {
            Text(due?.getString("localDate")?.let { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
                ?: stringResource(R.string.detail_choose_date))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = due?.optString("localDate") == today.toString(), enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("due-today"), onClick = { change(today) }, label = { Text(stringResource(R.string.detail_today)) })
            FilterChip(selected = due?.optString("localDate") == today.plusDays(1).toString(), enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("due-tomorrow"), onClick = { change(today.plusDays(1)) }, label = { Text(stringResource(R.string.detail_tomorrow)) })
            FilterChip(selected = due?.optString("localDate") == today.plusWeeks(1).toString(), enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("due-next-week"), onClick = { change(today.plusWeeks(1)) }, label = { Text(stringResource(R.string.detail_next_week)) })
        }
        if (due != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = enabled, modifier = Modifier.testTag("due-time"), onClick = {
                    chooseTime(context, due.optString("localTime").takeIf { it != "null" && it.isNotBlank() }?.let(LocalTime::parse) ?: LocalTime.of(9, 0)) {
                        change(LocalDate.parse(due.getString("localDate")), it)
                    }
                }) { Text(if (due.getString("kind") == "DATE_TIME") due.getString("localTime") else stringResource(R.string.detail_add_time)) }
                if (due.getString("kind") == "DATE_TIME") OutlinedButton(enabled = enabled,
                    onClick = { change(LocalDate.parse(due.getString("localDate")), null) }) { Text(stringResource(R.string.detail_date_only)) }
                OutlinedButton(enabled = enabled, modifier = Modifier.testTag("due-clear"), onClick = { change(null) }) { Text(stringResource(R.string.detail_remove_due)) }
            }
            Text(zone, style = MaterialTheme.typography.bodySmall)
            val adjustment = SharedTaskDetails.normalize(due).optString("adjustment")
            if (adjustment == "GAP_FORWARD" || adjustment == "OVERLAP_EARLIER") Text(stringResource(
                if (adjustment == "GAP_FORWARD") R.string.detail_gap else R.string.detail_overlap), style = MaterialTheme.typography.bodySmall)
        }
        SettingToggleRow(stringResource(R.string.detail_urgent), value.optBoolean("urgent"), {
            value.put("urgent", it); onChange(value.toString())
        }, Modifier.testTag("task-urgent"), enabled = enabled)
        Text(stringResource(if (!creating) R.string.detail_keep_position else if (SharedTaskDetails.expedited(value))
            R.string.detail_priority_position else R.string.detail_append_position), modifier = Modifier.testTag("placement-hint"),
            style = MaterialTheme.typography.bodyMedium)
    }
}
