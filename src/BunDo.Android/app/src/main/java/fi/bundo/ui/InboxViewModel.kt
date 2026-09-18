package fi.bundo.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.bundo.data.EditorDraft
import fi.bundo.data.InboxLimits
import fi.bundo.data.InboxRepository
import fi.bundo.data.TaskEditorRepository
import fi.bundo.data.InboxTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InboxUiState(
    val loaded: Boolean = false,
    val tasks: List<InboxTask> = emptyList(),
    val drafts: List<EditorDraft> = emptyList(),
    val editor: EditorDraft? = null,
    val draftSaved: Boolean = false,
    val working: Boolean = false,
    val writeFailed: Boolean = false,
    val readFailed: Boolean = false,
    val savedPlacement: String? = null,
)

class InboxViewModel(private val repository: TaskEditorRepository, private val savedState: SavedStateHandle) :
    ViewModel() {
    private val mutableState = MutableStateFlow(InboxUiState())
    val state = mutableState.asStateFlow()
    fun hide() {
        reads?.cancel()
        mutableState.value = InboxUiState()
    }
    private class Write(val perform: suspend () -> Unit, val failed: () -> Unit)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private var reads: Job? = null
    private var retryWrite: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            for (write in writes) {
                try {
                    write.perform()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Do not log exceptions that might contain household text or SQL bindings.
                    write.failed()
                }
            }
        }
        load()
        savedState.get<String>("editorKey")?.let(::openEditor)
    }

    private fun load() {
        reads?.cancel()
        reads = viewModelScope.launch {
            try {
                combine(repository.tasks, repository.drafts) { tasks, drafts -> tasks to drafts }
                    .collect { (tasks, drafts) ->
                    mutableState.update {
                        it.copy(tasks = tasks, drafts = drafts, loaded = true, readFailed = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(readFailed = true) }
            }
        }
    }

    fun retry() {
        if (state.value.working) return
        if (state.value.writeFailed) retryWrite?.invoke()
        else if (state.value.readFailed) load()
    }

    fun openEditor(key: String = InboxRepository.NEW_DRAFT) {
        if (state.value.working) return
        mutableState.update { it.copy(working = true, writeFailed = false, savedPlacement = null) }
        writes.trySend(Write(perform = {
            val draft = repository.draft(key)
            savedState["editorKey"] = key
            mutableState.update { it.copy(editor = draft, draftSaved = true, working = false) }
        }, failed = {
            retryWrite = { openEditor(key) }
            mutableState.update { it.copy(working = false, writeFailed = true) }
        }))
    }

    fun change(title: String, description: String) {
        if (state.value.working) return
        val draft = state.value.editor?.copy(title = title, description = description) ?: return
        mutableState.update { it.copy(editor = draft, draftSaved = false, writeFailed = false) }
        persist(draft)
    }

    fun changeDetails(details: String) {
        if (state.value.working) return
        val draft = state.value.editor?.copy(details = details) ?: return
        mutableState.update { it.copy(editor = draft, draftSaved = false, writeFailed = false) }
        persist(draft)
    }

    private fun persist(draft: EditorDraft) {
        writes.trySend(Write(perform = {
            repository.saveDraft(draft)
            mutableState.update {
                if (it.editor == draft) it.copy(draftSaved = true, writeFailed = false) else it
            }
        }, failed = {
            // An older autosave does not own a queued Save/Back action's busy state.
            if (state.value.editor == draft && !state.value.working) {
                retryWrite = ::retryDraft
                mutableState.update { it.copy(writeFailed = true, draftSaved = false) }
            }
        }))
    }

    fun retryDraft() {
        if (state.value.working) return
        state.value.editor?.let(::persist)
    }

    fun closeEditor(commit: Boolean, onClosed: (() -> Unit)? = null, onSaved: ((String) -> Unit)? = null) {
        val draft = state.value.editor ?: return
        if (state.value.working || (commit && (!InboxLimits.valid(draft.title, draft.description) || !fi.bundo.data.SharedTaskDetails.valid(draft.details)))) return
        mutableState.update { it.copy(working = true, writeFailed = false) }
        writes.trySend(Write(perform = {
            // Explicit Back is a durability boundary, just like Save.
            val id = if (commit) repository.commit(draft) else { repository.saveDraft(draft); null }
            val placement = if (id != null && draft.key == InboxRepository.NEW_DRAFT) repository.placement(id) else null
            savedState.remove<String>("editorKey")
            mutableState.update {
                it.copy(editor = null, working = false, draftSaved = true, writeFailed = false, savedPlacement = placement)
            }
            if (id != null) onSaved?.invoke(id)
            onClosed?.invoke()
        }, failed = {
            retryWrite = { closeEditor(commit, onClosed, onSaved) }
            mutableState.update { it.copy(working = false, writeFailed = true, draftSaved = false) }
        }))
    }
}
