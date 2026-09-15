package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxDatabase
import fi.bundo.data.RecordingStorageFull
import fi.bundo.data.RecordingStore
import fi.bundo.speech.exportWave
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "speech-${UUID.randomUUID()}.db"
    private val directory = File(context.cacheDir, name).apply { mkdirs() }
    private var database = InboxDatabase.open(context, name)
    private var store = RecordingStore(database, directory)

    @After fun close() {
        database.close()
        context.deleteDatabase(name)
        directory.deleteRecursively()
    }

    @Test fun householdSpeechTargetsTheCapturedWorkspaceAndCommitsBeforeAudioCleanup() = runBlocking {
        val registration = UUID.randomUUID().toString()
        val member = UUID.randomUUID().toString()
        val state = fi.bundo.data.SharedWorkspace("voice-household", UUID.randomUUID().toString(), UUID.randomUUID().toString(), registration, "Home",
            membership = org.json.JSONObject().put("me", member).toString())
        database.shared().saveWorkspace(state)
        val row = store.begin(target = fi.bundo.data.VoiceTarget(state.scope))
        store.audio(row.id).writeBytes(byteArrayOf(1, 2))
        // Force only post-commit cleanup to fail. A retry must return the committed identity.
        store.audio(row.id).delete()
        store.audio(row.id).mkdir()
        File(store.audio(row.id), "block-cleanup").writeText("fixture")
        assertTrue(runCatching { store.commit(row.id, "Varaa pyörän huolto") }.isFailure)
        val committed = database.recordings().get(row.id)!!
        assertEquals("COMMITTED", committed.state)
        val taskId = committed.committedTaskId!!
        assertTrue(database.inbox().intents().isEmpty())
        assertEquals(1, database.shared().intents(state.scope).size)
        val task = org.json.JSONObject(database.shared().task(state.scope, taskId)!!.snapshot)
        assertEquals("Varaa pyörän huolto", task.getString("title"))
        assertEquals(member, task.getJSONObject("creation").getString("actorId"))
        assertEquals(java.time.Instant.ofEpochMilli(row.createdAt), java.time.Instant.parse(task.getJSONObject("creation").getString("capturedAt")))
        store.audio(row.id).deleteRecursively()
        assertEquals(fi.bundo.data.SavedRecording(taskId, fi.bundo.data.VoiceTarget(state.scope)),
            store.commit(row.id, "Do not create this twice"))
        assertEquals(1, database.shared().intents(state.scope).size)
        assertNull(database.recordings().get(row.id))
    }

    @Test fun householdSpeechFailureRetainsAudioAndDoesNotFallBackToLocalInbox() = runBlocking {
        val state = fi.bundo.data.SharedWorkspace("voice-household", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Home",
            membership = org.json.JSONObject().put("me", UUID.randomUUID().toString()).toString())
        database.shared().saveWorkspace(state)
        val row = store.begin(target = fi.bundo.data.VoiceTarget(state.scope))
        store.audio(row.id).writeBytes(byteArrayOf(1, 2))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_shared_voice BEFORE INSERT ON shared_intents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { store.commit(row.id, "Buy milk") }.isFailure)
        assertTrue(database.shared().intents(state.scope).isEmpty())
        assertTrue(database.inbox().intents().isEmpty())
        assertEquals("RECORDING", database.recordings().get(row.id)!!.state)
        assertTrue(store.audio(row.id).exists())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_shared_voice")
        database.shared().saveWorkspace(state.copy(blocked = "FORBIDDEN"))
        assertTrue(runCatching { store.commit(row.id, "Buy milk") }.isFailure)
        assertTrue(store.audio(row.id).exists())
        assertTrue(database.inbox().intents().isEmpty())
    }

    @Test fun interruptedRecordingAndTranscriptionSurviveReopen() = runBlocking {
        val first = store.begin()
        store.audio(first.id).writeBytes(byteArrayOf(0, 1, 2, 3))
        val second = store.begin()
        store.transcribing(second.id)
        database.close()
        database = InboxDatabase.open(context, name)
        store = RecordingStore(database, directory)
        store.recover()
        assertEquals(listOf("INTERRUPTED", "INTERRUPTED"), database.recordings().all().map { it.reason })
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), store.audio(first.id).readBytes())
    }

    @Test fun textIntentAndMarkerCommitBeforeAudioRemoval() = runBlocking {
        val row = store.begin()
        store.audio(row.id).writeBytes(byteArrayOf(1, 2))
        val saved = store.commit(row.id, "Osta maitoa")
        assertNull(saved.target)
        val id = saved.taskId
        assertEquals("Osta maitoa", database.inbox().task(id)!!.originalTitle)
        assertEquals("CaptureInboxTask", database.inbox().intents().single().kind)
        assertNull(database.recordings().get(row.id))
        assertFalse(store.audio(row.id).exists())
    }

    @Test fun failedDatabaseCommitRetainsAudioAndNoPartialTask() = runBlocking {
        val row = store.begin()
        store.audio(row.id).writeBytes(byteArrayOf(1, 2))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_voice BEFORE INSERT ON inbox_intents BEGIN SELECT RAISE(ABORT, 'fixture'); END",
        )
        assertTrue(runCatching { store.commit(row.id, "Buy milk") }.isFailure)
        assertNull(database.inbox().task("voice-${row.id}"))
        assertTrue(database.inbox().intents().isEmpty())
        assertTrue(store.audio(row.id).exists())
        assertEquals("RECORDING", database.recordings().get(row.id)!!.state)
    }

    @Test fun cleanupCrashDoesNotDuplicateCommittedTask() = runBlocking {
        val row = store.begin()
        // A nonempty directory forces the post-commit file delete to fail.
        store.audio(row.id).mkdir()
        File(store.audio(row.id), "block").writeText("fixture")
        assertTrue(runCatching { store.commit(row.id, "Buy milk") }.isFailure)
        assertEquals("COMMITTED", database.recordings().get(row.id)!!.state)
        store.failed(row.id, "TRANSCRIPTION_FAILED") // Controller's broad failure path.
        assertEquals("COMMITTED", database.recordings().get(row.id)!!.state)
        assertFalse(store.available(row.id))
        File(store.audio(row.id), "block").delete()
        store.commit(row.id, "Buy milk")
        assertEquals(1, database.inbox().intents().size)
        assertNull(database.recordings().get(row.id))
    }

    @Test fun countLimitRequiresCleanupAndExpiryReclaimsOnlyExpiredAudio() = runBlocking {
        val now = System.currentTimeMillis()
        repeat(20) { store.begin(now).also { row -> store.audio(row.id).writeBytes(byteArrayOf(0, 0)) } }
        assertTrue(runCatching { store.begin(now) }.exceptionOrNull() is RecordingStorageFull)
        val first = database.recordings().all().first()
        store.delete(first.id)
        val fresh = store.begin(now + 1000)
        store.prune(now + RecordingStore.RETAIN_MILLIS)
        assertEquals(fresh.id, database.recordings().all().single().id)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun backgroundExpiryInvalidatesForegroundRecoveryList() = runBlocking {
        val now = System.currentTimeMillis()
        val row = store.begin(now)
        store.audio(row.id).writeBytes(byteArrayOf(0, 0))
        val observing = CompletableDeferred<Unit>()
        val removal = async(Dispatchers.IO) {
            withTimeout(10_000) {
                store.recordings.onEach { rows ->
                    if (rows.any { it.id == row.id }) observing.complete(Unit)
                }.first { rows -> observing.isCompleted && rows.none { it.id == row.id } }
            }
        }
        withTimeout(10_000) { observing.await() }
        try {
            // The account-aware worker now shares the active account's store.
            kotlinx.coroutines.withContext(Dispatchers.IO) { store.prune(now + RecordingStore.RETAIN_MILLIS) }
            assertTrue(removal.await().isEmpty())
            assertFalse(store.audio(row.id).exists())
        } finally {
            removal.cancel()
        }
    }

    @Test fun longTranscriptKeepsWholeOriginalTextAndExportIsValidWave() = runBlocking {
        val row = store.begin()
        val text = "Muista ostaa maitoa. ".repeat(15)
        store.audio(row.id).writeBytes(byteArrayOf(1, 2, 3, 4))
        val output = ByteArrayOutputStream()
        exportWave(store.audio(row.id), output)
        assertEquals("RIFF", String(output.toByteArray(), 0, 4))
        assertEquals(4, ByteBuffer.wrap(output.toByteArray(), 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        val id = store.commit(row.id, text).taskId
        assertEquals(text.trim(), database.inbox().task(id)!!.originalDescription)
        assertEquals(160, database.inbox().task(id)!!.title.length)
    }
}
