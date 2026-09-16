package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import fi.bundo.R
import fi.bundo.data.*
import org.json.JSONObject

@Composable
internal fun GuidedAdventureScreen(creation: GuidedCreation?, remote: JSONObject?, busy: Boolean, failed: Boolean,
    tasks: Map<String, JSONObject>, canStart: Boolean, onPlan: (String, Int?) -> Unit, onApprove: (GuidedDraft) -> Unit,
    onResume: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var outcome by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    var draft by remember(creation?.id) { mutableStateOf(creation?.draft) }
    var confirmCancel by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).imePadding().padding(24.dp)
        .testTag("adventure-creator"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.guided_back)) }
        Text(stringResource(R.string.guided_title), style = MaterialTheme.typography.headlineSmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.guided_error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("guided-error"))
        if (creation?.stage == "ENDED") {
            Text(stringResource(R.string.guided_ended), modifier = Modifier.testTag("guided-ended"))
            Button(onClick = onDiscard) { Text(stringResource(R.string.guided_acknowledge)) }
        } else if (creation?.stage in listOf("APPROVED", "QUEUED") || remote != null) {
            AdventureArtwork("dojo-garden")
            Text(stringResource(R.string.guided_pending))
            if (creation?.stage in listOf("APPROVED", "QUEUED")) Button(onClick = onResume, enabled = !busy,
                modifier = Modifier.testTag("guided-resume")) { Text(stringResource(R.string.guided_resume)) }
            else Text(stringResource(R.string.guided_other_device))
            if (remote != null) TextButton(onClick = { confirmCancel = true }, enabled = !busy) { Text(stringResource(R.string.guided_cancel)) }
        } else if (draft != null) {
            Text(stringResource(R.string.guided_review_hint))
            OutlinedTextField(draft!!.title, { draft = draft!!.copy(title = it) }, label = { Text(stringResource(R.string.guided_name)) },
                modifier = Modifier.fillMaxWidth().testTag("guided-name"))
            draft!!.phases.forEachIndexed { index, phase ->
                fun update(p: GuidedPhase) { draft = draft!!.copy(phases = draft!!.phases.toMutableList().apply { set(index, p) }) }
                Text(stringResource(if (phase.rootId == null) R.string.guided_new_task else R.string.guided_existing_task), style = MaterialTheme.typography.titleMedium)
                if (phase.rootId == null) OutlinedTextField(phase.taskTitle.orEmpty(), { update(phase.copy(taskTitle = it)) },
                    label = { Text(stringResource(R.string.guided_task)) }, modifier = Modifier.fillMaxWidth().testTag("guided-task-$index"))
                else Text(tasks[phase.rootId]?.optString("title") ?: stringResource(R.string.adventure_source_unavailable))
                OutlinedTextField(phase.name, { update(phase.copy(name = it)) }, label = { Text(stringResource(R.string.adventure_phase_name)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.guided_difficulty), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { stars ->
                    val label = stringResource(R.string.guided_stars, stars)
                    FilterChip(phase.stars == stars, { update(phase.copy(stars = stars)) }, label = { Text(stars.toString()) }, modifier = Modifier.semantics { contentDescription = label }) } }
                OutlinedTextField(phase.minutes.takeIf { it > 0 }?.toString().orEmpty(), { update(phase.copy(minutes = it.toIntOrNull() ?: 0)) },
                    label = { Text(stringResource(R.string.adventure_minutes)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = { draft = draft!!.copy(phases = draft!!.phases.filterIndexed { i, _ -> i != index }) },
                    modifier = Modifier.testTag("guided-remove-$index")) { Text(stringResource(R.string.guided_remove)) }
                HorizontalDivider()
            }
            Text(stringResource(R.string.adventure_estimate_hint), style = MaterialTheme.typography.bodySmall)
            val valid = runCatching { GuidedDraft.read(draft!!.json()) }.isSuccess
            if (!valid) Text(stringResource(R.string.guided_invalid), color = MaterialTheme.colorScheme.error)
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); onApprove(draft!!) }, enabled = !busy && canStart && valid,
                modifier = Modifier.testTag("guided-approve")) { Text(stringResource(R.string.guided_approve)) }
            TextButton(onClick = onDiscard, enabled = !busy) { Text(stringResource(R.string.guided_discard)) }
        } else {
            AdventureArtwork("dojo-garden")
            val invalidOutcome = outcome.isNotEmpty() && (outcome.isBlank() || outcome.length > 1000 || outcome.any(Char::isISOControl))
            val invalidTime = minutes.isNotEmpty() && minutes.toIntOrNull() !in 1..1440
            OutlinedTextField(outcome, { outcome = it }, label = { Text(stringResource(R.string.guided_outcome)) },
                modifier = Modifier.fillMaxWidth().testTag("guided-outcome"), isError = invalidOutcome,
                supportingText = { if (invalidOutcome) Text(stringResource(R.string.guided_outcome_invalid), Modifier.testTag("guided-outcome-error")) })
            val prompt1 = stringResource(R.string.guided_prompt_balcony)
            val prompt2 = stringResource(R.string.guided_prompt_corner)
            TextButton(onClick = { outcome = prompt1 }) { Text(prompt1) }
            TextButton(onClick = { outcome = prompt2 }) { Text(prompt2) }
            OutlinedTextField(minutes, { minutes = it }, label = { Text(stringResource(R.string.guided_time)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("guided-time"), isError = invalidTime,
                supportingText = { if (invalidTime) Text(stringResource(R.string.guided_time_invalid), Modifier.testTag("guided-time-error")) })
            Text(stringResource(R.string.guided_preview_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); onPlan(outcome.trim(), minutes.toIntOrNull()) },
                enabled = !busy && canStart && outcome.isNotBlank() && outcome.length <= 1000 && outcome.none(Char::isISOControl) &&
                    (minutes.isEmpty() || minutes.toIntOrNull() in 1..1440), modifier = Modifier.testTag("guided-plan")) {
                Text(stringResource(R.string.guided_plan))
            }
        }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text(stringResource(R.string.guided_cancel)) },
        text = { Text(stringResource(R.string.guided_cancel_hint)) }, confirmButton = { TextButton(onClick = { confirmCancel = false; onCancel() }) {
            Text(stringResource(R.string.guided_cancel)) } }, dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.adventure_not_now)) } })
}
