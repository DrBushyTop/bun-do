package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GuidedAdventureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val draft = GuidedDraft("A quiet corner", "", listOf(GuidedPhase(null, "Sort papers", "Make room", 1, 15)))
    private fun fixture(block: suspend (InboxDatabase, SharedWorkspace, SharedRepository) -> Unit) = runBlocking {
        val name = "guided-${UUID.randomUUID()}.db"; val db = InboxDatabase.open(context, name)
        val state = adventureWorkspace().copy(membership = JSONObject().put("me", UUID.randomUUID().toString()).toString())
        try { db.shared().saveWorkspace(state); block(db, state, SharedRepository(db, DataLease(), state.scope, state.registration)) }
        finally { db.close(); context.deleteDatabase(name) }
    }
    private fun empty(state: SharedWorkspace, revision: String = "3") = adventureFixture(state, active = false, revision = revision).apply {
        getJSONObject("board").put("batch", JSONObject.NULL)
    }
    @Test fun generation_and_editable_review_create_nothing_until_explicit_approval() = fixture { db, state, repository ->
        GuidedAdventure.plan(context, repository, "token", "Clear a corner", null) { _, _, action ->
            assertEquals("plan", action.getString("action")); JSONObject().put("snapshot", empty(state)).put("draft", draft.json())
        }
        assertEquals("REVIEW", repository.guidedCreation()!!.stage); assertTrue(db.shared().intents(state.scope).isEmpty())
        val restarted = SharedRepository(db, DataLease(), state.scope, state.registration)
        assertEquals(draft, restarted.guidedCreation()!!.draft)
        restarted.approveGuidedCreation(draft.copy(title = "Our quiet corner"))
        assertEquals("APPROVED", restarted.guidedCreation()!!.stage); assertTrue(db.shared().intents(state.scope).isEmpty())
    }
    @Test fun lost_start_reply_and_repeated_resume_keep_the_same_ordinary_task_ids() = fixture { db, state, repository ->
        GuidedAdventure.plan(context, repository, "token", "Clear", null) { _, _, _ -> JSONObject().put("snapshot", empty(state)).put("draft", draft.json()) }
        repository.approveGuidedCreation(draft)
        val local = repository.guidedCreation()!!
        val reserved = empty(state, "4").apply { getJSONObject("board").put("creation", JSONObject()
            .put("id", local.id).put("memberId", JSONObject(state.membership!!).getString("me")).put("registrationId", state.registration)
            .put("revision", "4").put("draft", draft.json()).put("startedAt", java.time.Instant.now().toString())) }
        var read = empty(state)
        var loseBeginReply = true
        val send: suspend (String, SharedWorkspace, JSONObject) -> JSONObject = { _, _, action ->
            when (action.getString("action")) {
                "read" -> read
                "beginCreation" -> { read = reserved; if (loseBeginReply) { loseBeginReply = false; throw SyncFailure("UNAVAILABLE") }; reserved }
                "finishCreation" -> throw SyncFailure("TASKS_PENDING")
                else -> error("Unexpected action")
            }
        }
        assertTrue(runCatching { GuidedAdventure.resume(context, repository, "token", send) }.isFailure)
        assertTrue(db.shared().intents(state.scope).isEmpty())
        GuidedAdventure.resume(context, repository, "token", send)
        val intent = db.shared().intents(state.scope).single(); assertEquals("CreateTask", intent.kind); assertEquals("Sort papers", intent.title)
        assertEquals(listOf(intent.taskId), repository.guidedCreation()!!.roots)
        val restarted = SharedRepository(db, DataLease(), state.scope, state.registration)
        repeat(3) { GuidedAdventure.resume(context, restarted, "token", send) }
        assertEquals(listOf(intent), db.shared().intents(state.scope))
        assertEquals("QUEUED", restarted.guidedCreation()!!.stage); assertNull(restarted.adventure.first()!!.active)
        // Covers either cancellation+refresh or lost finish reply followed by close+refresh.
        read = empty(state, "10")
        GuidedAdventure.resume(context, restarted, "token", send)
        assertEquals("ENDED", restarted.guidedCreation()!!.stage)
        assertEquals(listOf(intent), db.shared().intents(state.scope))
        restarted.discardGuidedReview(); assertNull(restarted.guidedCreation())
        assertEquals(listOf(intent), db.shared().intents(state.scope))
    }
    @Test fun existing_references_are_not_copied_and_revocation_blocks_private_draft_access() = fixture { db, state, repository ->
        val existing = adventureTask()
        val mixed = draft.copy(phases = listOf(GuidedPhase(existing.getString("id"), null, "Use what we have", 1, 10)) + draft.phases)
        GuidedAdventure.plan(context, repository, "token", "Clear", null) { _, _, _ -> JSONObject().put("snapshot", empty(state)).put("draft", mixed.json()) }
        repository.approveGuidedCreation(mixed)
        val local = repository.guidedCreation()!!
        val reserved = empty(state, "4").apply { getJSONObject("board").put("creation", JSONObject().put("id", local.id)
            .put("registrationId", state.registration).put("memberId", JSONObject(state.membership!!).getString("me"))
            .put("revision", "4").put("startedAt", java.time.Instant.now().toString()).put("draft", mixed.json())) }
        GuidedAdventure.resume(context, repository, "token") { _, _, action -> if (action.getString("action") == "finishCreation") throw SyncFailure("TASKS_PENDING") else reserved }
        assertEquals(1, db.shared().intents(state.scope).size); assertEquals(existing.getString("id"), repository.guidedCreation()!!.roots!!.first())
        val request = repository.prepareJourney(1000, 1)!!; repository.block(request, "FORBIDDEN")
        assertNull(repository.guidedCreation()); assertNull(repository.adventure.first())
    }
}
