package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fi.bundo.R
import fi.bundo.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun SharedListsScreen(repository: SharedRepository, workspace: SharedWorkspace?, tasks: Map<String, JSONObject>,
    enabled: Boolean, onOpen: (String) -> Unit, onAction: (SharedTaskAction) -> Unit,
    sync: suspend (JSONObject?, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    var showCompleted by rememberSaveable(repository.scope) { mutableStateOf(false) }
    var views by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(true) }
    var discard by remember { mutableStateOf(false) }
    var loadingDraft by remember { mutableStateOf(true) }
    var starters by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf<Pair<SavedHouseholdList, String>?>(null) }
    val library = workspace?.listLibrary?.let(::JSONObject)
    val array = library?.optJSONArray("lists")
    val saved = (0 until (array?.length() ?: 0)).map { SavedHouseholdList.read(array!!.getJSONObject(it)) }.sortedByDescending { it.pinned }
    fun run(work: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? SyncFailure)?.code ?: "UNAVAILABLE" }
            finally { pending = repository.listDraft("pending") != null; busy = false }
        }
    }
    suspend fun online(command: JSONObject? = null, retry: Boolean = false) = sync(command, retry)
    fun refresh() {
        if (refreshing || busy) return
        refreshing = true; refreshFailed = false
        scope.launch {
            try { online() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { refreshFailed = true }
            finally { refreshing = false }
        }
    }
    fun change(value: String?) {
        preview = value
        scope.launch {
            try { repository.saveListDraft("preview", value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "UNAVAILABLE" }
        }
    }
    fun prepare(value: SavedHouseholdList, mode: String, standing: Boolean = false) {
        starters = false
        showPreview = true
        change(JSONObject().put("list", value.json()).put("mode", mode).put("standing", standing)
            .put("version", library?.getString("version") ?: "0")
            .put("selected", JSONArray(value.items.map { true })).toString())
    }
    LaunchedEffect(repository.scope) {
        preview = repository.listDraft("preview")
        pending = repository.listDraft("pending") != null
        loadingDraft = false
        refresh()
    }
    if (workspace?.blocked != null) {
        Text(stringResource(R.string.adventure_unavailable), Modifier.padding(24.dp))
        return
    }
    val currentPreview = preview
    if (currentPreview != null && showPreview) {
        ListPreview(currentPreview, enabled && !busy, error, pending, { run { online(retry = true) } }, ::change,
            { showPreview = false; error = null }, workspace?.listLibrary) { value, mode, standing, version ->
            run {
                repository.saveListDraft("preview", preview)
                if (mode == "save") {
                    val command = SharedLists.command(library, value.id, value).put("expectedVersion", version)
                    online(command)
                    repository.saveListDraft("preview", null); preview = null
                } else {
                    val id = repository.startList(value, standing)
                    preview = null
                    onOpen(id)
                }
            }
        }
        return
    }
    if (starters) {
        BackHandler { starters = false }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("list-types"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lists_choose_type), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            val names = listOf(R.string.lists_groceries, R.string.lists_cottage, R.string.lists_cleaning, R.string.lists_hosting, R.string.lists_seasonal, R.string.lists_blank)
            val contents = listOf(R.string.lists_groceries_items, R.string.lists_cottage_items, R.string.lists_cleaning_items, R.string.lists_hosting_items, R.string.lists_seasonal_items, R.string.lists_blank_items)
            val descriptions = listOf(R.string.lists_groceries_hint, R.string.lists_cottage_hint, R.string.lists_cleaning_hint,
                R.string.lists_hosting_hint, R.string.lists_seasonal_hint, R.string.lists_blank_hint)
            names.forEachIndexed { index, name ->
                val title = stringResource(name)
                val items = stringResource(contents[index]).split("\n").filter { it.isNotBlank() }.map { HouseholdListItem(it) }
                SettingsNavigationRow(title, { prepare(SavedHouseholdList(UUID.randomUUID().toString(), if (index == 5) "" else title, null, items), "start", index == 0) },
                    Modifier.testTag("list-type-$index"), value = stringResource(descriptions[index]), leading = {
                        Box(Modifier.testTag("list-type-icon-$index")) {
                            TaskCue(title, if (index == 0 || index == 3) "shop" else "storage",
                                when (index) { 3 -> Icons.Outlined.Person; 5 -> Icons.Outlined.Add; else -> null })
                        }
                    })
            }
        }
        return
    }
    val refreshLabel = stringResource(R.string.lists_refresh)
    RefreshPage(refreshLabel, refreshing, enabled && !busy, ::refresh, Modifier.testTag("lists-page")) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("lists-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RefreshHeading(stringResource(R.string.lists_title), refreshLabel, enabled && !busy && !refreshing, ::refresh)
        Text(stringResource(R.string.lists_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("lists-action-progress"))
        if (error != null) Text(stringResource(if (error == "LIST_CHANGED") R.string.lists_changed else R.string.lists_failed), color = MaterialTheme.colorScheme.error)
        if (pending) {
            Text(stringResource(R.string.lists_pending))
            OutlinedButton(onClick = { run { online(retry = true) } }, enabled = enabled && !busy) { Text(stringResource(R.string.lists_retry_save)) }
        }
        if (currentPreview != null) {
            SettingsNavigationRow(stringResource(R.string.resume_draft), { showPreview = true },
                Modifier.testTag("lists-resume"), enabled = !busy)
            TextButton(onClick = { discard = true }, enabled = !busy && !pending, modifier = Modifier.testTag("lists-discard")) {
                Text(stringResource(R.string.voice_discard_draft))
            }
        }
        Button(onClick = { starters = !starters }, enabled = enabled && !busy && !loadingDraft && currentPreview == null, modifier = Modifier.testTag("lists-new")) {
            Text(stringResource(R.string.lists_new))
        }
        val lifecycle = if (showCompleted) "COMPLETED" else "OPEN"
        val roots = tasks.values.filter {
            it.isNull("parentId") && it.isNull("deletion") &&
                (it.optBoolean("isChecklist") || !it.isNull("listKind")) && it.optString("lifecycle", "OPEN") == lifecycle
        }
            .sortedWith(compareByDescending<JSONObject> { it.optBoolean("listPinned") }.thenBy { it.getString("title") })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lists_yours), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            Box {
                TextButton(onClick = { views = true }, modifier = Modifier.testTag("lists-view")) {
                    Text(stringResource(if (showCompleted) R.string.lists_completed else R.string.queue_active))
                    Icon(Icons.Outlined.KeyboardArrowDown, null)
                }
                DropdownMenu(views, { views = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.queue_active)) }, modifier = Modifier.testTag("lists-active"), onClick = { showCompleted = false; views = false })
                    DropdownMenuItem(text = { Text(stringResource(R.string.lists_completed)) }, modifier = Modifier.testTag("lists-completed"), onClick = { showCompleted = true; views = false })
                }
            }
        }
        if (roots.isEmpty()) Text(stringResource(if (showCompleted) R.string.lists_completed_empty else R.string.lists_empty))
        roots.forEach { root ->
            var menu by remember(root.getString("id")) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val children = SharedChecklistActions.childIds(root).mapNotNull(tasks::get).filter { it.isNull("deletion") && it.optString("lifecycle") != "CANCELLED" }
                val progress = stringResource(R.string.checklist_progress, children.count { it.optString("lifecycle") == "COMPLETED" }, children.size)
                SettingsNavigationRow(root.getString("title"), { onOpen(root.getString("id")) }, Modifier.weight(1f),
                    value = listOfNotNull(progress, stringResource(R.string.lists_standing).takeIf { root.optString("listKind") == "STANDING" },
                        stringResource(R.string.lists_pinned).takeIf { root.optBoolean("listPinned") }).joinToString(" · "),
                    icon = if (root.optBoolean("listPinned")) Icons.Outlined.Star else null)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.lists_options, root.getString("title"))) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(if (root.optBoolean("listPinned")) R.string.lists_unpin else R.string.lists_pin)) }, enabled = enabled,
                            onClick = { menu = false; onAction(SharedTaskAction("SetListPinned", root.toString())) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_copy)) }, enabled = enabled && currentPreview == null,
                            onClick = { menu = false; prepare(SavedHouseholdList.fromTask(root, tasks), "start") })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_save)) }, enabled = enabled && !pending && currentPreview == null,
                            onClick = { menu = false; prepare(SavedHouseholdList.fromTask(root, tasks), "save") })
                    }
                }
            }
            HorizontalDivider()
        }
        Text(stringResource(R.string.lists_saved), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (refreshing && workspace?.listLibrary == null) Text(stringResource(R.string.lists_loading),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("lists-initial-loading"))
        else if (saved.isEmpty() && workspace?.listLibrary != null) Text(stringResource(R.string.lists_saved_empty))
        if (refreshFailed) Text(stringResource(if (workspace?.listLibrary == null) R.string.lists_load_failed else R.string.lists_refresh_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lists-refresh-error"))
        saved.forEach { value ->
            var menu by remember(value.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsNavigationRow(value.title, { prepare(value.copy(id = UUID.randomUUID().toString()), "start") }, Modifier.weight(1f),
                    value = stringResource(R.string.lists_pinned).takeIf { value.pinned },
                    icon = if (value.pinned) Icons.Outlined.Star else null, enabled = enabled && currentPreview == null)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.lists_options, value.title)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_edit_saved)) }, enabled = enabled && !busy && !pending && currentPreview == null,
                            onClick = { menu = false; prepare(value, "save") })
                        DropdownMenuItem(text = { Text(stringResource(if (value.pinned) R.string.lists_unpin else R.string.lists_pin)) }, enabled = enabled && !busy && !pending,
                            onClick = { menu = false; run { online(SharedLists.command(library, value.id, value.copy(pinned = !value.pinned))) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.lists_delete_saved)) }, enabled = enabled && !busy && !pending,
                            onClick = { menu = false; deleted = value to (library?.getString("version") ?: "0") })
                    }
                }
            }
        }
        if (refreshFailed) TextButton(onClick = ::refresh, enabled = enabled && !busy && !refreshing,
            modifier = Modifier.testTag("lists-refresh")) { Text(refreshLabel) }
    }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.voice_discard_draft)) },
        text = { Text(stringResource(R.string.voice_discard_confirm)) },
        confirmButton = { TextButton(onClick = { discard = false; change(null) }, modifier = Modifier.testTag("lists-confirm-discard")) {
            Text(stringResource(R.string.voice_discard_draft))
        } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.back)) } })
    deleted?.let { (value, version) -> AlertDialog(onDismissRequest = { deleted = null },
        title = { Text(stringResource(R.string.lists_delete_saved)) }, text = { Text(stringResource(R.string.lists_delete_confirm, value.title)) },
        confirmButton = { TextButton(onClick = { deleted = null; run { online(SharedLists.command(library, value.id, null).put("expectedVersion", version)) } }, enabled = enabled && !busy) { Text(stringResource(R.string.lists_delete_saved)) } },
        dismissButton = { TextButton(onClick = { deleted = null }) { Text(stringResource(R.string.back)) } }) }
}

