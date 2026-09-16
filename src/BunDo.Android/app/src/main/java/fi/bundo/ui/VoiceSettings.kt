package fi.bundo.ui

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
    var notices by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportId; exportId = null
        if (id != null && uri != null) controller.export(id, uri)
    }
    LaunchedEffect(state.reviewId) { if (state.reviewId != null) onReview() }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.voice_model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to R.string.voice_model_auto, true to R.string.voice_model_local).forEachIndexed { index, (local, label) ->
                SegmentedButton(selected = state.localOnly == local, onClick = { controller.configure(localOnly = local) },
                    enabled = !state.busy, shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.testTag(if (local) "voice-model-local" else "voice-model-auto")) { Text(stringResource(label)) }
            }
        }
        Text(stringResource(if (state.localOnly) R.string.voice_model_local_hint else R.string.voice_model_auto_hint), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { setup = !setup }, modifier = Modifier.heightIn(min = 48.dp).testTag("voice-model-setup")) {
            Text(stringResource(if (state.modelReady) R.string.voice_model_installed else R.string.voice_model_setup))
        }
        if (setup || state.phase == "INSTALLING") {
            Text(stringResource(R.string.voice_install_explanation), style = MaterialTheme.typography.bodyMedium)
            if (state.phase == "INSTALLING") {
                Text(stringResource(if (state.progress < 1f) R.string.voice_downloading else R.string.voice_verifying))
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = controller::cancel) { Text(stringResource(R.string.voice_cancel)) }
            } else if (!state.modelReady) Button(onClick = controller::install, enabled = !state.busy,
                modifier = Modifier.testTag("install-model")) { Text(stringResource(R.string.voice_install)) }
            TextButton(onClick = { notices = !notices }) { Text(stringResource(R.string.voice_licenses)) }
            if (notices) Text(stringResource(R.string.voice_attribution), style = MaterialTheme.typography.bodySmall)
        }
        VoiceSettingSwitch(stringResource(R.string.voice_analysis_setting), state.analyze, !state.busy, "voice-analyze") { controller.configure(analyze = it) }
        Text(stringResource(R.string.voice_analysis_hint), style = MaterialTheme.typography.bodyMedium)
        VoiceSettingSwitch(stringResource(R.string.voice_keep_audio), state.keepAudio, !state.busy, "voice-keep-audio") { controller.configure(keepAudio = it) }
        Text(stringResource(R.string.voice_keep_audio_hint), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        TextButton(onClick = { history = !history }, modifier = Modifier.heightIn(min = 48.dp).testTag("voice-history")) {
            Text(stringResource(R.string.voice_history, state.recordings.size))
        }
        if (history) {
            if (state.recordings.isEmpty()) Text(stringResource(R.string.voice_history_empty))
            for (recording in state.recordings) {
                val preview = recording.state == "REVIEW"
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.createdAt)),
                    style = MaterialTheme.typography.titleSmall)
                if (preview) {
                    TextButton(onClick = { controller.openReview(recording.id) }, enabled = !state.busy,
                        modifier = Modifier.testTag("voice-resume-${recording.id}")) { Text(stringResource(R.string.voice_resume_draft)) }
                } else if (recording.state != "COMMITTED") {
                    if (recording.reason.isNotEmpty()) Text(stringResource(voiceMessageResource(recording.reason)))
                    TextButton(onClick = { controller.retry(recording.id) }, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
                }
                if (recording.keepAudio && recording.expiresAt > System.currentTimeMillis()) {
                    Text(stringResource(R.string.voice_expires, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.expiresAt))),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.voice_export_warning), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { exportId = recording.id; export.launch("bun-do-recording.wav") }, enabled = !state.busy) {
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

@Composable
private fun VoiceSettingSwitch(label: String, value: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.testTag(tag)
            .semantics { contentDescription = label })
    }
}
