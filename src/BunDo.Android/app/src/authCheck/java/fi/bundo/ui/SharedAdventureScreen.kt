package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import fi.bundo.R
import fi.bundo.data.*
import org.json.JSONObject
import java.time.Instant

@Composable
internal fun SharedAdventureScreen(snapshot: AdventureSnapshot?, busy: Boolean, failed: Boolean, allowed: Boolean,
    tasks: Map<String, JSONObject>, onAction: (JSONObject) -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit,
    onAcknowledge: suspend (String) -> Boolean = { false }, onCreate: (() -> Unit)? = null, onQueue: (() -> Unit)? = null,
    refreshing: Boolean = false, onRefresh: () -> Unit = { onAction(JSONObject().put("action", "visit").put("force", true)) }) {
    BackHandler(onBack = onBack)
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = Instant.now() } }
    var preview by remember(snapshot?.batchId) { mutableStateOf<AdventureChoice?>(null) }
    var edit by remember(snapshot?.active?.id, snapshot?.active?.version) { mutableStateOf(false) }
    var leave by remember(snapshot?.active?.id, snapshot?.active?.version) { mutableStateOf(false) }
    var finish by remember(snapshot?.active?.id, snapshot?.active?.version) { mutableStateOf(false) }
    val active = snapshot?.active
    val status = snapshot?.batchStatus(now)
    fun action(name: String) = JSONObject().put("action", name).put("adventureId", active!!.id).put("version", active.version)
    val refreshLabel = stringResource(R.string.refresh_adventures)
    RefreshPage(refreshLabel, refreshing, allowed && !busy, onRefresh, Modifier.testTag("adventure-page")) {
    Column(Modifier.fillMaxSize().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp).testTag("adventure-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RefreshHeading(stringResource(R.string.adventure_title), refreshLabel, allowed && !busy && !refreshing, onRefresh)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("adventure-busy"))
        if (failed) Text(stringResource(R.string.adventure_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("adventure-error"))
        if (!allowed) Text(stringResource(R.string.adventure_unavailable))
        if (active != null) {
            AdventureArtwork(active.artwork, active)
            Text(active.draft.title, style = MaterialTheme.typography.titleLarge)
            if (active.draft.flavor.isNotEmpty()) Text(active.draft.flavor)
            Text(pluralStringResource(R.plurals.adventure_progress, snapshot.total, snapshot.completed, snapshot.total), modifier = Modifier.testTag("adventure-progress"))
            LinearProgressIndicator(progress = { if (snapshot.total == 0) 0f else snapshot.completed.toFloat() / snapshot.total },
                modifier = Modifier.fillMaxWidth(), gapSize = 0.dp, drawStopIndicator = {})
            if (snapshot.complete) {
                AdventureBow(active.id, onAcknowledge)
                Text(stringResource(R.string.adventure_complete), style = MaterialTheme.typography.titleMedium)
            }
            AdventurePhases(active.draft, snapshot.tasks, tasks, onOpen)
            OutlinedButton(onClick = { edit = true }, enabled = allowed && !busy, modifier = Modifier.testTag("adventure-edit")) { Text(stringResource(R.string.adventure_edit)) }
            if (snapshot.complete) Button(onClick = { onAction(action("dismiss")) }, enabled = allowed && !busy,
                modifier = Modifier.testTag("adventure-dismiss")) { Text(stringResource(R.string.adventure_dismiss)) }
            if (!snapshot.complete) Button(onClick = { finish = true }, enabled = allowed && !busy, modifier = Modifier.testTag("adventure-finish")) { Text(stringResource(R.string.adventure_finish)) }
            TextButton(onClick = { leave = true }, enabled = allowed && !busy, modifier = Modifier.testTag("adventure-leave")) { Text(stringResource(R.string.adventure_leave)) }
        } else if (snapshot?.creation != null) {
            Text(stringResource(R.string.guided_pending))
        } else {
            if (status == "EMPTY" && !busy && !failed) {
                AdventureArtwork("dojo-garden")
                Text(stringResource(R.string.adventure_empty_heading), style = MaterialTheme.typography.titleLarge)
            }
            if (status == "READY") Text(stringResource(R.string.adventure_intro))
            if (status == "READY") for (choice in snapshot!!.proposals) {
                ElevatedCard(onClick = { preview = choice }, modifier = Modifier.fillMaxWidth().testTag("adventure-proposal-${choice.id}")) {
                    AdventureArtwork(choice.artwork, choice)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(choice.draft.title, style = MaterialTheme.typography.titleLarge)
                        if (choice.draft.flavor.isNotEmpty()) Text(choice.draft.flavor)
                        Text(pluralStringResource(R.plurals.adventure_task_count, choice.draft.phases.size, choice.draft.phases.size))
                        Text(stringResource(R.string.adventure_preview), color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else if (!busy && !(snapshot == null && refreshing)) Text(stringResource(when (status) {
                "EMPTY" -> R.string.adventure_empty; "RUNNING" -> R.string.adventure_generating
                "FAILED" -> R.string.adventure_generation_failed; "EXPIRED" -> R.string.adventure_expired
                else -> R.string.adventure_unavailable
            }), modifier = Modifier.testTag("adventure-status"))
        }
        if (active == null && onCreate != null) Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().testTag("adventure-create")) {
            Text(stringResource(if (snapshot?.creation != null) R.string.guided_resume else R.string.guided_title))
        }
        if (active == null && status == "EMPTY" && snapshot?.creation == null && onQueue != null) OutlinedButton(
            onClick = onQueue, modifier = Modifier.fillMaxWidth().testTag("adventure-add-tasks")) { Text(stringResource(R.string.adventure_add_tasks)) }
        HelpDisclosure(stringResource(R.string.adventure_how)) {
            Text(stringResource(R.string.adventure_how_body))
        }
        if (failed || status == "FAILED" || status == "EXPIRED") OutlinedButton(onClick = {
            if (status == "FAILED") onAction(JSONObject().put("action", "retry").put("batchId", snapshot?.batchId)) else onRefresh()
        }, enabled = allowed && !busy && !refreshing, modifier = Modifier.testTag("adventure-retry")) {
            Text(stringResource(R.string.adventure_retry))
        }
    }
    }
    if (preview != null && active == null) Dialog(onDismissRequest = { preview = null }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).testTag("adventure-preview"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(preview!!.draft.title, style = MaterialTheme.typography.titleLarge)
                AdventurePhases(preview!!.draft, tasks, tasks, onOpen)
                Text(stringResource(R.string.adventure_accept_hint))
                AdventureRequestFeedback(busy, failed)
                if (status != "READY") Text(stringResource(R.string.adventure_expired))
                Button(onClick = { onAction(JSONObject().put("action", "accept").put("batchId", snapshot!!.batchId).put("proposalId", preview!!.id)) },
                    enabled = allowed && !busy && status == "READY", modifier = Modifier.testTag("adventure-accept")) { Text(stringResource(if (failed) R.string.adventure_retry else R.string.adventure_accept)) }
                TextButton(onClick = { preview = null }) { Text(stringResource(R.string.adventure_not_now)) }
            }
        }
    }
    if (edit && active != null) AdventureEditor(active, tasks, !busy && allowed, busy, failed, { edit = false }) { draft ->
        onAction(action("edit").put("draft", draft.json()))
    }
    if (finish && active != null) AlertDialog(onDismissRequest = { finish = false },
        title = { Text(stringResource(R.string.adventure_finish)) }, text = { Text(stringResource(R.string.adventure_finish_confirm)) },
        confirmButton = { TextButton(onClick = { onAction(action("finish").put("confirmed", true)); finish = false }, enabled = !busy && allowed,
            modifier = Modifier.testTag("adventure-confirm-finish")) { Text(stringResource(R.string.adventure_finish)) } },
        dismissButton = { TextButton(onClick = { finish = false }) { Text(stringResource(R.string.adventure_not_now)) } })
    if (leave && active != null) AlertDialog(onDismissRequest = { leave = false },
        title = { Text(stringResource(R.string.adventure_leave)) }, text = { Text(stringResource(R.string.adventure_leave_confirm)) },
        confirmButton = { TextButton(onClick = { onAction(action("leave").put("confirmed", true)); leave = false }, enabled = !busy && allowed,
            modifier = Modifier.testTag("adventure-confirm-leave")) { Text(stringResource(R.string.adventure_leave)) } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text(stringResource(R.string.adventure_not_now)) } })
}

