package fi.bundo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.bundo.BunDoApplication
import fi.bundo.R
import fi.bundo.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SharedWorkspaceScreen(data: AccountData, selected: SharedWorkspace, appearance: String,
    onAppearance: (String) -> Unit, onAccount: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = remember(data.lease.generation, selected.scope) {
        SharedRepository(data.database, data.lease, selected.scope, checkNotNull(data.registrationId))
    }
    val model: InboxViewModel = viewModel(key = "${data.lease.generation}/${selected.scope}", factory = viewModelFactory {
        initializer { InboxViewModel(repository, createSavedStateHandle()) }
    })
    val state by model.state.collectAsStateWithLifecycle()
    val problems by repository.problems.collectAsStateWithLifecycle(emptyList())
    val current by repository.workspace.collectAsStateWithLifecycle(selected)
    val recovery by repository.recovery.collectAsStateWithLifecycle(null)
    val canonical by repository.canonical.collectAsStateWithLifecycle(emptyMap())
    val taskStates by repository.taskStates.collectAsStateWithLifecycle(emptyList())
    val byId = taskStates.associateBy { it.getString("id") }
    val membership = current?.membership?.let(::JSONObject)
    var history by rememberSaveable(selected.scope) { mutableStateOf(false) }
    var deleted by rememberSaveable(selected.scope) { mutableStateOf(false) }
    val snackbar = remember(selected.scope) { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.task_undo)
    val queueRows = taskStates.filter { if (deleted) !it.isNull("deletion")
        else it.isNull("deletion") && (it.optString("lifecycle", "OPEN") != "OPEN") == history }
        .let { rows -> if (history) rows.sortedByDescending { it.optString("lifecycleAt") } else rows }
        .map(SharedProtocol::inbox)
    var imports by remember { mutableStateOf<List<RecoveryText>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var showProblems by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reapply by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected.scope, current?.nextSequence) { SharedSyncWorker.request(context, data) }
    LaunchedEffect(current?.blocked) {
        if (current?.blocked == "FORBIDDEN") model.hide()
    }
    DisposableEffect(owner, data.lease.generation, selected.scope) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) SharedSyncWorker.request(context, data)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); if (!data.lease.active) model.hide() }
    }
    InboxApp(state, model, appearance, onAppearance, onAccount = onAccount, queueTitle = selected.name,
        canEdit = current?.blocked == null, queueTasks = queueRows,
        canEditTask = { id -> byId[id]?.isNull("deletion") == true },
        snackbarHost = { SnackbarHost(snackbar) },
        rowSummary = { id -> byId[id]?.let { SharedTaskSummary(it, membership) } },
        taskControls = { id -> byId[id]?.let { task ->
            SharedTaskControls(task, taskStates, membership, !busy && current?.blocked == null && recovery == null, failed) { action ->
                run {
                    val sequence = repository.act(action.kind, action.displayed, action.confirmedClaimant, action.after, action.before)
                    SharedSyncWorker.request(context, data)
                    if (action.kind == "DeleteTask") scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        if (snackbar.showSnackbar(deletedMessage, undoLabel, withDismissAction = true,
                                duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) run {
                            repository.undoDelete(JSONObject(action.displayed).getString("id"), sequence)
                            SharedSyncWorker.request(context, data)
                        }
                    }
                }
            }
        } },
        queueHeader = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip(selected = !history && !deleted, onClick = { history = false; deleted = false }, modifier = Modifier.testTag("task-active-view"),
                label = { Text(stringResource(R.string.task_active_view)) })
            FilterChip(selected = history && !deleted, onClick = { history = true; deleted = false }, modifier = Modifier.testTag("task-history-view"),
                label = { Text(stringResource(R.string.task_history_view)) })
            FilterChip(selected = deleted, onClick = { deleted = true }, modifier = Modifier.testTag("task-deleted-view"),
                label = { Text(stringResource(R.string.task_deleted_view)) })
            if (deleted) Text(stringResource(if (queueRows.isEmpty()) R.string.task_deleted_empty else R.string.task_deleted_retention))
            recovery?.let { recovering ->
                Text(stringResource(if (recovering.problem == "STORAGE_REQUIRED") R.string.shared_storage_required
                    else if (recovering.problem != null) R.string.shared_recovery_paused else R.string.shared_rebuilding))
                if (recovering.problem != null) {
                    TextButton(onClick = onAccount) { Text(stringResource(R.string.shared_export_saved)) }
                    if (recovering.problem == "STORAGE_REQUIRED") TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                    }) { Text(stringResource(R.string.shared_manage_storage)) }
                }
            }
            if (current?.blocked != null) Text(stringResource(R.string.shared_access_lost),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp).testTag("shared-blocked"))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { run { data.selectHousehold(null) } }, enabled = !busy,
                    modifier = Modifier.testTag("shared-local")) { Text(stringResource(R.string.shared_local)) }
                TextButton(onClick = { run {
                    imports = (context.applicationContext as BunDoApplication).accounts.sharedImportPreview(data)
                    chosen = emptySet()
                } }, enabled = !busy && current?.blocked == null,
                    modifier = Modifier.testTag("shared-import")) { Text(stringResource(R.string.shared_import)) }
            }
            Row {
                TextButton(onClick = { SharedSyncWorker.request(context, data) }, enabled = current?.blocked == null,
                    modifier = Modifier.testTag("shared-refresh")) { Text(stringResource(R.string.household_refresh)) }
                if (problems.isNotEmpty()) TextButton(onClick = { showProblems = true },
                    modifier = Modifier.testTag("shared-recovery")) {
                    Text(stringResource(R.string.shared_review, problems.size))
                }
            }
            if (failed) Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
        }
    })
    imports?.let { texts ->
        AlertDialog(onDismissRequest = { if (!busy) imports = null },
            title = { Text(stringResource(R.string.shared_import)) },
            text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.shared_import_explanation))
                if (failed) Text(stringResource(R.string.shared_copy_failed), color = MaterialTheme.colorScheme.error)
                for (text in texts) Row {
                    Checkbox(checked = text.source in chosen, enabled = !busy, onCheckedChange = { checked ->
                        chosen = if (checked) chosen + text.source else chosen - text.source
                    })
                    Text(text.title, Modifier.padding(top = 12.dp))
                }
                if (texts.isEmpty()) Text(stringResource(R.string.shared_no_local))
            } },
            confirmButton = { TextButton(enabled = !busy && chosen.isNotEmpty(), onClick = { run {
                for (text in texts.filter { it.source in chosen }) {
                    repository.copyText(text.title, text.description)
                    chosen = chosen - text.source
                }
                imports = null
                SharedSyncWorker.request(context, data)
            } }) { Text(stringResource(R.string.shared_copy)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { imports = null }) { Text(stringResource(R.string.back)) } })
    }
    if (showProblems) AlertDialog(onDismissRequest = { if (!busy) showProblems = false },
        title = { Text(stringResource(R.string.shared_review, problems.size)) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.shared_recovery_explanation))
            for (intent in problems) {
                Text(intent.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                if (intent.taskAction != null) Text(stringResource(taskActionLabel(intent.kind)))
                Text(stringResource(when (intent.problem) {
                    "CLAIM_CONFLICT", "ALREADY_CLAIMED", "CLAIM_CONFIRMATION_REQUIRED", "CLAIM_NOT_YOURS" -> R.string.task_claim_conflict
                    "LIFECYCLE_CONFLICT", "HIERARCHY_CONFLICT", "ORDER_CONFLICT", "TASK_NOT_OPEN", "TASK_NOT_DELETED" -> R.string.task_state_conflict
                    "TASK_DELETED", "TASK_PURGING" -> R.string.task_deleted_conflict
                    "FIELD_CONFLICT", "DELETION_CONFLICT" -> R.string.shared_conflict_reason
                    "TASK_LIMIT" -> R.string.shared_limit_reason
                    "BLOCKED_DEPENDENCY" -> R.string.shared_dependency_reason
                    else -> R.string.shared_preserved_reason
                }))
                if (!intent.description.isNullOrEmpty()) Text(intent.description)
                val shared = canonical[intent.taskId]
                if (shared != null) {
                    val task = JSONObject(shared)
                    Text(stringResource(R.string.shared_current, task.getString("title")))
                    Text(stringResource(R.string.shared_current_description, task.nullableString("description").orEmpty()))
                    SharedTaskSummary(task, membership)
                } else Text(stringResource(R.string.shared_missing))
                val terminal = intent.status in listOf("REJECTED", "BLOCKED_DEPENDENCY", "QUARANTINED")
                if (shared != null && JSONObject(shared).isNull("deletion") && intent.kind == "EditTask" && terminal) TextButton(
                    enabled = !busy && current?.blocked == null && recovery == null,
                    onClick = { reapply = intent.sequence to shared }) {
                    Text(stringResource(R.string.shared_reapply))
                }
                if (intent.taskAction == null || shared == null || !JSONObject(shared).isNull("deletion"))
                    TextButton(enabled = !busy && current?.blocked == null && terminal, onClick = { run {
                    repository.copyText(intent.title, intent.description.orEmpty())
                    showProblems = false
                    SharedSyncWorker.request(context, data)
                } }) { Text(stringResource(R.string.shared_copy_new)) }
                if (terminal) TextButton(enabled = !busy, onClick = { run { repository.dismiss(intent.sequence) } }) {
                    Text(stringResource(R.string.shared_dismiss))
                }
            }
        } }, confirmButton = { TextButton(onClick = { showProblems = false }, enabled = !busy) { Text(stringResource(R.string.back)) } })
    reapply?.let { confirmation ->
        AlertDialog(onDismissRequest = { if (!busy) reapply = null },
            title = { Text(stringResource(R.string.shared_reapply)) },
            text = { Text(stringResource(R.string.shared_reapply_confirm, JSONObject(confirmation.second).getString("title"))) },
            confirmButton = { TextButton(enabled = !busy, onClick = { run {
                repository.reapply(confirmation.first, confirmation.second)
                reapply = null
                SharedSyncWorker.request(context, data)
            } }) { Text(stringResource(R.string.shared_reapply)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { reapply = null }) { Text(stringResource(R.string.back)) } })
    }
}
