package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectableGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.bundo.R
import fi.bundo.speech.VoiceController
import java.text.DateFormat
import java.util.Date

@Composable
fun VoiceSettings(controller: VoiceController, onReview: () -> Unit, modifier: Modifier = Modifier, initialHistory: Boolean = false) {
    val state by controller.state.collectAsStateWithLifecycle()
    var history by rememberSaveable { mutableStateOf(initialHistory) }
    var setup by rememberSaveable { mutableStateOf(false) }
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportId; exportId = null
        if (id != null && uri != null) controller.export(id, uri)
    }
    LaunchedEffect(state.reviewId) { if (state.reviewId != null) onReview() }
    val pageScroll = key(history, setup) { rememberScrollState() }
    BackHandler(enabled = history || setup) { history = false; setup = false }
    if (choosingMode) AlertDialog(onDismissRequest = { choosingMode = false }, title = { Text(stringResource(R.string.voice_model)) },
        text = { Column(Modifier.selectableGroup()) {
            listOf(false to R.string.voice_model_auto, true to R.string.voice_model_local).forEach { (local, label) ->
                SettingChoiceRow(stringResource(label), state.localOnly == local, {
                    controller.configure(localOnly = local); choosingMode = false
                }, Modifier.testTag(if (local) "voice-model-local" else "voice-model-auto"), enabled = !state.busy)
            }
        } }, confirmButton = { TextButton(onClick = { choosingMode = false }) { Text(stringResource(R.string.back)) } })
    Column(modifier.verticalScroll(pageScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (history || setup) TextButton(onClick = { history = false; setup = false }) { Text(stringResource(R.string.back)) }
        if (!history && !setup) {
            Text(stringResource(R.string.voice_settings_heading), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            SettingsNavigationRow(stringResource(R.string.voice_model), { choosingMode = true }, Modifier.testTag("voice-mode"),
                value = stringResource(if (state.localOnly) R.string.voice_model_local else R.string.voice_model_auto), enabled = !state.busy)
            Text(stringResource(if (state.localOnly) R.string.voice_model_local_hint else R.string.voice_model_auto_hint), style = MaterialTheme.typography.bodyMedium)
            SettingToggleRow(stringResource(R.string.voice_analysis_setting), state.analyze, { controller.configure(analyze = it) },
                Modifier.testTag("voice-analyze"), enabled = !state.busy, hint = stringResource(R.string.voice_analysis_hint))
            SettingToggleRow(stringResource(R.string.voice_keep_audio), state.keepAudio, { controller.configure(keepAudio = it) },
                Modifier.testTag("voice-keep-audio"), enabled = !state.busy, hint = stringResource(R.string.voice_audio_short))
            HorizontalDivider()
            SettingsNavigationRow(stringResource(R.string.voice_offline_setup), { setup = true }, Modifier.testTag("voice-model-setup"),
                value = stringResource(when { state.phase == "INSTALLING" -> R.string.voice_downloading
                    state.modelReady -> R.string.voice_model_installed; else -> R.string.voice_not_downloaded }))
            SettingsNavigationRow(stringResource(R.string.voice_history, state.recordings.size), { history = true }, Modifier.testTag("voice-history"))
            HelpDisclosure(stringResource(R.string.voice_privacy_details)) { Text(stringResource(R.string.voice_keep_audio_hint)) }
        }
        if (setup) {
            Text(stringResource(R.string.voice_offline_setup), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.voice_install_explanation), style = MaterialTheme.typography.bodyMedium)
            if (state.phase == "INSTALLING") {
                Text(stringResource(if (state.progress < 1f) R.string.voice_downloading else R.string.voice_verifying))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = controller::cancel) { Text(stringResource(R.string.voice_cancel)) }
            } else if (!state.modelReady) Button(onClick = controller::install, enabled = !state.busy,
                modifier = Modifier.testTag("install-model")) { Text(stringResource(R.string.voice_install)) }
            else Text(stringResource(R.string.voice_model_installed))
            HelpDisclosure(stringResource(R.string.voice_licenses)) { Text(stringResource(R.string.voice_attribution), style = MaterialTheme.typography.bodySmall) }
        }
        if (history) {
            Text(stringResource(R.string.voice_history, state.recordings.size), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("voice-history-list").semantics { heading() })
            if (state.recordings.isEmpty()) Text(stringResource(R.string.voice_history_empty))
            for (recording in state.recordings) {
                val preview = recording.state == "REVIEW"
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.createdAt)),
                    style = MaterialTheme.typography.titleSmall)
                if (preview) {
                    OutlinedButton(onClick = { controller.openReview(recording.id) }, enabled = !state.busy,
                        modifier = Modifier.testTag("voice-resume-${recording.id}")) { Text(stringResource(R.string.voice_resume_draft)) }
                } else if (recording.state !in setOf("COMMITTED", "USED")) {
                    if (recording.reason.isNotEmpty()) Text(stringResource(voiceMessageResource(recording.reason)))
                    OutlinedButton(onClick = { controller.retry(recording.id) }, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
                }
                if (recording.keepAudio && recording.expiresAt > System.currentTimeMillis()) {
                    Text(stringResource(R.string.voice_expires, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.expiresAt))),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.voice_export_warning), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { exportId = recording.id; export.launch("bun-do-recording.wav") }, enabled = !state.busy) {
                        Text(stringResource(R.string.voice_export))
                    }
                }
                TextButton(onClick = { controller.delete(recording.id) }, enabled = !state.busy) {
                    Text(stringResource(if (preview) R.string.voice_discard_draft else R.string.voice_delete))
                }
                HorizontalDivider()
            }
        }
        if (state.message.isNotEmpty()) Text(stringResource(voiceMessageResource(state.message)), style = MaterialTheme.typography.bodyMedium)
    }
}
