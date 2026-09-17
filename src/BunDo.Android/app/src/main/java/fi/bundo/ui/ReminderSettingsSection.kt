package fi.bundo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.data.ReminderCoordinator
import fi.bundo.data.ReminderSettings
import fi.bundo.reminders.AndroidReminders
import fi.bundo.reminders.ReminderWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.time.LocalTime

@Composable
internal fun ReminderSettingsSection(data: AccountData) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val saved by remember(data) { data.database.reminders().observeSettings().map { it ?: ReminderSettings() } }.collectAsState(null)
    val settings = saved ?: ReminderSettings()
    var permitted by remember { mutableStateOf(AndroidReminders.permissionGranted(context)) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingOwner by remember { mutableStateOf<AccountData?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val owner = pendingOwner
        pendingOwner = null
        if (owner === data && data.lease.active) {
            permitted = AndroidReminders.permissionGranted(context)
            ReminderWorker.request(context, data)
        }
    }
    DisposableEffect(lifecycle, data) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permitted = AndroidReminders.permissionGranted(context)
                ReminderWorker.request(context, data)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); pendingOwner = null }
    }
    fun save(ask: Boolean = false, update: (ReminderSettings) -> ReminderSettings) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                val shouldAsk = ask && !settings.permissionAsked && !permitted && Build.VERSION.SDK_INT >= 33
                ReminderCoordinator.updateSettings(data) { update(it).copy(permissionAsked = it.permissionAsked || shouldAsk) }
                if (shouldAsk && data.lease.active) {
                    pendingOwner = data
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ReminderWorker.request(context, data)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup()) {
        for ((mode, label) in listOf(0 to R.string.reminders_off, 1 to R.string.reminders_mine, 2 to R.string.reminders_all)) {
            val selected = if (!settings.enabled) mode == 0 else if (settings.allTasks) mode == 2 else mode == 1
            SettingChoiceRow(stringResource(label), selected, onClick = { save(ask = mode != 0) { it.copy(enabled = mode != 0, allTasks = mode == 2) } },
                enabled = !busy && saved != null, modifier = Modifier.testTag("reminders-mode-$mode"))
        }
        }
        if (settings.enabled && !permitted) {
            Text(stringResource(R.string.reminders_denied), modifier = Modifier.testTag("reminders-denied"))
            androidx.compose.material3.OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.reminders_system_settings)) }
        }
        val locale = LocalConfiguration.current.locales[0]
        val time = LocalTime.parse(settings.dateOnlyTime)
        SettingsNavigationRow(stringResource(R.string.reminders_time), {
            chooseTime(context, time) { chosen -> save { it.copy(dateOnlyTime = chosen.format(DateTimeFormatter.ofPattern("HH:mm"))) } }
        }, Modifier.testTag("reminders-time"), value = time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)),
            enabled = !busy && saved != null)
        HelpDisclosure(stringResource(R.string.reminders_details)) {
            Text(stringResource(R.string.reminders_approximate))
            Text(stringResource(R.string.reminders_time_hint))
            Text(stringResource(R.string.reminders_offline))
        }
        if (failed) Text(stringResource(R.string.reminders_failed), color = MaterialTheme.colorScheme.error)
    }
}
