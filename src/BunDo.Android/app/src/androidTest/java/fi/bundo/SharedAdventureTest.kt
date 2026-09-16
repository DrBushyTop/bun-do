package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedAdventureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: suspend (InboxDatabase, SharedWorkspace, DataLease, SharedRepository) -> Unit) = runBlocking {
        val name = "adventure-${UUID.randomUUID()}.db"; val db = InboxDatabase.open(context, name)
        val state = adventureWorkspace(); val lease = DataLease()
        try { db.shared().saveWorkspace(state); block(db, state, lease, SharedRepository(db, lease, state.scope, state.registration)) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun cacheSurvivesRestartWithoutSubmittingPendingTextOrAdvancingTaskCursor() = fixture { db, state, _, repository ->
        repository.copyText("Private pending text", "")
        val request = repository.prepareJourney(1000, 1)!!
        assertNull(request.envelope)
        val snapshot = adventureFixture(state)
        assertTrue(repository.applyAdventure(request, snapshot)); repository.release(request)
        val restarted = SharedRepository(db, DataLease(), state.scope, state.registration)
        assertEquals(snapshot.getJSONObject("board").getJSONObject("active").getString("id"), restarted.adventure.first()!!.active!!.id)
        assertEquals("0", restarted.workspace.first()!!.revision); assertNull(restarted.workspace.first()!!.cursor)
        assertEquals("PENDING", db.shared().intents(state.scope).single().status); assertNull(db.shared().intents(state.scope).single().frozen)
        assertEquals("Private pending text", repository.taskStates.first().single().getString("title"))
    }
    @Test fun staleWrongEpochAndLostWorkerRepliesCannotReplaceCache() = fixture { _, state, _, repository ->
        val first = repository.prepareJourney(1000, 1)!!
        val newer = repository.prepareJourney(151001, 1)!!
        assertFalse(repository.applyAdventure(first, adventureFixture(state)))
        val accepted = adventureFixture(state, revision = "5")
        repository.applyAdventure(newer, accepted)
        repository.applyAdventure(newer, adventureFixture(state, revision = "4"))
        repository.applyAdventure(newer, adventureFixture(state, revision = "5"))
        assertEquals(accepted.toString(), repository.workspace.first()!!.adventure)
        assertTrue(runCatching { repository.applyAdventure(newer, adventureFixture(state.copy(epoch = UUID.randomUUID().toString()))) }.isFailure)
    }
    @Test fun artwork_in_flight_never_reserves_command_worker_and_stale_reply_cannot_replace_acceptance() = fixture { db, state, lease, repository ->
        val initial = repository.prepareJourney(1, 1)!!
        repository.applyAdventure(initial, adventureFixture(state, active = false))
        repository.release(initial)
        val imageRequest = repository.prepareArtwork()!!
        val command = repository.prepareJourney(2, 1)!!
        val accepted = adventureFixture(state, revision = "5")
        assertTrue(repository.applyAdventure(command, accepted))
        assertNotNull(repository.prepareArtwork()) // Explicit retry also runs while a command owns the worker.
        assertTrue(repository.applyArtwork(imageRequest, adventureFixture(state, revision = "4")))
        assertEquals(accepted.toString(), repository.workspace.first()!!.adventure)
        assertEquals(command.worker, repository.workspace.first()!!.worker)
        repository.release(command)
        val sync = repository.prepare(3, 1)!!
        assertTrue(repository.applyArtwork(imageRequest, accepted))
        assertEquals(sync.worker, repository.workspace.first()!!.worker)
        assertEquals("0", db.shared().workspace(state.scope)!!.revision)
        repository.blockArtwork(imageRequest, "FORBIDDEN")
        assertNull(repository.prepareArtwork())
        assertNull(repository.artworkChoice(accepted.getJSONObject("board").getJSONObject("active").getString("id")))
        assertFalse(repository.applyArtwork(imageRequest, accepted))
        lease.revoke()
        assertTrue(runCatching { repository.applyArtwork(imageRequest, accepted) }.exceptionOrNull() is CancellationException)
    }
    @Test fun held_image_download_allows_acceptance_and_sync_and_reuses_private_cache_offline() = fixture { _, state, lease, repository ->
        coroutineScope {
            val initial = repository.prepareJourney(1, 1)!!
            val snapshot = adventureFixture(state)
            repository.applyAdventure(initial, snapshot); repository.release(initial)
            val request = repository.prepareArtwork()!!
            val choice = AdventureSnapshot.read(snapshot).active!!
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val directory = java.io.File(context.cacheDir, "image-${UUID.randomUUID()}")
            val cache = ArtworkCache(directory, lease)
            val bitmap = android.graphics.Bitmap.createBitmap(24, 16, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            bitmap.recycle()
            try {
                val download = async { AdventureArtworkClient.fetch(repository, request, choice, true, cache, "synthetic") { _, body ->
                    if (body.getString("action") == "image") { entered.complete(Unit); release.await(); bytes }
                    else {
                        assertEquals("retry", body.getString("action"))
                        JSONObject().put("status", "READY").put("key", "dojo-v1-home").put("snapshot", snapshot).toString().toByteArray()
                    }
                } }
                entered.await()
                val acceptance = repository.prepareJourney(2, 1)!!
                assertTrue(repository.applyAdventure(acceptance, adventureFixture(state, revision = "5")))
                repository.release(acceptance)
                val sync = repository.prepare(3, 1)!!
                release.complete(Unit)
                assertEquals(24, download.await().image!!.width)
                assertEquals(sync.worker, repository.workspace.first()!!.worker)
                assertEquals(5uL, AdventureSnapshot.read(JSONObject(repository.workspace.first()!!.adventure!!)).revision)
                assertEquals(16, ArtworkCache(directory, lease).read("dojo-v1-home")!!.height)
            } finally { release.complete(Unit); directory.deleteRecursively() }
        }
    }
    @Test fun revokedAccountAndAccessRemovalDoNotRetainAdventureContent() = fixture { db, state, lease, repository ->
        val request = repository.prepareJourney(1000, 1)!!
        repository.applyAdventure(request, adventureFixture(state)); repository.block(request, "FORBIDDEN")
        assertNull(db.shared().workspace(state.scope)!!.adventure)
        lease.revoke()
        assertTrue(runCatching { repository.applyAdventure(request, adventureFixture(state)) }.exceptionOrNull() is CancellationException)
    }
    @Test fun unavailableRootsNeverCompleteAndEmptyAdventureIsNotComplete() {
        val state = adventureWorkspace(); val root = adventureTask(done = true).put("lifecycle", "CANCELLED")
        val snapshot = AdventureSnapshot.read(adventureFixture(state, root)).project(state, listOf(root), emptyList())
        assertFalse(snapshot.complete); assertEquals(1, snapshot.total); assertEquals(0, snapshot.completed)
        val empty = snapshot.copy(active = snapshot.active!!.copy(draft = snapshot.active!!.draft.copy(phases = emptyList())))
        assertEquals(0, empty.total); assertFalse(empty.complete)
    }
    @Test fun currentLocalRootLifecycleWinsAfterSyncCatchesUpAndPendingCompletionProjectsOffline() = fixture { db, state, _, repository ->
        val root = adventureTask()
        val me = UUID.randomUUID().toString()
        val synced = state.copy(revision = "3", membership = JSONObject().put("me", me).toString())
        db.shared().saveWorkspace(synced)
        db.shared().saveBase(SharedBase(state.scope, root.getString("id"), root.toString()))
        db.shared().saveProjection(SharedProjection(state.scope, root.getString("id"), root.toString()))
        val request = repository.prepareJourney(1000, 1)!!
        repository.applyAdventure(request, adventureFixture(state, root)); repository.release(request)
        repository.act("CompleteTask", root.toString())
        assertTrue(repository.adventure.first()!!.complete)
        val completed = repository.taskStates.first().single()
        repository.act("ReopenTask", completed.toString())
        assertFalse(repository.adventure.first()!!.complete)
        // Freezing seq1 replaces its SQLite row after seq2. Replay must still use numeric sequence order.
        val submitted = repository.prepare(2000, 1)!!
        assertFalse(repository.adventure.first()!!.complete)
        assertFalse(repository.acknowledgeAdventure(repository.adventure.first()!!.active!!.id, false))
        repository.release(submitted)
        assertEquals(1, repository.adventure.first()!!.total)
    }
    @Test fun bowAndSignAcknowledgementsPersistIndependentlyAndDoNotReplay() = fixture { db, state, _, repository ->
        val request = repository.prepareJourney(1000, 1)!!
        repository.applyAdventure(request, adventureFixture(state, adventureTask(done = true)))
        repository.release(request)
        val id = repository.adventure.first()!!.active!!.id
        assertTrue(repository.acknowledgeAdventure(id, false)); assertFalse(repository.acknowledgeAdventure(id, false))
        val restarted = SharedRepository(db, DataLease(), state.scope, state.registration)
        assertFalse(restarted.acknowledgeAdventure(id, false)); assertTrue(restarted.acknowledgeAdventure(id, true))
        assertFalse(restarted.acknowledgeAdventure(UUID.randomUUID().toString(), true))
    }
    @Test fun consumedBatchRefreshesOnVisitAndAfterBothCloseActions() = fixture { _, state, _, repository ->
        for (action in listOf("visit", "leave", "dismiss")) {
            val consumed = adventureFixture(state).apply {
                getJSONObject("board").put("active", JSONObject.NULL)
                put("progress", JSONObject.NULL)
            }
            val ready = adventureFixture(state, active = false, revision = "4")
            val calls = mutableListOf<String>()
            SharedAdventure.send(context, repository, "token", JSONObject().put("action", action)) { _, _, body ->
                calls += body.getString("action")
                if (body.getString("action") == "refresh") ready else consumed
            }
            assertEquals(listOf(if (action == "visit") "read" else action, "refresh"), calls)
            // Each request has a fresh batch; use a fresh revision for cache progression below.
            assertEquals("READY", repository.adventure.first()!!.batchStatus(Instant.now()))
        }
    }
    @Test fun expiryAndInvalidResponseBoundsCannotBecomeStartableSuggestions() {
        val state = adventureWorkspace(); val fixture = adventureFixture(state, active = false)
        fixture.getJSONObject("board").getJSONObject("batch").put("expiresAt", Instant.now().minusSeconds(1).toString())
        assertEquals("EXPIRED", AdventureSnapshot.read(fixture).batchStatus(Instant.now()))
        val choice = fixture.getJSONObject("board").getJSONObject("batch").getJSONArray("proposals").getJSONObject(0)
        choice.getJSONObject("draft").getJSONArray("phases").getJSONObject(0).put("stars", 4)
        assertTrue(runCatching { AdventureSnapshot.read(fixture) }.isFailure)
    }
}
