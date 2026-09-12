package fi.bundo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxDatabase
import fi.bundo.data.InboxRepository
import fi.bundo.data.InboxTask
import fi.bundo.ui.InboxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class InboxViewModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "viewmodel-${UUID.randomUUID()}.db"
    private val database = InboxDatabase.open(context, name)
    private val store = ViewModelStore()
    private lateinit var model: InboxViewModel

    private suspend fun start() {
        withContext(Dispatchers.Main) {
            model = InboxViewModel(InboxRepository(database), SavedStateHandle())
            store.put("test", model)
        }
        await { model.state.value.loaded }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    @After fun close() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        database.close()
        context.deleteDatabase(name)
        Unit
    }

    @Test fun retryDuringCommitCannotRecreateTheCommittedDraft() = runBlocking {
        start()
        withContext(Dispatchers.Main) { model.openEditor() }
        await { model.state.value.editor != null }
        withContext(Dispatchers.Main) { model.change("Retain exactly once", "") }
        await { model.state.value.draftSaved }
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_save BEFORE INSERT ON inbox_intents BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        withContext(Dispatchers.Main) { model.closeEditor(commit = true) }
        await { model.state.value.writeFailed }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_save")
        withContext(Dispatchers.Main) {
            model.closeEditor(commit = true)
            model.retry()
            model.retryDraft()
            model.closeEditor(commit = true)
        }
        await { model.state.value.editor == null }
        withContext(Dispatchers.Main) { model.openEditor() }
        await { model.state.value.editor != null }
        assertEquals("", model.state.value.editor!!.title)
        assertNull(database.inbox().draft("new"))
        assertEquals(1, database.inbox().intents().size)
    }

    @Test fun failedEditorOpenRetriesTheOpenRatherThanReloadingTheQueue() = runBlocking {
        start()
        withContext(Dispatchers.Main) { model.openEditor("restored") }
        await { model.state.value.writeFailed }
        database.inbox().insertTask(InboxTask("restored", "Recovered", "", "Recovered", "", 1, 1))
        withContext(Dispatchers.Main) { model.retry() }
        await { model.state.value.editor != null }
        assertEquals("Recovered", model.state.value.editor!!.title)
    }

    @Test fun aFailedAutosaveDoesNotUnlockAnAlreadyQueuedCommit() = runBlocking {
        start()
        withContext(Dispatchers.Main) { model.openEditor() }
        await { model.state.value.editor != null }
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_draft BEFORE INSERT ON editor_drafts BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        withContext(Dispatchers.Main) {
            model.change("Commit is the durability boundary", "")
            model.closeEditor(commit = true)
        }
        await { model.state.value.editor == null }
        assertEquals(1, database.inbox().intents().size)
        assertNull(database.inbox().draft("new"))
        assertEquals(false, model.state.value.writeFailed)
    }
}
