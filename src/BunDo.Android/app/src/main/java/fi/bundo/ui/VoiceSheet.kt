package fi.bundo.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val density = androidx.compose.ui.platform.LocalDensity.current
    var started by rememberSaveable { mutableStateOf(!autoStart) }
    var savedElsewhere by rememberSaveable { mutableStateOf(false) }
    var revisionRecording by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            if (revisionRecording) state.reviewId?.let(controller::recordRevision)
            else controller.record(target, review = target?.taskId == null)
        } else controller.permissionDenied()
    }
    val record = {
        revisionRecording = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            controller.record(target, review = target?.taskId == null)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val reviseByVoice: () -> Unit = {
        revisionRecording = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            state.reviewId?.let(controller::recordRevision)
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
    Dialog(onDismissRequest = { controller.interruptRecording(); controller.closeReview(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val review = state.draft
        val reviewId = state.reviewId
        if (state.phase == "IDLE" && review != null && reviewId != null && (onUseDraft == null || state.reviewTarget == target)) {
            VoiceReview(reviewId, review, controller, allowItems = state.reviewTarget != null,
                useDraft = onUseDraft != null, useDraftLabel = useDraftLabel, onClose = { controller.closeReview(); onDismiss() }, onRecord = reviseByVoice)
        } else Column(Modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }
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
                    TextButton(onClick = { controller.cancel(); if (!state.recordingRevision) onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.voice_cancel))
                    }
                }
                "TRANSCRIBING", "ANALYZING", "REVISING" -> {
                    Text(stringResource(when (state.phase) { "REVISING" -> R.string.voice_revising; "ANALYZING" -> R.string.voice_analyzing; else -> R.string.voice_transcribing }),
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
                        else Text(stringResource(R.string.voice_review))
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
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceReview(id: String, initial: VoiceDraft, controller: VoiceController, allowItems: Boolean,
    useDraft: Boolean = false, useDraftLabel: String? = null, onClose: () -> Unit, onRecord: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var panel by rememberSaveable(id) { mutableStateOf("") }
    var instruction by rememberSaveable(id) { mutableStateOf(initial.revisionInstruction) }
    val scroll = rememberScrollState()
    val proposal = state.revision
    val draft = initial
    LaunchedEffect(id, proposal, draft.items) { scroll.scrollTo(0) }
    val accepted = draft.normalized()
    BoxWithConstraints(Modifier.widthIn(max = 840.dp).fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val short = maxHeight < 480.dp
        val sideScene = short && maxWidth >= 600.dp
        Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.back)) }
                    Text(stringResource(if (proposal == null) R.string.voice_review else R.string.voice_revision_preview),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (proposal == null) IconButton(onClick = { panel = "edit" }, modifier = Modifier.testTag("voice-review-edit")) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.edit_task))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.task_actions_menu)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.voice_discard_draft)) }, onClick = { menu = false; panel = "discard" })
                            DropdownMenuItem(text = { Text(stringResource(R.string.voice_original)) }, onClick = { menu = false; panel = "original" })
                        }
                    }
                }
            val content: @Composable () -> Unit = {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.message.isNotEmpty()) Text(stringResource(voiceMessageResource(state.message)), color = MaterialTheme.colorScheme.error)
                if (proposal == null) {
                    Text(draft.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (draft.description.isNotEmpty()) Text(draft.description)
                    if (accepted.items.isNotEmpty()) {
                        Text(stringResource(R.string.checklist_title), style = MaterialTheme.typography.titleMedium)
                        accepted.items.forEachIndexed { index, item -> Text("${index + 1}. $item", Modifier.testTag("voice-draft-step-$index")) }
                    }
                } else {
                    val after = proposal.after
                    if (draft.title != after.title) RevisionChange(stringResource(R.string.task_title), draft.title, after.title)
                    if (draft.description != after.description) RevisionChange(stringResource(R.string.description_optional), draft.description, after.description)
                    if (draft.items != after.items) RevisionChange(stringResource(R.string.checklist_title),
                        draft.items.joinToString("\n"), after.items.joinToString("\n"))
                    if (draft.title == after.title && draft.description == after.description && draft.items == after.items)
                        Text(stringResource(R.string.voice_revision_unchanged))
                }
            }            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("voice-review-content"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (sideScene) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { content() }
                    Box(Modifier.width(220.dp).padding(top = 16.dp, end = 16.dp)) { TaskArtwork(draft.title, height = 160.dp) }
                } else {
                    TaskArtwork(draft.title, height = if (short) 96.dp else 220.dp)
                    content()
                }
            }
        HorizontalDivider()
        FlowRow(Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(16.dp),
            maxItemsInEachRow = if (short) 2 else 1, horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (proposal != null) {
                Button(onClick = controller::acceptRevision, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("voice-revision-accept")) { Text(stringResource(R.string.voice_revision_accept)) }
                OutlinedButton(onClick = controller::rejectRevision, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("voice-revision-reject")) { Text(stringResource(R.string.voice_revision_keep)) }
            } else {
                if (allowItems && state.revisionConfigured) OutlinedButton(onClick = { instruction = draft.revisionInstruction; panel = "revise" },
                    enabled = accepted.valid, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("voice-review-revise")) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.microphone), null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.voice_revision_action))
                }
                if (!accepted.valid) Text(stringResource(R.string.voice_review_invalid), color = MaterialTheme.colorScheme.error)
                Button(onClick = { if (useDraft) controller.useReview(id, accepted) else controller.saveReview(id, accepted) }, enabled = accepted.valid,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("voice-review-save")) { Text(useDraftLabel ?: stringResource(R.string.voice_add_task)) }
            }
        }
        }
    }
    if (panel.isNotEmpty()) AlertDialog(onDismissRequest = { panel = "" },
        title = { Text(stringResource(when (panel) { "edit" -> R.string.edit_task; "original" -> R.string.voice_original; "discard" -> R.string.voice_discard_draft; else -> R.string.voice_revision_question })) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (panel) {
                    "original" -> Text(draft.transcript)
                    "discard" -> Text(stringResource(R.string.voice_discard_confirm))
                    "edit" -> {
                        OutlinedTextField(draft.title, { controller.editReview(id, draft.copy(title = it)) }, label = { Text(stringResource(R.string.task_title)) }, modifier = Modifier.testTag("voice-review-title"))
                        OutlinedTextField(draft.description, { controller.editReview(id, draft.copy(description = it)) }, label = { Text(stringResource(R.string.description_optional)) }, modifier = Modifier.testTag("voice-review-description"))
                        if (allowItems) OutlinedTextField(draft.items.joinToString("\n"), { controller.editReview(id, draft.copy(items = it.lines())) },
                            label = { Text(stringResource(R.string.voice_review_items)) }, modifier = Modifier.testTag("voice-review-items"))
                    }
                    else -> {
                        Text(stringResource(R.string.voice_revision_hint))
                        OutlinedTextField(instruction, { if (fi.bundo.data.InboxLimits.length(it) <= fi.bundo.data.InboxLimits.DESCRIPTION) { instruction = it; controller.editReview(id, draft.copy(revisionInstruction = it)) } },
                            label = { Text(stringResource(R.string.voice_revision_instruction)) }, modifier = Modifier.testTag("voice-revision-instruction"))
                        OutlinedButton(onClick = { panel = ""; onRecord() }, modifier = Modifier.fillMaxWidth().testTag("voice-revision-record")) {
                            Text(stringResource(R.string.voice_record))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            when (panel) { "discard" -> controller.delete(id); "revise" -> controller.reviseReview(id, instruction) }
            panel = ""
        }, enabled = panel != "revise" || instruction.isNotBlank() && fi.bundo.data.InboxLimits.length(instruction) <= fi.bundo.data.InboxLimits.DESCRIPTION,
            modifier = Modifier.testTag("voice-panel-confirm")) { Text(stringResource(if (panel == "revise") R.string.voice_revision_preview else if (panel == "discard") R.string.voice_discard_draft else R.string.back)) } },
        dismissButton = { if (panel == "discard" || panel == "revise") TextButton(onClick = { panel = "" }) { Text(stringResource(R.string.voice_cancel)) } })
}

@Composable
private fun RevisionChange(label: String, before: String, after: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (before.isNotEmpty()) {
        Text(stringResource(R.string.voice_revision_before), style = MaterialTheme.typography.labelLarge)
        Text(before, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
    }
    Text(stringResource(R.string.voice_revision_after), style = MaterialTheme.typography.labelLarge)
    Text(after.ifEmpty { stringResource(R.string.voice_revision_removed) }, color = MaterialTheme.colorScheme.primary)

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
    "REVISION_FAILED" -> R.string.voice_revision_failed
    "REVISION_OFFLINE" -> R.string.voice_revision_offline
    "ANALYSIS_FAILED" -> R.string.voice_analysis_failed
    else -> R.string.voice_failed_short
}
