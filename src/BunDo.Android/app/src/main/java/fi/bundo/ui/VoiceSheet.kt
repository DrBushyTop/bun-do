package fi.bundo.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.bundo.R
import fi.bundo.speech.VoiceController
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(controller: VoiceController, onDismiss: () -> Unit, onType: () -> Unit, onSaved: (String) -> Unit, target: fi.bundo.data.VoiceTarget? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var savedElsewhere by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        state.saved?.let {
            controller.acknowledgeSaved()
            // IDs are only unique within a household. Never navigate or offer Undo
            // through this screen's repository for another recording destination.
            if (it.target == target) onSaved(it.taskId) else savedElsewhere = true
        }
    }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var notices by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) controller.record(target) else controller.permissionDenied()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val id = exportId
        exportId = null
        if (id != null && uri != null) controller.export(id, uri)
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.interruptRecording()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            controller.interruptRecording()
        }
    }
    ModalBottomSheet(
        onDismissRequest = { controller.interruptRecording(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(if (target?.taskId == null) R.string.voice_capture else R.string.split_dictate), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(if (state.onlineConfigured) R.string.voice_online_private else R.string.voice_private),
                style = MaterialTheme.typography.bodyMedium)
            if (state.message.isNotEmpty()) {
                Text(stringResource(if (state.message == "SAVED" && savedElsewhere) R.string.voice_saved_elsewhere else messageResource(state.message)),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            when (state.phase) {
                "CHECKING" -> {
                    Text(stringResource(R.string.voice_checking))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                "INSTALLING" -> {
                    Text(stringResource(if (state.progress < 1f) R.string.voice_downloading else R.string.voice_verifying))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = controller::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_cancel)) }
                }
                "RECORDING" -> {
                    Text(stringResource(R.string.voice_recording, state.seconds), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("recording"))
                    Text(stringResource(R.string.voice_level), style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = controller::stop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop-recording")) {
                        Text(stringResource(R.string.voice_stop))
                    }
                    TextButton(onClick = controller::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_cancel)) }
                }
                "TRANSCRIBING" -> {
                    Text(stringResource(if (state.usingOnline) R.string.voice_online_transcribing else R.string.voice_transcribing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = controller::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_cancel)) }
                    if (state.usingOnline && state.modelReady) {
                        TextButton(onClick = controller::useOffline, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.voice_use_offline))
                        }
                    }
                }
                "IDLE" -> {
                    if (state.modelReady || state.onlineConfigured) {
                        Button(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                                controller.record(target)
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("record")) {
                            Text(stringResource(R.string.voice_record))
                        }
                        Text(stringResource(R.string.voice_limit), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!state.modelReady) {
                        Text(stringResource(R.string.voice_install_explanation))
                        TextButton(onClick = controller::install, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("install-model")) {
                            Text(stringResource(R.string.voice_install))
                        }
                    }
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TextButton(
                onClick = { controller.interruptRecording(); onType() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("voice-type"),
            ) { Text(stringResource(R.string.type_task)) }
            if (state.recordings.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.voice_recovery), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.voice_retention), style = MaterialTheme.typography.bodySmall)
                for (recording in state.recordings) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.createdAt)),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.voice_expires, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(recording.expiresAt))))
                    if (recording.reason.isNotEmpty()) Text(stringResource(messageResource(recording.reason)))
                    if (recording.state == "COMMITTED") Text(stringResource(R.string.voice_cleanup_pending))
                    TextButton(
                        onClick = { controller.retry(recording.id) },
                        enabled = !state.busy && (state.modelReady || state.onlineConfigured) && recording.state != "COMMITTED",
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.retry)) }
                    if (state.onlineConfigured && state.modelReady) {
                        TextButton(onClick = { controller.retryOffline(recording.id) },
                            enabled = !state.busy && recording.state != "COMMITTED",
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_use_offline)) }
                    }
                    Text(stringResource(R.string.voice_export_warning), style = MaterialTheme.typography.bodySmall)
                    TextButton(
                        onClick = { exportId = recording.id; export.launch("bun-do-recording.wav") },
                        enabled = !state.busy && recording.state != "COMMITTED", modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.voice_export)) }
                    TextButton(
                        onClick = { controller.delete(recording.id) },
                        enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.voice_delete)) }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { notices = !notices }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.voice_licenses))
            }
            if (notices) {
                Text(stringResource(R.string.voice_attribution), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun messageResource(code: String): Int = when (code) {
    "PERMISSION" -> R.string.voice_permission
    "MODEL_SPACE" -> R.string.voice_model_space
    "AUDIO_SPACE" -> R.string.voice_audio_space
    "INTERRUPTED" -> R.string.voice_interrupted
    "CANCELED" -> R.string.voice_canceled
    "SILENCE" -> R.string.voice_silence
    "TOO_LONG" -> R.string.voice_too_long
    "INSTALLED" -> R.string.voice_installed
    "SAVED" -> R.string.voice_saved
    "EXPORTED" -> R.string.voice_exported
    "SIGN_IN_REQUIRED" -> R.string.voice_sign_in_required
    "ONLINE_FAILED" -> R.string.voice_online_failed
    "OFFLINE_MODEL_REQUIRED" -> R.string.voice_offline_required
    else -> R.string.voice_failed
}
