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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
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
    var imports by remember { mutableStateOf<List<RecoveryText>?>(null) }
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var showProblems by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
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
        canEdit = current?.blocked == null, queueHeader = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
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
            if (failed) Text(stringResource(R.string.shared_copy_failed), color = MaterialTheme.colorScheme.error)
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
                Text(stringResource(when (intent.problem) {
                    "FIELD_CONFLICT", "DELETION_CONFLICT" -> R.string.shared_conflict_reason
                    "TASK_LIMIT" -> R.string.shared_limit_reason
                    "BLOCKED_DEPENDENCY" -> R.string.shared_dependency_reason
                    else -> R.string.shared_preserved_reason
                }))
                if (!intent.description.isNullOrEmpty()) Text(intent.description)
                intent.receipt?.let { receipt ->
                    JSONObject(receipt).optJSONObject("task")?.let { task ->
                        Text(stringResource(R.string.shared_current, task.getString("title")))
                        Text(stringResource(R.string.shared_current_description, task.nullableString("description").orEmpty()))
                    }
                }
                TextButton(enabled = !busy && current?.blocked == null, onClick = { run {
                    repository.copyText(intent.title, intent.description.orEmpty())
                    showProblems = false
                    SharedSyncWorker.request(context, data)
                } }) { Text(stringResource(R.string.shared_copy_new)) }
            }
        } }, confirmButton = { TextButton(onClick = { showProblems = false }, enabled = !busy) { Text(stringResource(R.string.back)) } })
}
