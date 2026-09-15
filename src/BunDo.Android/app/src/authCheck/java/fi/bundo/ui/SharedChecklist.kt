package fi.bundo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        val aiSplit = task.optJSONObject("cleanup")?.takeIf { it.optString("mode") == "SPLIT" }
        if (aiSplit?.optString("status") in listOf("PENDING", "RUNNING")) Text(stringResource(R.string.split_pending))
        if (aiSplit?.optString("status") == "READY") Text(stringResource(R.string.split_review))
        if (aiSplit?.optString("status") == "FAILED") Text(stringResource(R.string.split_failed))
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
    onSaveDraft: suspend (ChecklistDraft) -> Unit, onSave: (ChecklistDraft) -> Unit, onClose: (ChecklistDraft) -> Unit,
    request: JSONObject? = null, canGenerate: Boolean = false, onGenerate: (ChecklistDraft) -> Unit = {},
    onAdopt: () -> Unit = {}, onManual: (ChecklistDraft) -> Unit = {}, onCancel: (ChecklistDraft) -> Unit = {}, onDictate: (ChecklistDraft) -> Unit = {}) {
    val loadedId = draft.details?.let(::JSONObject)?.optString("proposalId")
    val loadedPreview = draft.details?.let(::JSONObject)?.has("rows") == true
    var text by rememberSaveable(draft.taskId, loadedId, loadedPreview) { mutableStateOf(draft.text) }
    var details by rememberSaveable(draft.taskId, loadedId, loadedPreview, draft.details?.let(::JSONObject)?.optString("instructions")) {
        mutableStateOf(draft.details ?: "{}")
    }
    var writeFailed by remember { mutableStateOf(false) }
    fun latest() = draft.copy(text = text, details = details)
    fun change(block: (JSONObject) -> Unit) { details = JSONObject(details).also(block).toString() }
    LaunchedEffect(text, details) {
        try { onSaveDraft(latest()); writeFailed = false }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { writeFailed = true }
    }
    val value = JSONObject(details)
    val rows = value.optJSONArray("rows")
    val instructions = value.optString("instructions")
    val items = SharedSplitPreview.items(latest())
    val valid = SharedSplitPreview.valid(items)
    val splitRequest = request?.takeIf { it.optString("mode") == "SPLIT" }
    val pending = splitRequest?.optString("status") in listOf("PENDING", "RUNNING")
    Dialog(onDismissRequest = { if (!busy) onClose(latest()) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(enabled = !busy, onClick = { onClose(latest()) }) { Text(stringResource(R.string.back)) }
                    Text(stringResource(if (rows == null) R.string.checklist_add else R.string.split_preview_title),
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    TextButton(enabled = valid && !busy, modifier = Modifier.testTag("checklist-save"),
                        onClick = { onSave(latest()) }) { Text(stringResource(R.string.save)) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(draft.title, style = MaterialTheme.typography.titleMedium)
            value.nullableString("sourceDescription")?.let { Text(it) }
            if (canGenerate || splitRequest != null) {
                OutlinedTextField(value = instructions, onValueChange = { if (InboxLimits.length(it) <= 2000) change { value -> value.put("instructions", it) } },
                    enabled = !busy && !pending, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("split-instructions"),
                    label = { Text(stringResource(R.string.split_instructions)) })
                TextButton(enabled = !busy && !pending, onClick = { onDictate(latest()) }, modifier = Modifier.testTag("split-dictate")) {
                    Text(stringResource(R.string.split_dictate))
                }
                if (pending) {
                    Text(stringResource(R.string.split_pending))
                    TextButton(enabled = !busy, onClick = { onCancel(latest()) }, modifier = Modifier.testTag("split-cancel")) { Text(stringResource(R.string.split_cancel)) }
                } else if (canGenerate) OutlinedButton(enabled = !busy, onClick = { onGenerate(latest()) }, modifier = Modifier.testTag("split-generate")) {
                    Text(stringResource(if (splitRequest?.optString("status") == "FAILED") R.string.split_retry else R.string.split_generate))
                }
                if (splitRequest?.optString("status") == "FAILED") Text(stringResource(R.string.split_failed), color = MaterialTheme.colorScheme.error)
                if (splitRequest?.optString("status") == "READY" && splitRequest.optString("id") != value.optString("proposalId"))
                    Button(enabled = !busy, onClick = onAdopt, modifier = Modifier.testTag("split-review")) { Text(stringResource(R.string.split_review)) }
            }
            if (rows == null) {
                Text(stringResource(R.string.checklist_editor_help, SharedChecklistActions.MAX_ITEMS))
                OutlinedTextField(value = text, onValueChange = { if (it.length <= 16000) text = it }, minLines = 4,
                    enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("checklist-draft"), label = { Text(stringResource(R.string.checklist_items)) })
            } else {
                Text(stringResource(R.string.split_review_help))
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val label = stringResource(R.string.split_select, row.getString("text"))
                        Checkbox(checked = row.optBoolean("selected", true), enabled = !busy,
                            modifier = Modifier.testTag("split-select-$index").semantics { contentDescription = label }, onCheckedChange = { selected ->
                                change { it.getJSONArray("rows").getJSONObject(index).put("selected", selected) }
                            })
                        OutlinedTextField(value = row.getString("text"), onValueChange = { edited ->
                            if (InboxLimits.length(edited) <= InboxLimits.TITLE) change { it.getJSONArray("rows").getJSONObject(index).put("text", edited) }
                        }, enabled = !busy, modifier = Modifier.weight(1f).testTag("split-item-$index"),
                            label = { Text(stringResource(R.string.split_step, index + 1)) })
                    }
                }
                TextButton(enabled = !busy, onClick = { onManual(latest()) }) { Text(stringResource(R.string.split_manual)) }
            }
            if (!valid && (text.isNotBlank() || rows != null)) Text(stringResource(R.string.split_invalid), color = MaterialTheme.colorScheme.error)
            if (failed || writeFailed) Text(stringResource(R.string.checklist_save_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