internal val LocalAdventureArtwork = staticCompositionLocalOf<(suspend (AdventureChoice, Boolean) -> ArtworkResult)?> { null }

@Composable
internal fun AdventureArtwork(artwork: String, choice: AdventureChoice? = null) {
    val loader = LocalAdventureArtwork.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember(choice?.id, loader) { mutableStateOf<ArtworkResult?>(null) }
    var retry by remember(choice?.id, loader) { mutableIntStateOf(0) }
    var retried by remember(choice?.id, loader) { mutableIntStateOf(0) }
    LaunchedEffect(choice?.id, artwork, retry, loader) {
        if (choice != null && loader != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            do {
                val requestedRetry = retry > retried; retried = retry
                result = try { loader(choice, requestedRetry) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { ArtworkResult("FAILED", result?.image) }
                if (result?.status in listOf("PENDING", "RUNNING")) kotlinx.coroutines.delay(15_000)
            } while (result?.status in listOf("PENDING", "RUNNING"))
        }
    }
    val modifier = Modifier.fillMaxWidth().height(144.dp).testTag("adventure-art-$artwork")
    val image = result?.image
    if (image != null) Image(image.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier)
    else Image(painterResource(R.drawable.dojo_garden), null, contentScale = ContentScale.Crop, modifier = modifier)
    if (result?.status == "FAILED") TextButton(onClick = { retry++ }) { Text(stringResource(R.string.artwork_retry)) }
}

@Composable
private fun AdventurePhases(draft: AdventureDraft, projected: Map<String, JSONObject>, editable: Map<String, JSONObject>, onOpen: (String) -> Unit) {
    for (phase in draft.phases) {
        val task = projected[phase.rootId]
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(phase.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.adventure_estimate, phase.stars, phase.minutes), style = MaterialTheme.typography.bodySmall)
            if (!AdventureSnapshot.available(task)) Text(stringResource(R.string.adventure_source_unavailable), modifier = Modifier.testTag("adventure-unavailable-${phase.rootId}"))
            else {
                if (AdventureSnapshot.available(editable[phase.rootId])) SettingsNavigationRow(task!!.getString("title"), { onOpen(phase.rootId) },
                    Modifier.testTag("adventure-task-${phase.rootId}"))
                else {
                    Text(task!!.getString("title"), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.adventure_task_sync), style = MaterialTheme.typography.bodySmall)
                }
                if (task!!.optString("lifecycle") == "COMPLETED") Text(stringResource(R.string.adventure_task_done), style = MaterialTheme.typography.bodySmall)
                val children = task.optJSONArray("childOrder")
                if (children != null) for (i in 0 until children.length()) projected[children.getString(i)]?.takeIf { it.isNull("deletion") }?.let { child ->
                    Row { Checkbox(child.optString("lifecycle") == "COMPLETED", onCheckedChange = null)
                        Text(child.getString("title"), Modifier.padding(start = 8.dp)) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun AdventureEditor(active: AdventureChoice, tasks: Map<String, JSONObject>, allowed: Boolean, busy: Boolean, failed: Boolean, onBack: () -> Unit, onSave: (AdventureDraft) -> Unit) {
    var draft by remember(active.id, active.version) { mutableStateOf(active.draft) }
    var replacement by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onBack) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(24.dp).testTag("adventure-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.adventure_edit), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.adventure_estimate_hint), style = MaterialTheme.typography.bodySmall)
                draft.phases.forEachIndexed { index, phase ->
                    fun update(value: AdventurePhase) { draft = draft.copy(phases = draft.phases.toMutableList().apply { set(index, value) }) }
                    OutlinedTextField(phase.name, { update(phase.copy(name = it)) }, label = { Text(stringResource(R.string.adventure_phase_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("adventure-phase-name-$index"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..3).forEach { stars ->
                        FilterChip(phase.stars == stars, { update(phase.copy(stars = stars)) }, label = { Text(stars.toString()) },
                            modifier = Modifier.testTag("adventure-stars-$index-$stars")) } }
                    OutlinedTextField(phase.minutes.takeIf { it > 0 }?.toString().orEmpty(), { update(phase.copy(minutes = it.toIntOrNull() ?: 0)) },
                        label = { Text(stringResource(R.string.adventure_minutes)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text(tasks[phase.rootId]?.getString("title") ?: stringResource(R.string.adventure_source_unavailable))
                    OutlinedButton(onClick = { replacement = index }, modifier = Modifier.testTag("adventure-replace-$index")) { Text(stringResource(R.string.adventure_replace)) }
                    OutlinedButton(onClick = { draft = draft.copy(phases = draft.phases.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.testTag("adventure-remove-$index")) { Text(stringResource(R.string.adventure_remove)) }
                    HorizontalDivider()
                }
                val valid = runCatching { AdventureDraft.read(draft.json(), true) }.isSuccess
                if (!valid) Text(stringResource(R.string.adventure_edit_invalid), color = MaterialTheme.colorScheme.error)
                if (draft.phases.isEmpty()) Text(stringResource(R.string.adventure_no_roots))
                AdventureRequestFeedback(busy, failed)
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); onSave(draft) }, enabled = allowed && valid, modifier = Modifier.testTag("adventure-save")) { Text(stringResource(if (failed) R.string.adventure_retry else R.string.adventure_save)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.adventure_not_now)) }
            }
        }
    }
    replacement?.let { index -> AlertDialog(onDismissRequest = { replacement = null }, title = { Text(stringResource(R.string.adventure_replace)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val candidates = tasks.values.filter { AdventureSnapshot.available(it) && draft.phases.none { p -> p.rootId == it.getString("id") } }
            if (candidates.isEmpty()) Text(stringResource(R.string.adventure_no_replacements))
            candidates.forEach { task -> TextButton(onClick = {
                draft = draft.copy(phases = draft.phases.toMutableList().apply { set(index, get(index).copy(rootId = task.getString("id"))) }); replacement = null
            }) { Text(task.getString("title")) } }
        } }, confirmButton = { TextButton(onClick = { replacement = null }) { Text(stringResource(R.string.adventure_not_now)) } }) }
}

@Composable
private fun AdventureBow(id: String, acknowledge: suspend (String) -> Boolean) {
    val motion = LocalHouseholdMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val angle = remember(id) { Animatable(0f) }
    LaunchedEffect(id, motion.enabled) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            angle.snapTo(0f)
            if (acknowledge(id) && motion.enabled) { angle.animateTo(12f, tween(180)); angle.animateTo(0f, tween(180)) }
        }
    }
    Icon(painterResource(R.drawable.bun_do), null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp).graphicsLayer { rotationZ = angle.value })
}

@Composable
private fun AdventureRequestFeedback(busy: Boolean, failed: Boolean) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("adventure-dialog-busy"))
    if (failed) Text(stringResource(R.string.adventure_failed), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("adventure-dialog-error"))
}
