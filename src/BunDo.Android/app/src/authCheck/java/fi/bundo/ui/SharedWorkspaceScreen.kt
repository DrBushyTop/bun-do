package fi.bundo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.flow.first
import org.json.JSONObject

@Composable
fun SharedWorkspaceScreen(data: AccountData, selected: SharedWorkspace, appearance: String,
    onAppearance: (String) -> Unit, onAccount: () -> Unit, onWelcome: (() -> Unit)? = null) {
    val resources = LocalResources.current
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
    var feedback by remember { mutableStateOf<HouseholdFeedback?>(null) }
    var completionTurn by rememberSaveable { mutableLongStateOf(0L) }
    var views by remember { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable(selected.scope) { mutableStateOf("queue") }
    var journeyBusy by remember(data.lease.generation, selected.scope) { mutableStateOf(false) }
    var journeyFailed by remember(data.lease.generation, selected.scope) { mutableStateOf(false) }
    fun journey(enable: Boolean) {
        if (journeyBusy) return
        journeyBusy = true
        journeyFailed = false
        scope.launch {
            try {
                (context.applicationContext as BunDoApplication).withAccountToken(data) { token ->
                    SharedJourney.refresh(context, repository, token, enable)
                }
                SharedSyncWorker.request(context, data)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { journeyFailed = true }
            finally { journeyBusy = false }
        }
    }
    val adventure by repository.adventure.collectAsStateWithLifecycle(null)
    var adventureBusy by remember(data.lease.generation, selected.scope) { mutableStateOf(false) }
    var adventureFailed by remember(data.lease.generation, selected.scope) { mutableStateOf(false) }
    fun adventureAction(action: JSONObject) {
        if (adventureBusy) return
        adventureBusy = true; adventureFailed = false
        scope.launch {
            try { (context.applicationContext as BunDoApplication).withAccountToken(data) { token ->
                SharedAdventure.send(context, repository, token, action)
            } } catch (error: CancellationException) { throw error }
            catch (_: Exception) { adventureFailed = true }
            finally { adventureBusy = false }
        }
    }
    val creation = current?.adventureCreation?.let(GuidedCreation::read)
    fun guidedAction(work: suspend (String) -> Unit) {
        if (adventureBusy) return
        adventureBusy = true; adventureFailed = false
        scope.launch {
            try { (context.applicationContext as BunDoApplication).withAccountToken(data) { token -> work(token) }
                SharedSyncWorker.request(context, data)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { adventureFailed = true }
            finally { adventureBusy = false }
        }
    }
    LaunchedEffect(current?.revision, creation?.stage, destination) {
        if (destination == "creator" && creation?.stage == "QUEUED")
            guidedAction { token -> GuidedAdventure.resume(context, repository, token) }
    }
    LaunchedEffect(adventure?.active?.id) {
        if (destination == "creator" && adventure?.active != null) destination = "adventure"
    }
    LaunchedEffect(destination, selected.scope) {
        if (destination == "adventure") adventureAction(JSONObject().put("action", "visit"))
    }
    var history by rememberSaveable(selected.scope) { mutableStateOf(false) }
    var deleted by rememberSaveable(selected.scope) { mutableStateOf(false) }
    var snoozed by rememberSaveable(selected.scope) { mutableStateOf(false) }
    var dueOnly by rememberSaveable(selected.scope) { mutableStateOf(false) }
    val reminderSettings by remember(data) { data.database.reminders().observeSettings() }.collectAsState(null)
    var now by remember { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(selected.scope) { while (true) { kotlinx.coroutines.delay(30_000); now = java.time.Instant.now() } }
    var checklistId by rememberSaveable(selected.scope) { mutableStateOf<String?>(null) }
    var checklistDraft by remember { mutableStateOf<ChecklistDraft?>(null) }
    var dictatingSteps by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember(selected.scope) { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.task_deleted)
    val undoLabel = stringResource(R.string.task_undo)
    val dueIds = fi.bundo.reminders.ReminderPolicy.candidates(taskStates.map {
        ReminderCoordinator.fromProjection(selected.scope, it, reminderSettings?.dateOnlyTime ?: "09:00", emptySet())
    }, "", true, 0).filter { it.at <= now }.map { it.task.id }.toSet()
    val queueRows = taskStates.filter { if (dueOnly) it.getString("id") in dueIds
        else if (deleted) !it.isNull("deletion") &&
        (it.isNull("parentId") || byId[it.getString("parentId")]?.isNull("deletion") == true)
        else it.isNull("parentId") && it.isNull("deletion") && (it.optString("lifecycle", "OPEN") != "OPEN") == history &&
            (history || SharedChecklistActions.snoozed(it, byId, now) == snoozed) }
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
    fun act(action: SharedTaskAction) {
        run {
            val sequence = repository.act(action.kind, action.displayed, action.confirmedClaimant, action.after, action.before, action.until, expectedOrder = action.expectedOrder)
            SharedSyncWorker.request(context, data)
            val taskId = JSONObject(action.displayed).getString("id")
            val message = when (action.kind) {
                "DeleteTask" -> deletedMessage
                "CompleteTask" -> resources.getString(R.string.feedback_completed)
                "ClaimTask" -> resources.getString(R.string.feedback_claimed)
                "MoveTask" -> {
                    val order = repository.taskStates.first().filter { it.isNull("parentId") && it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" }
                    resources.getString(R.string.queue_position, order.indexOfFirst { it.getString("id") == taskId } + 1, order.size)
                }
                else -> null
            }
            feedback = null
            if (action.kind == "CompleteTask") feedback = HouseholdFeedback(++completionTurn, "complete")
            if (action.kind == "ClaimTask") feedback = HouseholdFeedback(System.nanoTime(), "claim")
            if (message != null) scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val undo = action.kind in listOf("DeleteTask", "CompleteTask")
                if (snackbar.showSnackbar(message, if (undo) undoLabel else null, withDismissAction = true,
                        duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    snapshotFlow { busy }.first { !it }
                    run {
                        if (action.kind == "DeleteTask") repository.undoDelete(taskId, sequence)
                        else repository.undoCompletion(taskId, sequence)
                        SharedSyncWorker.request(context, data)
                    }
                }
            }
        }
    }
    LaunchedEffect(checklistId) {
        try { checklistDraft = checklistId?.let { repository.checklistDraft(it) } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(selected.scope, current?.nextSequence) { SharedSyncWorker.request(context, data) }
    LaunchedEffect(current?.blocked) {
        if (current?.blocked == "FORBIDDEN") {
            model.hide()
            checklistId = null
            checklistDraft = null
            dictatingSteps = false
            reapply = null
        }
    }
    DisposableEffect(owner, data.lease.generation, selected.scope) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) SharedSyncWorker.request(context, data)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); if (!data.lease.active) model.hide() }
    }
    val artworkLoader: suspend (AdventureChoice, Boolean) -> ArtworkResult = remember(data.lease.generation, selected.scope) {
        { choice, retry -> AdventureArtworkClient.load(context, repository, data, choice, retry) }
    }
    CompositionLocalProvider(LocalAdventureArtwork provides artworkLoader) {
    InboxApp(state, model, appearance, onAppearance, voice = data.voice, voiceTarget = VoiceTarget(selected.scope), onAccount = onAccount, onWelcome = onWelcome, queueTitle = selected.name,
        queueNavigation = { SharedHouseholdNavigation(if (destination in listOf("journey", "adventure", "creator")) "together" else destination) { destination = it } },
        queueContent = if (destination != "queue") ({ onOpen ->
            val progress = current?.progress?.takeIf { current?.blocked == null && recovery == null }
            if (destination == "creator") GuidedAdventureScreen(creation, adventure?.creation, adventureBusy, adventureFailed, byId,
                current?.blocked == null && recovery == null && adventure?.active == null,
                { outcome, minutes -> guidedAction { token -> GuidedAdventure.plan(context, repository, token, outcome, minutes) } },
                { draft -> guidedAction { token -> repository.approveGuidedCreation(draft); GuidedAdventure.resume(context, repository, token) } },
                { guidedAction { token -> GuidedAdventure.resume(context, repository, token) } },
                { scope.launch { repository.discardGuidedReview() } },
                { guidedAction { token ->
                    val pending = checkNotNull(adventure?.creation)
                    SharedAdventure.send(context, repository, token, JSONObject().put("action", "cancelCreation").put("creationId", pending.getString("id")).put("confirmed", true))
                    // The shared reservation is gone; any already queued tasks remain ordinary tasks.
                    repository.clearCancelledGuidedCreation(pending.getString("id"))
                } }, { destination = "adventure" })
            else if (destination == "adventure") SharedAdventureScreen(adventure?.takeIf { recovery == null && current?.blocked == null },
                adventureBusy, adventureFailed, current != null && current?.blocked == null && recovery == null,
                byId, ::adventureAction, onOpen, { destination = "together" }, { repository.acknowledgeAdventure(it, false) }, { destination = "creator" })
            else if (destination == "journey") SharedJourneyScreen(progress, journeyBusy, journeyFailed,
                current != null && current?.blocked == null && recovery == null, { journey(true) }, { journey(false) }, { destination = "together" })
            else SharedProgressScreen(progress, destination == "activity", byId, membership,
                { SharedSyncWorker.request(context, data) }, onOpen, { destination = "journey" }, { destination = "adventure" })
        }) else null,
        canEdit = current?.blocked == null, queueTasks = queueRows,
        onSplit = if (state.editor?.let { it.key == InboxRepository.NEW_DRAFT || byId[it.key]?.let { task ->
            task.isNull("parentId") && !task.optBoolean("isChecklist") && task.optString("lifecycle", "OPEN") == "OPEN"
        } == true } == true) ({ model.closeEditor(true) { checklistId = it } }) else null,
        canEditTask = { id -> byId[id]?.isNull("deletion") == true },
        snackbarHost = { HouseholdSnackbar(snackbar, feedback) },
        taskAttribution = { id -> byId[id]?.let { SharedTaskAttribution(it, membership) } },
        onTaskSaved = { id, created -> if (created) run {
            feedback = HouseholdFeedback(System.nanoTime(), "file")
            snackbar.currentSnackbarData?.dismiss()
            val message = resources.getString(if (repository.placement(id) == "EXPEDITED") R.string.detail_saved_priority else R.string.detail_saved_append)
            scope.launch {
                if (snackbar.showSnackbar(message, undoLabel, withDismissAction = true,
                        duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    snapshotFlow { busy }.first { !it }
                    run {
                        repository.undoCapture(id)
                        SharedSyncWorker.request(context, data)
                    }
                }
            }
        } },
        rowSummary = { id -> byId[id]?.let { SharedTaskSummary(it, membership); ChecklistProgress(it, byId) } },
        taskControls = { id, onOpen -> byId[id]?.let { task ->
            SharedChecklist(task, byId, membership, !busy && current?.blocked == null && recovery == null,
                onOpen, { checklistId = id }, ::act)
            SharedTaskControls(task, taskStates, membership, !busy && current?.blocked == null && recovery == null, failed, ::act)
            SharedRepeatControls(task, membership, !busy && current?.blocked == null && recovery == null) { displayed, blueprint ->
                run {
                    repository.changeRepeat(displayed, blueprint) {
                        (context.applicationContext as BunDoApplication).withAccountToken(data) { token ->
                            SharedRepeatSetup.checkConnection(context, repository, token)
                        }
                    }
                    SharedSyncWorker.request(context, data)
                }
            }
        } },
        queueList = { onOpen -> SharedQueue(queueRows, taskStates, membership,
            !busy && current?.blocked == null && recovery == null, onOpen, ::act,
            onFullQueue = { history = false; deleted = false; snoozed = false; dueOnly = false },
            toolbar = {
                Row(Modifier.fillMaxWidth()) {
                    Box {
                        TextButton(onClick = { views = true }, modifier = Modifier.testTag("queue-views")) {
                            Text(stringResource(when { dueOnly -> R.string.reminders_due; deleted -> R.string.task_deleted_view
                                history -> R.string.task_history_view; snoozed -> R.string.task_snoozed_view; else -> R.string.task_active_view }))
                        }
                        DropdownMenu(views, { views = false }) {
                            listOf("active" to R.string.task_active_view, "history" to R.string.task_history_view,
                                "deleted" to R.string.task_deleted_view, "snoozed" to R.string.task_snoozed_view,
                                "due" to R.string.reminders_due).forEach { (view, label) ->
                                DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.testTag("task-$view-view"), onClick = {
                                    history = view == "history"; deleted = view == "deleted"; snoozed = view == "snoozed"; dueOnly = view == "due"; views = false
                                })
                            }
                        }
                    }
                    TextButton(onClick = { tools = !tools }, modifier = Modifier.testTag("queue-tools")) { Text(stringResource(R.string.queue_tools)) }
                }
            }, notices = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (dueOnly && queueRows.isEmpty()) Text(stringResource(R.string.reminders_due_empty))
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
            if (tools) {
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
            }
            }
                if (problems.isNotEmpty()) TextButton(onClick = { showProblems = true },
                    modifier = Modifier.testTag("shared-recovery")) {
                    Text(stringResource(R.string.shared_review, problems.size))
                }
            if (failed) Text(stringResource(R.string.shared_action_failed), color = MaterialTheme.colorScheme.error)
        }
    }) })
    checklistDraft?.takeUnless { dictatingSteps || current?.blocked != null }?.let { draft -> ChecklistEditor(draft, busy || current?.blocked != null, failed,
        repository::saveChecklistDraft,
        onSave = { latest -> run {
            repository.saveChecklistDraft(latest)
            repository.commitChecklist(latest.taskId)
            checklistId = null; checklistDraft = null
            SharedSyncWorker.request(context, data)
        } },
        onClose = { latest -> run { repository.saveChecklistDraft(latest); checklistId = null; checklistDraft = null } },
        request = byId[draft.taskId]?.optJSONObject("cleanup"),
        canGenerate = byId[draft.taskId]?.let { !it.optBoolean("isChecklist") && it.isNull("parentId") && it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" } == true,
        onGenerate = { latest -> run { repository.requestSplit(latest); SharedSyncWorker.request(context, data) } },
        onAdopt = { run { checklistDraft = repository.adoptSplit(draft.taskId) } },
        onManual = { latest -> run { checklistDraft = repository.manualChecklist(latest) } },
        onCancel = { latest -> run {
            repository.saveChecklistDraft(latest)
            repository.act("CancelCleanup", checkNotNull(byId[draft.taskId]).toString())
            SharedSyncWorker.request(context, data)
        } },
        onDictate = { latest -> run { repository.saveChecklistDraft(latest); data.voice.closeReview(); data.voice.acknowledgeSaved(); dictatingSteps = true } }) }
    if (dictatingSteps && checklistId != null && current?.blocked == null) VoiceSheet(data.voice,
        onDismiss = { dictatingSteps = false }, onType = { dictatingSteps = false },
        onSaved = { run { dictatingSteps = false; checklistDraft = repository.checklistDraft(checkNotNull(checklistId)) } },
        target = VoiceTarget(selected.scope, checkNotNull(checklistId)))
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
                    repository.copyText(text.title, text.description, text.capturedAt, text.captureContext)
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
                    "REPEAT_CONFLICT", "REPEAT_MOVED", "REPEAT_OPEN_CONFLICT", "REPEAT_UNAVAILABLE" -> R.string.repeat_conflict
                    "CLAIM_CONFLICT", "ALREADY_CLAIMED", "CLAIM_CONFIRMATION_REQUIRED", "CLAIM_NOT_YOURS" -> R.string.task_claim_conflict
                    "LIFECYCLE_CONFLICT", "HIERARCHY_CONFLICT", "ORDER_CONFLICT", "SUBTREE_CONFLICT", "SNOOZE_CONFLICT", "TASK_SNOOZED", "PARENT_UNAVAILABLE", "CHECKLIST_ROOT", "CHECKLIST_DERIVED", "TASK_NOT_OPEN", "TASK_NOT_DELETED" -> R.string.task_state_conflict
                    "TASK_DELETED", "TASK_PURGING" -> R.string.task_deleted_conflict
                    "FIELD_CONFLICT", "DELETION_CONFLICT" -> R.string.shared_conflict_reason
                    "TASK_LIMIT", "CHECKLIST_LIMIT" -> R.string.shared_limit_reason
                    "BLOCKED_DEPENDENCY" -> R.string.shared_dependency_reason
                    else -> R.string.shared_preserved_reason
                }))
                if (!intent.description.isNullOrEmpty()) Text(intent.description)
                intent.details?.let(::JSONObject)?.let { detail ->
                    detail.optJSONObject("due")?.let { Text(dueLabel(it)) }
                    if (detail.has("due") && detail.isNull("due")) Text(stringResource(R.string.detail_no_due))
                    if (detail.has("urgent")) Text(stringResource(if (detail.getBoolean("urgent")) R.string.detail_urgent else R.string.detail_not_urgent))
                }
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
                if (intent.taskAction == null || intent.kind in SharedChecklistActions.splitKinds || shared == null || !JSONObject(shared).isNull("deletion"))
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
}