@Composable
internal fun ListPreview(json: String, enabled: Boolean, error: String?, pending: Boolean, onRetry: () -> Unit, onChange: (String) -> Unit, onBack: () -> Unit,
    latestLibrary: String? = null,
    onCommit: (SavedHouseholdList, String, Boolean, String) -> Unit) {
    val draft = JSONObject(json)
    val value = SavedHouseholdList.read(draft.getJSONObject("list"))
    val selected = draft.getJSONArray("selected").let { a -> (0 until a.length()).map(a::getBoolean) }
    val mode = draft.getString("mode")
    val library = latestLibrary?.let(::JSONObject)
    val latestVersion = library?.getString("version")
    val stale = mode == "save" && latestVersion != null && latestVersion != draft.getString("version")
    var review by remember(latestVersion) { mutableStateOf(false) }
    fun update(list: SavedHouseholdList = value, checks: List<Boolean> = selected) = onChange(draft.put("list", list.json()).put("selected", JSONArray(checks)).toString())
    BackHandler(enabled = enabled, onBack = onBack)
    var addition by remember { mutableStateOf("") }
    val chosen = value.copy(items = value.items.filterIndexed { index, _ -> selected[index] })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxSize().imePadding().testTag("list-preview")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = enabled) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(if (mode == "save") R.string.lists_save else R.string.lists_preview), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (stale) {
                Text(stringResource(R.string.lists_changed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { review = true }, enabled = enabled && !pending, modifier = Modifier.testTag("list-review")) {
                    Text(stringResource(R.string.lists_review))
                }
            } else if (error != null && error != "LIST_CHANGED") Text(stringResource(R.string.lists_failed), color = MaterialTheme.colorScheme.error)
            if (pending) OutlinedButton(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.lists_retry_save)) }
            if (mode != "save") Text(stringResource(R.string.lists_copy_hint))
            OutlinedTextField(value.title, { if (InboxLimits.length(it) <= 160) update(value.copy(title = it)) }, enabled = enabled,
                label = { Text(stringResource(R.string.lists_name)) }, modifier = Modifier.fillMaxWidth().testTag("list-name"))
            OutlinedTextField(value.notes.orEmpty(), { if (InboxLimits.length(it) <= 4000) update(value.copy(notes = it)) }, enabled = enabled,
                label = { Text(stringResource(R.string.lists_notes)) }, modifier = Modifier.fillMaxWidth())
            if (mode == "start") SettingToggleRow(stringResource(R.string.lists_keep_open), draft.optBoolean("standing"),
                { onChange(draft.put("standing", it).toString()) }, enabled = enabled)
            value.items.forEachIndexed { index, item ->
                fun move(to: Int) {
                    val items = value.items.toMutableList()
                    items.add(to, items.removeAt(index))
                    val checks = selected.toMutableList()
                    checks.add(to, checks.removeAt(index))
                    update(value.copy(items = items), checks)
                }
                val toggleLabel = stringResource(R.string.checklist_toggle, item.title)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Checkbox(selected[index], { update(checks = selected.toMutableList().also { list -> list[index] = it }) }, enabled = enabled, modifier = Modifier.semantics { contentDescription = toggleLabel })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(item.title, { if (InboxLimits.length(it) <= 160) update(value.copy(items = value.items.toMutableList().also { list -> list[index] = item.copy(title = it) })) },
                            enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lists_item)) })
                        OutlinedTextField(item.notes.orEmpty(), { if (InboxLimits.length(it) <= 4000) update(value.copy(items = value.items.toMutableList().also { list -> list[index] = item.copy(notes = it) })) },
                            enabled = enabled, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lists_item_notes)) })
                        Row {
                            IconButton(onClick = { move(index - 1) }, enabled = enabled && index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.lists_up, item.title)) }
                            IconButton(onClick = { move(index + 1) }, enabled = enabled && index < value.items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.lists_down, item.title)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(addition, { if (InboxLimits.length(it) <= 160) addition = it }, enabled = enabled && value.items.size < SharedChecklistActions.MAX_ITEMS,
                    label = { Text(stringResource(R.string.lists_item)) }, modifier = Modifier.weight(1f).testTag("list-add-text"))
                TextButton(onClick = { update(value.copy(items = value.items + HouseholdListItem(addition.trim())), selected + true); addition = "" },
                    enabled = enabled && addition.isNotBlank() && value.items.size < SharedChecklistActions.MAX_ITEMS, modifier = Modifier.widthIn(min = 64.dp).testTag("list-add")) { Text(stringResource(R.string.lists_add), softWrap = false) }
            }
            if (chosen.json().toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) Text(stringResource(R.string.lists_too_large), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.lists_limit, SharedChecklistActions.MAX_ITEMS), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = { onCommit(chosen, mode, draft.optBoolean("standing"), draft.getString("version")) }, enabled = enabled && chosen.valid() && !stale && (mode != "save" || !pending),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("list-commit")) { Text(stringResource(if (mode == "save") R.string.lists_save else R.string.lists_start)) }
    }
}
    if (review && stale) {
        val lists = library!!.getJSONArray("lists")
        val current = (0 until lists.length()).map { SavedHouseholdList.read(lists.getJSONObject(it)) }.find { it.id == value.id }
        AlertDialog(onDismissRequest = { review = false },
            title = { Text(stringResource(R.string.lists_review)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (current == null) R.string.lists_review_missing else R.string.lists_review_hint))
                if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium)
                    if (current.pinned) Text(stringResource(R.string.lists_pinned))
                    current.notes?.let { Text(it) }
                    current.items.forEach { item ->
                        Text(item.title)
                        item.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = {
                // Consent applies only to the content/version displayed in this review.
                onChange(draft.put("version", latestVersion).put("list",
                    (if (current == null) value.copy(id = UUID.randomUUID().toString()) else value).json()).toString())
                review = false
            }, enabled = enabled && !pending, modifier = Modifier.testTag("list-confirm-review")) {
                Text(stringResource(if (current == null) R.string.lists_review_new else R.string.lists_review_keep))
            } },
            dismissButton = { TextButton(onClick = { review = false }) { Text(stringResource(R.string.back)) } })
    }
}
