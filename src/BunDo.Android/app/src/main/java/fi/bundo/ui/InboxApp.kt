package fi.bundo.ui

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import fi.bundo.R
import fi.bundo.data.InboxLimits
import fi.bundo.data.InboxRepository
import fi.bundo.data.InboxTask
import fi.bundo.speech.VoiceController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxApp(
    state: InboxUiState,
    model: InboxViewModel,
    appearance: String,
    onAppearance: (String) -> Unit,
    voice: VoiceController? = null,
    onAccount: (() -> Unit)? = null,
    queueTitle: String? = null,
    queueHeader: (@Composable () -> Unit)? = null,
    canEdit: Boolean = true,
    queueTasks: List<InboxTask>? = null,
    rowSummary: (@Composable (String) -> Unit)? = null,
    taskControls: (@Composable (String, (String) -> Unit) -> Unit)? = null,
    canEditTask: (String) -> Boolean = { true },
    snackbarHost: @Composable () -> Unit = {},
) {
    var settings by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val queueScroll = rememberLazyListState()
    val selected = state.tasks.find { it.id == selectedId }
    val editor = state.editor
    val back: () -> Unit = {
        when {
            editor != null -> model.closeEditor(commit = false)
            settings -> settings = false
            else -> selectedId = null
        }
    }
    BackHandler(enabled = editor != null || settings || selectedId != null, onBack = back)
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val wide = maxWidth >= 840.dp
        val short = maxHeight < 480.dp
        val gutter = if (maxWidth < 600.dp) 16.dp else 24.dp
        Scaffold(
            snackbarHost = snackbarHost,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (editor == null && !settings && (selected == null || wide) && queueTitle != null) queueTitle else stringResource(
                                when {
                                    editor != null -> if (editor.key == InboxRepository.NEW_DRAFT) R.string.new_task else R.string.edit_task
                                    settings -> R.string.settings
                                    selected != null && !wide -> R.string.task_detail
                                    else -> R.string.inbox
                                },
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (editor != null || settings || selectedId != null) {
                            IconButton(onClick = back, enabled = !state.working, modifier = Modifier.testTag("back")) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (onAccount != null && editor == null) {
                            TextButton(onClick = onAccount, modifier = Modifier.testTag("account")) {
                                Text(stringResource(R.string.account_title))
                            }
                        }
                        if (editor != null) {
                            TextButton(
                                onClick = { model.closeEditor(commit = true) },
                                enabled = canEdit && !state.working && InboxLimits.valid(editor.title, editor.description),
                                modifier = Modifier.testTag("save").padding(end = 8.dp).heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.save)) }
                        } else if (!settings) {
                            IconButton(onClick = { settings = true }, enabled = !state.working, modifier = Modifier.testTag("settings")) {
                                Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                            }
                        }
                    },
                )
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets).imePadding()) {
                when {
                    editor != null -> Editor(state, model, Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxWidth())
                    settings -> Settings(appearance, onAppearance, Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxWidth(), queueTitle == null)
                    selected != null && !wide -> TaskDetail(
                        selected, { model.openEditor(selected.id) }, state, model::retry, Modifier.fillMaxSize(), queueTitle == null,
                        taskControls, canEdit && canEditTask(selected.id), { selectedId = it },
                    )
                    else -> Row(Modifier.fillMaxSize()) {
                        Queue(
                            state = if (queueTasks == null) state else state.copy(tasks = queueTasks),
                            onOpen = { selectedId = it },
                            onType = { model.openEditor() },
                            onVoice = voice?.let { { voice.acknowledgeSaved(); showVoice = true } },
                            onRetry = model::retry,
                            scroll = queueScroll,
                            gutter = gutter,
                            compactNotice = short,
                            header = queueHeader,
                            canEdit = canEdit,
                            summary = rowSummary,
                            modifier = if (wide && selected != null) Modifier.width(360.dp) else Modifier.weight(1f),
                        )
                        if (wide && selected != null) {
                            TaskDetail(selected, { model.openEditor(selected.id) }, state, model::retry, Modifier.weight(1f), queueTitle == null, taskControls, canEdit && canEditTask(selected.id), { selectedId = it })
                        }
                    }
                }
            }
        }
        if (showVoice && voice != null) {
            VoiceSheet(
                voice,
                onDismiss = { showVoice = false },
                onType = { showVoice = false; model.openEditor() },
                onSaved = { showVoice = false; selectedId = it },
            )
        }
    }
}

