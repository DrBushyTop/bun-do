package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import fi.bundo.R
import fi.bundo.data.InboxTask
import fi.bundo.data.SharedProtocol
import fi.bundo.data.nullableString
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Move buttons commit immediately. Only the current drag is a cancellable preview. */
@Composable
internal fun SharedQueue(
    rows: List<InboxTask>, ordered: List<JSONObject>, membership: JSONObject?, enabled: Boolean,
    onOpen: (String) -> Unit, onAction: (SharedTaskAction) -> Unit, onFullQueue: () -> Unit,
    toolbar: @Composable () -> Unit,
    notices: @Composable () -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val byId = ordered.associateBy { it.getString("id") }
    val full = ordered.filter { it.isNull("parentId") && it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" }
    val fullIds = full.map { it.getString("id") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    var reorder by rememberSaveable { mutableStateOf(false) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var basis by remember { mutableStateOf<List<String>>(emptyList()) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var displayed by remember { mutableStateOf("") }
    var dragY by remember { mutableFloatStateOf(0f) }
    var announcement by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<SharedTaskAction?>(null) }
    fun cancelDrag() { dragId = null; preview = null; displayed = "" }
    fun finishMode() { cancelDrag(); reorder = false }
    BackHandler(reorder) { if (dragId != null) cancelDrag() else finishMode() }
    LaunchedEffect(fullIds) {
        if (dragId != null && fullIds != basis) {
            cancelDrag()
            announcement = resources.getString(R.string.reorder_changed)
        }
    }
    val ids = preview ?: fullIds
    val shown = if (reorder) ids.mapNotNull(byId::get).map(SharedProtocol::inbox) else rows.filter { row ->
        val task = byId[row.id] ?: return@filter false
        val leaves = if (task.optBoolean("isChecklist")) fi.bundo.data.SharedChecklistActions.childIds(task)
            .mapNotNull(byId::get).filter { it.isNull("deletion") && it.optString("lifecycle", "OPEN") == "OPEN" }
            else listOf(task)
        filter == "all" || leaves.any {
            val claimant = taskClaimant(it, membership)
            filter == "unclaimed" && claimant == null || filter == "mine" && claimant != null && claimant == membership?.optString("me")
        }
    }
    fun move(id: String, delta: Int) {
        if (!enabled || dragId != null) return
        val index = fullIds.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in fullIds.indices) return
        val next = fullIds.toMutableList().apply { removeAt(index); add(target, id) }
        onAction(SharedTaskAction("MoveTask", checkNotNull(byId[id]).toString(),
            after = next.getOrNull(target - 1), before = next.getOrNull(target + 1), expectedOrder = fullIds))
    }
    val earlier = stringResource(R.string.task_earlier)
    val later = stringResource(R.string.task_later)
    val cancel = stringResource(R.string.reorder_cancel_drag)
    val complete = stringResource(R.string.task_complete)
    fun complete(task: JSONObject) {
        val other = taskClaimant(task, membership)?.takeIf { it != membership?.optString("me") }
        val action = SharedTaskAction("CompleteTask", task.toString(), other)
        if (other != null) confirmation = action else onAction(action)
    }
    Column(Modifier.fillMaxSize().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && reorder) {
            if (dragId != null) cancelDrag() else finishMode()
            true
        } else false
    }) {
        if (!reorder) HouseholdWorld()
        // Outside the scrolling list so controls remain reachable during a long reorder.
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.task_count, shown.size, shown.size),
                    Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.labelLarge)
                Box {
                    TextButton(onClick = { menu = true }, enabled = !reorder, modifier = Modifier.testTag("queue-filter")) {
                        Text(stringResource(when (filter) { "mine" -> R.string.filter_mine; "unclaimed" -> R.string.filter_unclaimed; else -> R.string.filter_all }))
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        listOf("all" to R.string.filter_all, "unclaimed" to R.string.filter_unclaimed, "mine" to R.string.filter_mine).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.testTag("filter-$value"),
                                onClick = { filter = value; menu = false })
                        }
                    }
                }
                if (!reorder) IconButton(onClick = { filter = "all"; onFullQueue(); reorder = true },
                    enabled = enabled && full.size > 1, modifier = Modifier.testTag("queue-reorder")) {
                    Icon(painterResource(R.drawable.reorder), stringResource(R.string.reorder_queue))
                } else TextButton(onClick = ::finishMode, modifier = Modifier.testTag("reorder-done")) { Text(stringResource(R.string.reorder_done)) }
            }
            if (reorder) {
                Text(stringResource(R.string.reorder_help), style = MaterialTheme.typography.bodySmall)
                if (dragId != null) TextButton(onClick = ::cancelDrag, modifier = Modifier.testTag("reorder-cancel")) { Text(cancel) }
            } else toolbar()
            if (announcement.isNotEmpty()) Text(announcement,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("queue"),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item { notices() }
            if (shown.isEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TaskCue("")
                    Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.empty_body))
                }
            }
            itemsIndexed(shown, key = { _, it -> it.id }) { index, task ->
                val state = checkNotNull(byId[task.id])
                val position = stringResource(R.string.queue_position, index + 1, shown.size)
                val canComplete = enabled && !reorder && membership?.nullableString("me") != null &&
                    !state.optBoolean("isChecklist") && state.isNull("deletion") && state.optString("lifecycle", "OPEN") == "OPEN" &&
                    !fi.bundo.data.SharedChecklistActions.snoozed(state, byId)
                val currentComplete by rememberUpdatedState { if (canComplete) complete(state) }
                Column(Modifier.fillMaxWidth().testTag("queue-row-${task.id}")
                    .then(if (!canComplete) Modifier else Modifier
                        .semantics { customActions = listOf(CustomAccessibilityAction(complete) { currentComplete(); true }) }
                        .pointerInput(task.id, canComplete) {
                            var distance = 0f
                            detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f },
                                onDragEnd = { if (distance > size.width * .4f) currentComplete(); distance = 0f },
                                onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
                        })) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (reorder) {
                            val currentStart by rememberUpdatedState {
                                if (enabled) {
                                    basis = fullIds; preview = fullIds; dragId = task.id; displayed = state.toString()
                                    val info = list.layoutInfo.visibleItemsInfo.find { it.key == task.id }
                                    dragY = info?.let { it.offset + it.size / 2f } ?: 0f
                                }
                            }
                            val currentDrag by rememberUpdatedState { dy: Float ->
                                if (dragId == task.id) {
                                    dragY += dy
                                    val info = list.layoutInfo
                                    val target = info.visibleItemsInfo.find { it.key in ids && dragY >= it.offset && dragY <= it.offset + it.size }
                                    val next = (preview ?: basis).toMutableList()
                                    val from = next.indexOf(task.id)
                                    val to = next.indexOf(target?.key)
                                    if (from >= 0 && to >= 0 && from != to) {
                                        next.removeAt(from); next.add(to, task.id); preview = next
                                    }
                                    val edge = 64f
                                    val scroll = when { dragY > info.viewportEndOffset - edge -> 18f
                                        dragY < info.viewportStartOffset + edge -> -18f; else -> 0f }
                                    if (scroll != 0f) scope.launch { list.scrollBy(scroll) }
                                }
                            }
                            val currentEnd by rememberUpdatedState {
                                if (dragId == task.id) {
                                    val next = preview ?: basis
                                    val target = next.indexOf(task.id)
                                    if (next != basis && target >= 0) onAction(SharedTaskAction("MoveTask", displayed,
                                        after = next.getOrNull(target - 1), before = next.getOrNull(target + 1), expectedOrder = basis))
                                    cancelDrag()
                                }
                            }
                            Box(Modifier.size(48.dp).testTag("reorder-handle-${task.id}")
                                .semantics {
                                    contentDescription = task.title
                                    stateDescription = position
                                    customActions = buildList {
                                        if (enabled && dragId == null) {
                                            if (index > 0) add(CustomAccessibilityAction(earlier) { move(task.id, -1); true })
                                            if (index < shown.lastIndex) add(CustomAccessibilityAction(later) { move(task.id, 1); true })
                                        }
                                        if (dragId != null) add(CustomAccessibilityAction(cancel) { cancelDrag(); true })
                                    }
                                }.onPreviewKeyEvent {
                                    if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                                        Key.DirectionUp -> { move(task.id, -1); true }
                                        Key.DirectionDown -> { move(task.id, 1); true }
                                        else -> false
                                    }
                                }.focusable()
                                .pointerInput(task.id, enabled) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { currentStart() }, onDragCancel = ::cancelDrag,
                                        onDragEnd = { currentEnd() },
                                        onDrag = { change, amount -> change.consume(); currentDrag(amount.y) })
                                }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.drag_handle), null, tint = MaterialTheme.colorScheme.primary)
                            }
                        } else TaskCue(task.title)
                        TextButton(onClick = { onOpen(task.id) }, enabled = !reorder,
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(task.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (!reorder) {
                                    SharedTaskSummary(state, membership)
                                    ChecklistProgress(state, byId)
                                } else Text(position, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (canComplete) IconButton(onClick = { currentComplete() }, modifier = Modifier.testTag("queue-complete-${task.id}")) {
                            Icon(painterResource(R.drawable.action_check), complete)
                        }
                    }
                    if (reorder) Row(Modifier.padding(start = 64.dp, end = 16.dp)) {
                        TextButton(onClick = { move(task.id, -1) }, enabled = enabled && index > 0 && dragId == null,
                            modifier = Modifier.testTag("reorder-earlier-${task.id}")) { Text(earlier) }
                        TextButton(onClick = { move(task.id, 1) }, enabled = enabled && index < shown.lastIndex && dragId == null,
                            modifier = Modifier.testTag("reorder-later-${task.id}")) { Text(later) }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(complete) },
            text = { Text(stringResource(R.string.task_confirm_other, memberName(membership, action.confirmedClaimant))) },
            confirmButton = { TextButton(enabled = enabled, modifier = Modifier.testTag("queue-confirm-complete"), onClick = {
                confirmation = null; onAction(action)
            }) { Text(complete) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.back)) } })
    }
}
