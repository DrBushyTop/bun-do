package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedJourneyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: suspend (InboxDatabase, SharedWorkspace, DataLease, SharedRepository) -> Unit) = runBlocking {
        val name = "journey-${UUID.randomUUID()}.db"
        val db = InboxDatabase.open(context, name)
        val workspace = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        val registration = UUID.randomUUID().toString()
        val state = SharedWorkspace("$workspace/$epoch/$registration", workspace, epoch, registration, "Test")
        val lease = DataLease()
        try {
            db.shared().saveWorkspace(state)
            block(db, state, lease, SharedRepository(db, lease, state.scope, registration))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun firstHouseholdRefreshStartsJourneyWithoutSeparateConsent() = fixture { _, _, _, repository ->
        var enabled: Boolean? = null
        SharedJourney.refresh(context, repository, "token", false) { _, _, enable -> enabled = enable; journeyFixture() }
        assertEquals(true, enabled)
    }

    @Test fun existingJourneyIsReadWithoutRestartingAndPrivateWorkNeverEnablesIt() = fixture { db, state, _, repository ->
        val request = repository.prepareJourney(1, 1)!!
        repository.applyJourney(request, journeyFixture()); repository.release(request)
        SharedJourney.refresh(context, repository, "token", false) { _, _, enable -> assertFalse(enable); journeyFixture() }
        db.shared().saveWorkspace(repository.workspace.first()!!.copy(personal = true))
        SharedJourney.refresh(context, repository, "token", false) { _, _, _ -> error("Private workspace must not request a journey") }
    }

    @Test fun acceptedJourneySurvivesRepositoryRestartWithoutSubmittingPendingTextOrAdvancingSyncCursor() = fixture { db, state, _, repository ->
        repository.copyText("Still pending", "")
        val request = repository.prepareJourney(1000, 1)!!
        assertNull(request.envelope)
        assertEquals("PENDING", db.shared().intents(state.scope).single().status)
        assertNull(db.shared().intents(state.scope).single().frozen)
        assertTrue(repository.applyJourney(request, journeyFixture()))
        repository.release(request)
        val stored = SharedRepository(db, DataLease(), state.scope, state.registration).workspace.first()!!
        assertEquals(7, JourneyProgress.read(JSONObject(stored.progress!!))!!.credits)
        assertEquals("0", stored.revision)
        assertNull(stored.cursor)
        assertEquals("Still pending", repository.taskStates.first().single().getString("title"))
    }

    @Test fun expiredWorkerAndWrongScopeCannotApplyAndSyncSharesTheSameLock() = fixture { _, _, _, repository ->
        val first = repository.prepareJourney(1000, 1)!!
        assertNull(repository.prepare(1001, 1))
        val second = repository.prepareJourney(151001, 1)!!
        assertFalse(repository.applyJourney(first, journeyFixture()))
        assertTrue(runCatching { repository.applyJourney(second.copy(workspace = second.workspace.copy(epoch = UUID.randomUUID().toString())), journeyFixture()) }.isFailure)
        assertTrue(repository.applyJourney(second, journeyFixture()))
        repository.release(first)
        assertNull(repository.prepare(151002, 1))
        repository.release(second)
        assertNotNull(repository.prepare(151003, 1))
    }

    @Test fun revokedAccountDoesNotApplyLateResponse() = fixture { db, state, lease, repository ->
        val request = repository.prepareJourney(1000, 1)!!
        lease.revoke()
        assertTrue(runCatching { repository.applyJourney(request, journeyFixture()) }.exceptionOrNull() is CancellationException)
        assertNull(db.shared().workspace(state.scope)!!.progress)
    }

    @Test fun accessRemovalClearsCachedJourneyAndRecoveryBlocksRequests() = fixture { db, state, _, repository ->
        val request = repository.prepareJourney(1000, 1)!!
        assertTrue(repository.applyJourney(request, journeyFixture()))
        repository.block(request, "FORBIDDEN")
        assertNull(repository.workspace.first()!!.progress)
        assertNull(repository.prepareJourney(1001, 1))
        db.shared().saveWorkspace(state)
        db.shared().saveRecovery(SharedRecovery(state.scope, UUID.randomUUID().toString()))
        assertNull(repository.prepareJourney(1002, 1))
    }

    @Test fun olderOrResetSnapshotsCannotEraseAcceptedJourney() {
        val prior = journeyFixture().toString()
        assertEquals(prior, SharedProgress.accept(prior, journeyFixture(8, "1")))
        assertEquals(prior, SharedProgress.accept(prior, journeyFixture(6, "3")))
        assertEquals(prior, SharedProgress.accept(prior, journeyFixture(8, "3").put("journey", JSONObject.NULL)))
        val shifted = journeyFixture(8, "3").apply { getJSONObject("journey").put("enabledAt", "2026-09-15T07:30:00Z") }
        assertEquals(prior, SharedProgress.accept(prior, shifted))
        val next = SharedProgress.accept(prior, journeyFixture(8, "3"))
        assertEquals(8, JourneyProgress.read(JSONObject(next!!))!!.credits)
    }

    @Test fun malformedJourneyCannotReplaceValidCache() = fixture { _, _, _, repository ->
        val request = repository.prepareJourney(1000, 1)!!
        repository.applyJourney(request, journeyFixture())
        val before = repository.workspace.first()!!.progress
        for (field in listOf("credits", "locationIndex", "locationCount", "completionsPerLocation")) {
            val invalid = journeyFixture(8, "3").apply { getJSONObject("journey").put(field, -1) }
            assertTrue(runCatching { repository.applyJourney(request, invalid) }.isFailure)
        }
        assertTrue(runCatching { SharedProgress.validate(journeyFixture().put("journey", "bad")) }.isFailure)
        assertEquals(before, repository.workspace.first()!!.progress)
    }

    @Test fun unknownAuthoredRouteRetainsServerPositionRatherThanInventingStops() {
        val next = journeyFixture().apply { getJSONObject("journey").put("routeId", "future-route").put("locationId", "future-stop") }
        val result = JourneyProgress.read(next)!!
        assertEquals("future-route", result.routeId)
        assertEquals("future-stop", result.locationId)
        assertEquals(1, result.locationIndex)
    }

    @Test fun higherCreditsCannotMoveBackwardsOrRewritePublishedStops() {
        val prior = journeyFixture().toString()
        val backwards = journeyFixture(8, "3").apply {
            getJSONObject("journey").put("locationIndex", 0).put("locationId", "dojo-gate").put("locationCompletions", 0)
        }
        assertEquals(prior, SharedProgress.accept(prior, backwards))
        val cases = listOf(
            "locationCompletions" to 1, "locationId" to "changed-stop", "locationCount" to 6,
            "completionsPerLocation" to 4, "routeId" to "unearned-new-route")
        for ((field, value) in cases) {
            val changed = journeyFixture(8, "3").apply { getJSONObject("journey").put(field, value) }
            assertEquals(field, prior, SharedProgress.accept(prior, changed))
        }
    }

    @Test fun completedRouteAndAppendedFutureRouteTransitionsRemainAccepted() {
        val prior = journeyFixture(24).toString()
        val completed = SharedProgress.accept(prior, journeyFixture(25, "3"))!!
        assertTrue(JourneyProgress.read(JSONObject(completed))!!.resting)
        val next = journeyFixture(25, "4").apply {
            getJSONObject("journey").put("routeId", "riverside").put("locationId", "river-gate")
                .put("locationIndex", 0).put("locationCompletions", 0).put("resting", false)
        }
        assertEquals("riverside", JourneyProgress.read(JSONObject(SharedProgress.accept(completed, next)!!))!!.routeId)
        // A device can also miss the resting snapshot entirely.
        assertEquals("riverside", JourneyProgress.read(JSONObject(SharedProgress.accept(prior, next)!!))!!.routeId)
        val back = journeyFixture(25, "5")
        assertEquals(next.toString(), SharedProgress.accept(next.toString(), back))
    }
}