@Composable
private fun Queue(
    state: InboxUiState,
    onOpen: (String) -> Unit,
    onType: () -> Unit,
    onVoice: (() -> Unit)?,
    onRetry: () -> Unit,
    scroll: LazyListState,
    gutter: androidx.compose.ui.unit.Dp,
    compactNotice: Boolean,
    header: (@Composable () -> Unit)?,
    canEdit: Boolean,
    summary: (@Composable (String) -> Unit)?,
    modifier: Modifier,
) {
    val hasDraft = state.drafts.any {
        it.key == InboxRepository.NEW_DRAFT && (it.title.isNotEmpty() || it.description.isNotEmpty())
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).testTag("queue"),
            state = scroll,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                if (header != null) header() else Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) {
                        Text(stringResource(R.string.local_only), style = MaterialTheme.typography.labelLarge)
                        // Settings keeps the full explanation available on short screens.
                        if (!compactNotice) {
                            Text(
                                stringResource(R.string.local_explanation),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            if (state.savedPlacement != null) item {
                Text(stringResource(if (state.savedPlacement == "EXPEDITED") R.string.detail_saved_priority else R.string.detail_saved_append),
                    Modifier.padding(horizontal = gutter, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.readFailed || state.writeFailed) {
                item { ErrorNotice(if (state.readFailed) R.string.read_failed else R.string.open_failed, onRetry) }
            } else if (!state.loaded) {
                item {
                    Text(stringResource(R.string.loading), Modifier.padding(gutter))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (state.tasks.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 32.dp)) {
                        Icon(
                            painterResource(R.drawable.bun_do),
                            contentDescription = null,
                            tint = if (MaterialTheme.colorScheme.surface == Color(0xFF111511)) Color(0xFFF8F9F4) else Color(0xFF245B48),
                            modifier = Modifier.size(80.dp),
                        )
                        Text(
                            stringResource(R.string.empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 24.dp).semantics { heading() },
                        )
                        Text(
                            stringResource(R.string.empty_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                item {
                    Text(
                        pluralStringResource(R.plurals.task_count, state.tasks.size, state.tasks.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = gutter, vertical = 16.dp).semantics { heading() },
                    )
                }
                items(state.tasks, key = { it.id }) { task ->
                    val draft = state.drafts.any { it.key == task.id }
                    val clickLabel = stringResource(R.string.task_row_action)
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(role = Role.Button, onClickLabel = clickLabel) { onOpen(task.id) }
                            .padding(horizontal = gutter, vertical = 12.dp)
                            .heightIn(min = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        summary?.invoke(task.id)
                        if (task.description.isNotEmpty()) {
                            Text(task.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (draft) {
                            Text(stringResource(R.string.draft_available), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = gutter), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        Row(Modifier.padding(gutter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onType,
                enabled = canEdit && state.loaded && !state.working && !state.readFailed,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("capture"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (hasDraft) R.string.resume_draft else R.string.type_task))
            }
            if (onVoice != null) {
                FloatingActionButton(onClick = onVoice, modifier = Modifier.testTag("voice")) {
                    Icon(painterResource(R.drawable.microphone), stringResource(R.string.voice_capture), Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Editor(state: InboxUiState, model: InboxViewModel, modifier: Modifier) {
    val draft = state.editor ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(draft.key) { focus.requestFocus() }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.writeFailed) ErrorNotice(R.string.write_failed, model::retry)
        OutlinedTextField(
            value = draft.title,
            onValueChange = { model.change(it, draft.description) },
            label = { Text(stringResource(R.string.task_title)) },
            minLines = 2,
            enabled = !state.working,
            isError = InboxLimits.length(draft.title) > InboxLimits.TITLE,
            supportingText = { FieldCount(draft.title, InboxLimits.TITLE) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("title"),
        )
        OutlinedTextField(
            value = draft.description,
            onValueChange = { model.change(draft.title, it) },
            label = { Text(stringResource(R.string.description_optional)) },
            minLines = 3,
            enabled = !state.working,
            isError = InboxLimits.length(draft.description) > InboxLimits.DESCRIPTION,
            supportingText = { FieldCount(draft.description, InboxLimits.DESCRIPTION) },
            modifier = Modifier.fillMaxWidth().testTag("description"),
        )
        draft.details?.let { TaskDateFields(it, draft.key == fi.bundo.data.InboxRepository.NEW_DRAFT, !state.working, model::changeDetails) }
        if (draft.title.isBlank()) Text(stringResource(R.string.title_required), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(if (state.draftSaved) R.string.draft_saved else R.string.saving),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(if (state.draftSaved) "draft-saved" else "draft-saving"),
        )
        Text(
            stringResource(R.string.draft_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FieldCount(value: String, limit: Int) {
    val length = InboxLimits.length(value)
    Text(
        if (length > limit) stringResource(R.string.too_long, limit)
        else stringResource(R.string.character_count, length, limit),
    )
}

@Composable
private fun TaskDetail(
    task: InboxTask, onEdit: () -> Unit, state: InboxUiState, onRetry: () -> Unit, modifier: Modifier, localOnly: Boolean,
    controls: (@Composable (String, (String) -> Unit) -> Unit)? = null,
    editable: Boolean = true,
    onOpenTask: (String) -> Unit = {},
) {
    var original by rememberSaveable(task.id) { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.writeFailed) ErrorNotice(R.string.open_failed, onRetry)
        Text(task.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        controls?.invoke(task.id, onOpenTask)
        if (localOnly) Text(stringResource(R.string.local_only), style = MaterialTheme.typography.labelLarge)
        Text(task.description.ifEmpty { stringResource(R.string.no_description) }, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onEdit, enabled = editable && !state.working, modifier = Modifier.heightIn(min = 48.dp).testTag("edit")) {
            Text(stringResource(R.string.edit_task))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { original = !original }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(if (original) R.string.hide_original else R.string.show_original))
        }
        if (original) {
            Text(stringResource(R.string.original_text), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(task.originalTitle, style = MaterialTheme.typography.bodyLarge)
            if (task.originalDescription.isNotEmpty()) Text(task.originalDescription, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ErrorNotice(message: Int, retry: () -> Unit) {
    Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun Settings(appearance: String, onAppearance: (String) -> Unit, modifier: Modifier, localOnly: Boolean) {
    val language = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Choices(
            appearance,
            listOf("system" to R.string.system_default, "light" to R.string.light, "dark" to R.string.dark),
            onAppearance,
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.language_hint), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Choices(
            language,
            listOf("" to R.string.system_default, "fi" to R.string.finnish, "en" to R.string.english),
        ) { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) }
        if (localOnly) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.local_only), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.local_explanation), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Choices(selected: String, options: List<Pair<String, Int>>, onSelect: (String) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = selected == key, onClick = { onSelect(key) }, role = Role.RadioButton)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == key, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
