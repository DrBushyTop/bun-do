package fi.bundo.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.bundo.R
import fi.bundo.data.VoiceDraft
import fi.bundo.data.VoiceTarget
import fi.bundo.speech.VoiceController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(controller: VoiceController, onDismiss: () -> Unit, onType: () -> Unit,
    onSaved: (String) -> Unit, target: VoiceTarget? = null, autoStart: Boolean = false,
    onSettings: (() -> Unit)? = null, onUseDraft: ((VoiceDraft) -> Unit)? = null,
    useDraftLabel: String? = null) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var started by rememberSaveable { mutableStateOf(!autoStart) }
    var savedElsewhere by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) controller.record(target, review = target?.taskId == null) else controller.permissionDenied()
    }
    val record = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            controller.record(target, review = target?.taskId == null)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !started) {
            started = true
            if (!state.busy && state.reviewId == null) { controller.clearMessage(); record() }
        }
    }
    LaunchedEffect(state.saved) {
        state.saved?.let {
            controller.acknowledgeSaved()
            if (it.target == target) onSaved(it.taskId) else savedElsewhere = true
        }
    }
    LaunchedEffect(state.reviewUse, state.busy, target) {
        val result = state.reviewUse
        if (!state.busy && result != null && result.target == target && onUseDraft != null) {
            onUseDraft(result.draft)
            controller.acknowledgeReviewUse(result.id)
        }
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.interruptRecording()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); controller.interruptRecording() }
    }
    ModalBottomSheet(onDismissRequest = { controller.interruptRecording(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(if (target?.taskId != null) R.string.split_dictate else if (state.reviewId != null) R.string.voice_review else R.string.voice_capture),
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            when (state.phase) {
                "RECORDING" -> {
                    Text(stringResource(R.string.voice_recording, state.seconds), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("recording"))
                    LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(if (state.localOnly || !state.onlineConfigured) R.string.voice_recording_local else R.string.voice_recording_online),
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.voice_recording_hint), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = controller::stop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("stop-recording")) {
                        Text(stringResource(R.string.voice_stop))
                    }
                    TextButton(onClick = { controller.cancel(); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.voice_cancel))
                    }
                }
                "TRANSCRIBING", "ANALYZING" -> {
                    Text(stringResource(if (state.phase == "ANALYZING") R.string.voice_analyzing else R.string.voice_transcribing),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = controller::cancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (state.phase == "ANALYZING") R.string.voice_use_transcript else R.string.voice_cancel))
                    }
                }
                "IDLE" -> {
                    if (state.message.isNotEmpty() && state.message !in setOf("SAVED", "INSTALLED", "EXPORTED")) {
                        Text(stringResource(voiceMessageResource(state.message)), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (savedElsewhere) Text(stringResource(R.string.voice_saved_elsewhere))
                    val draft = state.draft
                    val id = state.reviewId
                    if (draft != null && id != null) {
                        if (onUseDraft != null && state.reviewTarget != target) Text(stringResource(R.string.voice_saved_elsewhere))
                        else VoiceReview(id, draft, controller, allowItems = state.reviewTarget != null, useDraft = onUseDraft != null, useDraftLabel)
                    } else {
                        Button(onClick = record, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("record")) {
                            Text(stringResource(R.string.voice_record))
                        }
                        if (onSettings != null) TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("voice-settings-link")) {
                            Text(stringResource(R.string.voice_settings))
                        }
                    }
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.reviewId == null) TextButton(onClick = { controller.cancel(); onType() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("voice-type")) { Text(stringResource(R.string.type_task)) }
        }
    }
}

@Composable
private fun VoiceReview(id: String, initial: VoiceDraft, controller: VoiceController, allowItems: Boolean,
    useDraft: Boolean = false, useDraftLabel: String? = null) {
    var json by rememberSaveable(id) { mutableStateOf(initial.json()) }
    var original by rememberSaveable(id) { mutableStateOf(false) }
    val draft = VoiceDraft.parse(json)
    fun edit(value: VoiceDraft) {
        json = value.json()
        controller.editReview(id, value)
    }
    OutlinedTextField(draft.title, { edit(draft.copy(title = it)) }, label = { Text(stringResource(R.string.task_title)) },
        modifier = Modifier.fillMaxWidth().testTag("voice-review-title"))
    OutlinedTextField(draft.description, { edit(draft.copy(description = it)) }, label = { Text(stringResource(R.string.description_optional)) },
        modifier = Modifier.fillMaxWidth().testTag("voice-review-description"))
    if (allowItems) {
        OutlinedTextField(draft.items.joinToString("\n"), { edit(draft.copy(items = it.lines())) },
            label = { Text(stringResource(R.string.voice_review_items)) },
            supportingText = { Text(stringResource(R.string.voice_review_items_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("voice-review-items"))
    }
    TextButton(onClick = { original = !original }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_original)) }
    if (original) Text(draft.transcript, style = MaterialTheme.typography.bodyMedium)
    val accepted = draft.copy(title = draft.title.trim(), items = draft.items.map(String::trim).filter(String::isNotEmpty))
    if (!accepted.valid) Text(stringResource(R.string.voice_review_invalid), color = MaterialTheme.colorScheme.error)
    Button(onClick = {
        if (useDraft) controller.useReview(id, accepted)
        else controller.saveReview(id, accepted)
    }, enabled = accepted.valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("voice-review-save")) { Text(useDraftLabel ?: stringResource(R.string.save)) }
    TextButton(onClick = { controller.delete(id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("voice-review-delete")) {
        Text(stringResource(R.string.voice_discard_draft))
    }
}

internal fun voiceMessageResource(code: String): Int = when (code) {
    "PERMISSION" -> R.string.voice_permission
    "MODEL_SPACE" -> R.string.voice_model_space
    "AUDIO_SPACE" -> R.string.voice_audio_space
    "INTERRUPTED" -> R.string.voice_interrupted_short
    "CANCELED" -> R.string.voice_canceled_short
    "SILENCE" -> R.string.voice_silence
    "TOO_LONG" -> R.string.voice_too_long
    "INSTALLED" -> R.string.voice_installed
    "SAVED" -> R.string.voice_saved
    "EXPORTED" -> R.string.voice_exported
    "SIGN_IN_REQUIRED" -> R.string.voice_sign_in_required
    "ONLINE_FAILED" -> R.string.voice_online_failed
    "OFFLINE_MODEL_REQUIRED" -> R.string.voice_setup_required
    "DRAFT_FAILED" -> R.string.voice_draft_failed
    "ANALYSIS_FAILED" -> R.string.voice_analysis_failed
    else -> R.string.voice_failed_short
}
