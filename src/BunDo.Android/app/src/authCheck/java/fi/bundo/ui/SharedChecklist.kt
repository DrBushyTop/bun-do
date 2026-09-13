package fi.bundo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.*
import org.json.JSONObject

@Composable
internal fun ChecklistProgress(task: JSONObject, tasks: Map<String, JSONObject>) {
    if (!task.optBoolean("isChecklist")) return
    val eligible = SharedChecklistActions.childIds(task).mapNotNull(tasks::get)
        .filter { it.isNull("deletion") && it.optString("lifecycle", "OPEN") != "CANCELLED" }
    Text(stringResource(R.string.checklist_progress, eligible.count { it.optString("lifecycle") == "COMPLETED" }, eligible.size),
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun SharedChecklist(task: JSONObject, tasks: Map<String, JSONObject>, membership: JSONObject?, enabled: Boolean,
    onOpen: (String) -> Unit, onAdd: () -> Unit, onAction: (SharedTaskAction) -> Unit) {
    var confirmation by remember(task.getString("id")) { mutableStateOf<SharedTaskAction?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        task.nullableString("parentId")?.let { parent ->
            TextButton(onClick = { onOpen(parent) }, modifier = Modifier.testTag("checklist-parent")) { Text(stringResource(R.string.checklist_parent)) }
        }
        if (task.optBoolean("isChecklist")) {
            Text(stringResource(R.string.checklist_title), style = MaterialTheme.typography.titleMedium)
            ChecklistProgress(task, tasks)
            for (child in SharedChecklistActions.childIds(task).mapNotNull(tasks::get).filter { it.isNull("deletion") }) {
                val id = child.getString("id")
                val toggleLabel = stringResource(R.string.checklist_toggle, child.getString("title"))
                val completed = child.optString("lifecycle", "OPEN") == "COMPLETED"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = completed, enabled = enabled && task.isNull("deletion") &&
                        !SharedChecklistActions.snoozed(child, tasks) && child.optString("lifecycle", "OPEN") != "CANCELLED",
                        modifier = Modifier.testTag("checklist-check-$id").semantics { contentDescription = toggleLabel }, onCheckedChange = { checked ->
                            val other = child.nullableString("claimantId")?.takeIf { it != membership?.optString("me") }
                            val action = SharedTaskAction(if (checked) "CompleteTask" else "ReopenTask", child.toString(), other)
                            if (checked && other != null) confirmation = action else onAction(action)
                        })
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(id) }.padding(vertical = 8.dp)) {
                        Text(child.getString("title"), style = MaterialTheme.typography.bodyLarge)
                        SharedTaskSummary(child, membership)
                    }
                }
            }
        }
        if (task.isNull("parentId") && task.isNull("deletion") &&
            (task.optString("lifecycle", "OPEN") == "OPEN" || task.optBoolean("emptyChecklist")))
            OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.testTag("checklist-add")) {
                Text(stringResource(if (task.optBoolean("isChecklist")) R.string.checklist_add else R.string.checklist_split))
            }
    }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(stringResource(R.string.task_complete)) },
        text = { Text(stringResource(R.string.task_confirm_other, memberName(membership, action.confirmedClaimant))) },
        confirmButton = { TextButton(enabled = enabled, onClick = { confirmation = null; onAction(action) }) { Text(stringResource(R.string.task_complete)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.back)) } }) }
}

@Composable
internal fun ChecklistEditor(draft: ChecklistDraft, busy: Boolean, failed: Boolean,
    onSaveDraft: suspend (ChecklistDraft) -> Unit, onSave: (ChecklistDraft) -> Unit, onClose: (ChecklistDraft) -> Unit) {
    var text by rememberSaveable(draft.taskId) { mutableStateOf(draft.text) }
    var writeFailed by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        try { onSaveDraft(draft.copy(text = text)); writeFailed = false }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { writeFailed = true }
    }
    val items = text.lines().map(String::trim).filter(String::isNotEmpty)
    val valid = items.size in 1..SharedChecklistActions.MAX_ITEMS && items.all { InboxLimits.valid(it, "") }
    AlertDialog(onDismissRequest = { if (!busy) onClose(draft.copy(text = text)) },
        title = { Text(stringResource(R.string.checklist_add)) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.title)
            Text(stringResource(R.string.checklist_editor_help, SharedChecklistActions.MAX_ITEMS))
            OutlinedTextField(value = text, onValueChange = { if (it.length <= 16000) text = it }, minLines = 4,
                enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("checklist-draft"), label = { Text(stringResource(R.string.checklist_items)) })
            if (!valid && text.isNotBlank()) Text(stringResource(R.string.checklist_invalid, SharedChecklistActions.MAX_ITEMS), color = MaterialTheme.colorScheme.error)
            if (failed || writeFailed) Text(stringResource(R.string.checklist_save_failed), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(enabled = valid && !busy, modifier = Modifier.testTag("checklist-save"),
            onClick = { onSave(draft.copy(text = text)) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { onClose(draft.copy(text = text)) }) { Text(stringResource(R.string.back)) } })
}
