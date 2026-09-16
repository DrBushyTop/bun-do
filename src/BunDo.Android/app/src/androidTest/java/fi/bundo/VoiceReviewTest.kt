package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.speech.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private inner class Fixture : AutoCloseable {
        val name = "voice-review-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val db = InboxDatabase.open(context, name)
        val lease = DataLease()
        val store = RecordingStore(db, File(root, "audio"), lease)
        val workspace = SharedWorkspace("home", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Home",
            membership = JSONObject().put("me", UUID.randomUUID().toString()).toString())
        var controller: VoiceController? = null
        suspend fun recording(keep: Boolean = false, shared: Boolean = false): String {
            if (shared) db.shared().saveWorkspace(workspace)
            return store.begin(target = if (shared) VoiceTarget(workspace.scope) else null,
                keepAudio = keep, reviewRequired = true).id.also { store.output(it).use { out -> out.write(byteArrayOf(0, 1)) } }
        }
        fun start(analyze: (suspend (String) -> VoiceDraft)? = null, text: String = "Shopping list: milk, 6 eggs, rye bread"): VoiceController {
            instrumentation.runOnMainSync {
                controller = VoiceController(context, store, ModelInstaller(File(root, "models"), loadSpeechManifest(context)),
                    online = { text }, connected = { true }, analyze = analyze)
            }
            await { controller!!.state.value.loaded }
            instrumentation.runOnMainSync { controller!!.configure(localOnly = false, keepAudio = false, analyze = true) }
            return controller!!
        }
        override fun close() {
            instrumentation.runOnMainSync { controller?.configure(localOnly = false, keepAudio = false, analyze = true); controller?.close() }
            db.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }
    @Test fun longVoiceDraftExportsAndImportsWithoutDroppingText() {
        val original = "🥕".repeat(4000)
        val edited = "🌿".repeat(4000)
        val row = VoiceRecording(UUID.randomUUID().toString(), 1, 2, "REVIEW", review = VoiceDraft(original, "Groceries", edited, listOf("milk", "eggs")).json())
        val records = row.recoveryTexts()
        val output = java.io.ByteArrayOutputStream()
        RecoveryExport.write(output, records, "Home", false, DataLease())
        val imported = RecoveryExport.read(output.toByteArray().inputStream())
        assertEquals(records.map { it.description }, imported.map { it.description })
        assertTrue(imported.joinToString("") { it.description }.contains(original))
        assertTrue(imported.joinToString("") { it.description }.contains(edited))
    }

    @Test fun reviewSavePreservesCaptureZoneAndInstant() = runBlocking {
        Fixture().use { f ->
            val originalZone = java.util.TimeZone.getDefault()
            try {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Helsinki"))
                val id = f.recording(shared = true)
                val captured = f.db.recordings().get(id)!!.captureContext!!
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
                val draft = VoiceDraft.from("Call tomorrow")
                f.store.review(id, draft); f.store.commit(id, draft.transcript, draft)
                assertEquals(captured, f.db.shared().intents(f.workspace.scope).single().captureContext)
            } finally { java.util.TimeZone.setDefault(originalZone) }
        }
    }

    @Test fun legacyCommittedAudioStillCleansUpAfterRestart() = runBlocking {
        Fixture().use { f ->
            val id = f.store.begin().id
            f.store.output(id).use { it.write(byteArrayOf(0, 1)) }
            f.db.recordings().markCommitted(id, "existing-task")
            assertFalse(f.store.exportable(id))
            f.store.recover()
            assertNull(f.db.recordings().get(id)); assertFalse(f.store.audio(id).exists())
        }
    }

    @Test fun queuedEditsSurviveDismissalAndReopenInOrder() = runBlocking {
        Fixture().use { f ->
            val controller = f.start(text = "Original")
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val lock = launch(Dispatchers.IO) { f.lease.access { locked.complete(Unit); release.await() } }
            locked.await()
            try {
                instrumentation.runOnMainSync {
                    controller.editReview(id, VoiceDraft("Original", "First edit"))
                    controller.editReview(id, VoiceDraft("Original", "Second edit"))
                    controller.editReview(id, VoiceDraft("Original", "Final edit", "Keep this too"))
                    controller.closeReview() // Composition is gone while IO is still blocked.
                    controller.openReview(id)
                }
                assertTrue(controller.state.value.busy)
                release.complete(Unit); lock.join()
                await { !controller.state.value.busy }
                assertEquals("Final edit", controller.state.value.draft!!.title)
                assertEquals("Keep this too", controller.state.value.draft!!.description)
                assertEquals(controller.state.value.draft, VoiceDraft.parse(f.db.recordings().get(id)!!.review!!))
            } finally { release.complete(Unit); lock.join() }
        }
    }
    @Test fun saveWaitsForOlderQueuedEditsBeforeCommittingFinalText() = runBlocking {
        Fixture().use { f ->
            val controller = f.start(text = "Original")
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            val locked = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val lock = launch(Dispatchers.IO) { f.lease.access { locked.complete(Unit); release.await() } }
            locked.await()
            try {
                instrumentation.runOnMainSync {
                    controller.editReview(id, VoiceDraft("Original", "Old queued edit"))
                    controller.saveReview(id, VoiceDraft("Original", "Final accepted edit"))
                }
                release.complete(Unit); lock.join()
                await { !controller.state.value.busy }
                assertEquals("Final accepted edit", f.db.inbox().task("voice-$id")!!.title)
                assertEquals(1, f.db.inbox().intents().size)
                assertNull(f.db.recordings().get(id))
            } finally { release.complete(Unit); lock.join() }
        }
    }

    @Test fun noAudioRetentionDeletesFailureButPreservesLegacyOptIn() = runBlocking {
        Fixture().use { f ->
            val temporary = f.recording(); val retained = f.recording(keep = true)
            f.store.failed(temporary, "CANCELED"); f.store.failed(retained, "CANCELED")
            assertNull(f.db.recordings().get(temporary)); assertFalse(f.store.audio(temporary).exists())
            assertEquals("CANCELED", f.db.recordings().get(retained)!!.reason)
            assertTrue(f.store.audio(retained).exists())
        }
    }
    @Test fun restartDeletesUnretainedPcmButKeepsReviewAndOldRecovery() = runBlocking {
        Fixture().use { f ->
            val interrupted = f.recording()
            val review = f.recording()
            val old = f.store.begin().id
            f.store.audio(old).writeBytes(byteArrayOf(0, 1))
            f.store.review(review, VoiceDraft.from("Buy milk"))
            assertFalse(f.store.audio(review).exists())
            f.store.recover()
            assertNull(f.db.recordings().get(interrupted)); assertFalse(f.store.audio(interrupted).exists())
            assertEquals("REVIEW", f.db.recordings().get(review)!!.state)
            assertEquals("Buy milk", VoiceDraft.parse(f.db.recordings().get(review)!!.review!!).title)
            assertTrue(f.store.audio(old).exists()); assertEquals("INTERRUPTED", f.db.recordings().get(old)!!.reason)
        }
    }
    @Test fun draftOutlivesAudioExpiryWithoutRetainingAudio() = runBlocking {
        Fixture().use { f ->
            val id = f.recording(keep = true)
            f.store.review(id, VoiceDraft.from("Milk"))
            f.store.prune(System.currentTimeMillis() + RecordingStore.RETAIN_MILLIS + 1)
            assertFalse(f.store.audio(id).exists())
            assertEquals("Milk", VoiceDraft.parse(f.db.recordings().get(id)!!.review!!).title)
        }
    }
    @Test fun analysisIsAnEditablePreviewAndSaveQueuesParentEditAndChecklistAtomically() = runBlocking {
        Fixture().use { f ->
            val controller = f.start({ text ->
                assertEquals("Shopping list: milk, 6 eggs, rye bread", text)
                VoiceDraft(text, "Shopping list", items = listOf("milk", "6 eggs", "rye bread"))
            })
            val id = f.recording(shared = true)
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals(id, controller.state.value.reviewId)
            assertEquals(listOf("milk", "6 eggs", "rye bread"), controller.state.value.draft!!.items)
            assertTrue(f.db.shared().intents(f.workspace.scope).isEmpty()); assertFalse(f.store.audio(id).exists())
            val edited = controller.state.value.draft!!.copy(items = listOf("oat milk", "6 eggs"))
            instrumentation.runOnMainSync { controller.saveReview(id, edited) }
            await { !controller.state.value.busy }
            val commands = f.db.shared().intents(f.workspace.scope)
            assertEquals(listOf("CreateTask", "EditTask", "SplitTask"), commands.map { it.kind })
            val tasks = f.db.shared().projectionRows(f.workspace.scope, "initial").map { JSONObject(it.snapshot) }
            val parent = tasks.single { it.isNull("parentId") }
            assertEquals("Shopping list", parent.getString("title")); assertTrue(parent.getBoolean("isChecklist"))
            assertEquals(setOf("oat milk", "6 eggs"), tasks.filter { !it.isNull("parentId") }.map { it.getString("title") }.toSet())
            assertNull(f.db.recordings().get(id)); assertFalse(f.store.audio(id).exists())
            assertEquals(VoiceTarget(f.workspace.scope), controller.state.value.saved!!.target)
        }
    }
    @Test fun failedAnalysisAndCanceledLateResultLeaveDurableTranscript() = runBlocking {
        for (cancel in listOf(false, true)) Fixture().use { f ->
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val controller = f.start({ text ->
                started.complete(Unit)
                if (cancel) withContext(NonCancellable) { finish.await() }
                else throw IllegalStateException("fixture")
                VoiceDraft(text, "Late unwanted result")
            })
            val id = f.recording(shared = true)
            instrumentation.runOnMainSync { controller.retry(id) }
            withTimeout(10_000) { started.await() }
            if (cancel) { instrumentation.runOnMainSync { controller.cancel() }; finish.complete(Unit) }
            await { !controller.state.value.busy }
            assertEquals("Shopping list: milk, 6 eggs, rye bread", controller.state.value.draft!!.title)
            assertEquals(controller.state.value.draft, VoiceDraft.parse(f.db.recordings().get(id)!!.review!!))
            assertTrue(f.db.shared().intents(f.workspace.scope).isEmpty()); assertFalse(f.store.audio(id).exists())
        }
    }
    @Test fun localOnlySelectionNeverCallsOnlineSpeech() = runBlocking {
        Fixture().use { f ->
            var calls = 0
            val controller = f.start({ calls++; error("Must not analyze") })
            val id = f.recording()
            instrumentation.runOnMainSync { controller.configure(localOnly = true); controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals("OFFLINE_MODEL_REQUIRED", controller.state.value.message)
            assertEquals(0, calls); assertNull(f.db.recordings().get(id)); assertTrue(f.db.inbox().intents().isEmpty())
        }
    }
    @Test fun optInKeepsSuccessfulAudioExportableButCannotTranscribeTwice() = runBlocking {
        Fixture().use { f ->
            val controller = f.start(text = "Book bike service")
            val id = f.recording(keep = true)
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            val draft = controller.state.value.draft!!.copy(title = "Book service")
            instrumentation.runOnMainSync { controller.saveReview(id, draft) }
            await { !controller.state.value.busy }
            assertTrue(f.store.exportable(id)); assertFalse(f.store.available(id))
            assertEquals("COMMITTED", f.db.recordings().get(id)!!.state)
            f.store.recover()
            assertTrue(f.store.exportable(id)); assertEquals(1, f.db.inbox().intents().size)
            assertEquals("Book bike service", f.db.inbox().task("voice-$id")!!.originalTitle)
        }
    }
    @Test fun failedChecklistSaveRollsBackParentAndLeavesDraft() = runBlocking {
        Fixture().use { f ->
            val id = f.recording(shared = true)
            val draft = VoiceDraft("milk, eggs", "Shopping list", items = listOf("milk", "eggs"))
            f.store.review(id, draft)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_split BEFORE INSERT ON shared_intents WHEN NEW.kind = 'SplitTask' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertTrue(runCatching { f.store.commit(id, draft.transcript, draft) }.isFailure)
            assertTrue(f.db.shared().intents(f.workspace.scope).isEmpty())
            assertEquals("REVIEW", f.db.recordings().get(id)!!.state)
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "Controller timeout" }; Thread.sleep(20) }
    }
}
