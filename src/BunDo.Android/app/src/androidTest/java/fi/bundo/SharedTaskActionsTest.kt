package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedTaskActionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val me = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InboxDatabase::class.java)

    @Test fun migrationPreservesFrozenWorkAndAddsNoImplicitTaskAction() {
        val name = "task-action-migration-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 6).use {
                it.execSQL("""INSERT INTO shared_intents
                    (scope,sequence,taskId,kind,title,titleChanged,descriptionChanged,observedTitle,observedDescription,
                    observedDeletion,captureContext,frozen,status)
                    VALUES ('scope','7','task','EditTask','Keep this',1,0,'4','1','1','{}','unchanged wire bytes','SUBMITTED')""")
            }
            migrations.runMigrationsAndValidate(name, 7, true, InboxDatabase.MIGRATION_6_7).use { db ->
                db.query("SELECT frozen, taskAction, status FROM shared_intents").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("unchanged wire bytes", it.getString(0))
                    assertTrue(it.isNull(1))
                    assertEquals("SUBMITTED", it.getString(2))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun atomicOrderEntityControlsTheSharedQueueInsteadOfCaptureTime() = runBlocking {
        fixture { _, _, repository ->
            val a = task("First")
            val b = task("Second")
            val request = repository.prepare(1000, 1)!!
            repository.apply(request, reply(request, listOf(a, b, order(b.getString("id"), a.getString("id")))))
            assertEquals(listOf("Second", "First"), repository.tasks.first().map { it.title })
        }
    }

    @Test fun offlineClaimThenCompleteKeepsItsDependenciesAndFrozenBytesAcrossRestart() = runBlocking {
        fixture { db, state, repository ->
            val task = task("Dishes")
            val id = task.getString("id")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(id))))
            repository.act("ClaimTask", repository.taskStates.first().single().toString())
            repository.act("CompleteTask", repository.taskStates.first().single().toString())
            assertEquals("COMPLETED", repository.taskStates.first().single().getString("lifecycle"))
            val claim = repository.prepare(1001, 1)!!
            val claimed = JSONObject(task.toString()).put("claimantId", me).put("claimVersion", "2")
            repository.apply(claim, reply(claim, listOf(claimed), receipt(claim, claimed)))
            val complete = repository.prepare(1002, 1)!!
            val wire = JSONObject(complete.envelope!!)
            assertEquals("CompleteTask", wire.getString("command"))
            assertEquals("2", wire.getJSONObject("observedVersions").getJSONObject("claim").getString("fieldVersion"))
            assertEquals("1", wire.getJSONObject("observedVersions").getJSONObject("lifecycle").getString("fieldVersion"))
            val name = checkNotNull(db.openHelper.databaseName)
            db.close()
            val reopened = InboxDatabase.open(context, name)
            try {
                val restarted = SharedRepository(reopened, DataLease(), state.scope, state.registration)
                val retry = restarted.prepare(100_000, 2)!!
                assertEquals(complete.envelope, retry.envelope)
                val completed = JSONObject(claimed.toString()).put("claimantId", JSONObject.NULL).put("claimVersion", "3")
                    .put("lifecycle", "COMPLETED").put("lifecycleVersion", "3")
                    .put("firstCompletion", JSONObject().put("rootId", id).put("memberId", me).put("acceptedAt", "2026-09-13T12:00:00Z"))
                restarted.apply(retry, reply(retry, listOf(completed), receipt(retry, completed)))
                assertEquals("2026-09-13T12:00:00Z", restarted.taskStates.first().single()
                    .getJSONObject("firstCompletion").getString("acceptedAt"))
                assertEquals(2, reopened.shared().intents(state.scope).size)
            } finally { reopened.close() }
        }
    }

    @Test fun aConcurrentClaimRetainsTheRejectedActionWithoutOverwritingTheCurrentClaimant() = runBlocking {
        fixture { db, state, repository ->
            val task = task("Dishes")
            val id = task.getString("id")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(id))))
            val poll = repository.prepare(1001, 1)!!
            repository.act("ClaimTask", repository.taskStates.first().single().toString())
            val remote = JSONObject(task.toString()).put("claimantId", other).put("claimVersion", "2")
            repository.apply(poll, reply(poll, listOf(remote)))
            assertEquals(other, repository.taskStates.first().single().getString("claimantId"))
            val request = repository.prepare(1002, 1)!!
            assertEquals("1", JSONObject(request.envelope!!).getJSONObject("observedVersions").getJSONObject("claim").getString("fieldVersion"))
            repository.apply(request, reply(request, emptyList(), receipt(request, remote, "CLAIM_CONFLICT")))
            assertEquals("REJECTED", db.shared().intents(state.scope).single().status)
            assertEquals("CLAIM_CONFLICT", repository.problems.first().single().problem)
            assertEquals(other, repository.taskStates.first().single().getString("claimantId"))
        }
    }

    @Test fun movingOfflineUsesAnchorsWithoutChangingAnotherTasksMoveVersion() = runBlocking {
        fixture { _, _, repository ->
            val a = task("First")
            val b = task("Second")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(a, b, order(a.getString("id"), b.getString("id")))))
            repository.act("MoveTask", b.toString(), before = a.getString("id"))
            assertEquals(listOf("Second", "First"), repository.tasks.first().map { it.title })
            val request = repository.prepare(1001, 1)!!
            val wire = JSONObject(request.envelope!!)
            assertEquals(a.getString("id"), wire.getJSONObject("payload").getString("beforeTaskId"))
            assertEquals("1", wire.getJSONObject("observedVersions").getJSONObject("orderIntent").getString("fieldVersion"))
        }
    }

    @Test fun pendingReopenStaysVisibleAfterPreparingCompletionAndRestarting() = runBlocking {
        fixture { db, state, repository ->
            val task = task("Dishes")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(task.getString("id")))))
            repository.act("CompleteTask", repository.taskStates.first().single().toString())
            repository.act("ReopenTask", repository.taskStates.first().single().toString())
            assertEquals(listOf("Dishes"), repository.tasks.first().map { it.title })

            val submitted = repository.prepare(1001, 1)!!
            assertEquals(listOf("Dishes"), repository.tasks.first().map { it.title })
            val name = checkNotNull(db.openHelper.databaseName)
            db.close()
            val reopened = InboxDatabase.open(context, name)
            try {
                val restarted = SharedRepository(reopened, DataLease(), state.scope, state.registration)
                assertEquals(submitted.envelope, restarted.prepare(100_000, 2)!!.envelope)
                assertEquals("OPEN", restarted.taskStates.first().single().getString("lifecycle"))
                assertEquals(listOf("Dishes"), restarted.tasks.first().map { it.title })
            } finally { reopened.close() }
        }
    }

    @Test fun aPendingMoveUsesItsAnchorsBeforeLaterPendingCompletionsRemoveThem() = runBlocking {
        fixture { _, _, repository ->
            val tasks = listOf("A", "B", "C", "D", "E").map(::task)
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, tasks + order(*tasks.map { it.getString("id") }.toTypedArray())))
            repository.act("MoveTask", tasks[4].toString(), after = tasks[1].getString("id"), before = tasks[2].getString("id"))
            repository.act("CompleteTask", tasks[1].toString())
            repository.act("CompleteTask", tasks[2].toString())
            assertEquals(listOf("A", "E", "D"), repository.taskStates.first()
                .filter { it.getString("lifecycle") == "OPEN" }.map { it.getString("title") })
        }
    }

    @Test fun aClaimReceiptDoesNotHideALaterPendingReopenBehindAFalseConflict() = runBlocking {
        fixture { _, _, repository ->
            val task = task("Dishes")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(task.getString("id")))))
            repository.act("ClaimTask", repository.taskStates.first().single().toString())
            repository.act("CompleteTask", repository.taskStates.first().single().toString())
            repository.act("ReopenTask", repository.taskStates.first().single().toString())
            val claim = repository.prepare(1001, 1)!!
            val claimed = JSONObject(task.toString()).put("claimantId", me).put("claimVersion", "2")
            repository.apply(claim, reply(claim, listOf(claimed), receipt(claim, claimed)))
            assertEquals("OPEN", repository.taskStates.first().single().getString("lifecycle"))
            assertTrue(repository.problems.first().isEmpty())
        }
    }

    @Test fun aRemoteClaimConflictStillBlocksTheDependentPendingCompletionAndReopen() = runBlocking {
        fixture { _, _, repository ->
            val task = task("Dishes")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(task.getString("id")))))
            val poll = repository.prepare(1001, 1)!!
            repository.act("ClaimTask", repository.taskStates.first().single().toString())
            repository.act("CompleteTask", repository.taskStates.first().single().toString())
            repository.act("ReopenTask", repository.taskStates.first().single().toString())
            val claimed = JSONObject(task.toString()).put("claimantId", other).put("claimVersion", "2")
            repository.apply(poll, reply(poll, listOf(claimed)))
            assertEquals(other, repository.taskStates.first().single().getString("claimantId"))
            assertEquals("OPEN", repository.taskStates.first().single().getString("lifecycle"))
            assertEquals(listOf("CLAIM_CONFLICT", "BLOCKED_DEPENDENCY", "BLOCKED_DEPENDENCY"),
                repository.problems.first().map { it.problem })
        }
    }

    @Test fun matchingVersionsCannotHideDifferentClaimantsOrReplaceFirstCompletionCredit() {
        val effect = task("Dishes").put("claimantId", me).put("claimVersion", "2")
        val different = JSONObject(effect.toString()).put("claimantId", other)
        assertFalse(SharedProtocol.containsEffect(different, effect))
        val credit = JSONObject().put("rootId", effect.getString("id")).put("memberId", me).put("acceptedAt", "2026-09-13T12:00:00Z")
        effect.put("firstCompletion", credit)
        val later = JSONObject(effect.toString()).put("lifecycleVersion", "3")
        assertTrue(SharedProtocol.containsEffect(later, effect))
        later.getJSONObject("firstCompletion").put("acceptedAt", "2026-09-20T12:00:00Z")
        assertFalse(SharedProtocol.containsEffect(later, effect))
    }

    private fun receipt(request: SharedRequest, task: JSONObject, code: String = "ACCEPTED"): JSONObject {
        val wire = JSONObject(request.envelope!!)
        return JSONObject().put("operationId", "${request.workspace.registration}:${wire.getString("sequence")}")
            .put("fingerprint", sha256(request.envelope)).put("code", code)
            .put("effectRevision", (request.workspace.revision.toULong() + 1u).toString()).put("task", task)
    }

    private fun task(title: String): JSONObject = JSONObject()
        .put("id", UUID.randomUUID().toString()).put("title", title).put("description", JSONObject.NULL)
        .put("titleVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
        .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
        .put("deletionVersion", "1").put("lifecycle", "OPEN").put("lifecycleVersion", "1")
        .put("claimantId", JSONObject.NULL).put("claimVersion", "1").put("hierarchyVersion", "1")
        .put("orderIntentVersion", "1").put("firstCompletion", JSONObject.NULL)

    private fun order(vararg ids: String) = JSONObject().put("id", "root-order").put("entityType", "ROOT_ORDER")
        .put("taskIds", JSONArray(ids.toList())).put("version", "1")

    private fun reply(request: SharedRequest, entities: List<JSONObject>, receipt: JSONObject? = null): JSONObject {
        val next = request.workspace.revision.toULong() + 1u
        val payload = JSONArray(entities).toString()
        return JSONObject().put("workspaceId", request.workspace.workspaceId).put("stateEpoch", request.workspace.epoch)
            .put("afterRevision", request.workspace.revision).put("throughRevision", next.toString())
            .put("headRevision", next.toString()).put("targetRevision", next.toString()).put("cursor", "cursor-$next")
            .put("hasMore", false).put("receipts", JSONArray().apply { receipt?.let { put(it) } })
            .put("membership", JSONObject().put("me", me).put("ownerId", me).put("members", JSONArray()
                .put(JSONObject().put("id", me).put("active", true).put("displayName", "Alice"))
                .put(JSONObject().put("id", other).put("active", true).put("displayName", "Bob"))))
            .put("groups", JSONArray().put(JSONObject().put("revision", next.toString()).put("partCount", 1)
                .put("digest", sha256(payload)).put("parts", JSONArray().put(JSONObject().put("partIndex", 0)
                    .put("entityIds", JSONArray(entities.map { it.getString("id") })).put("payload", payload)))))
    }

    private suspend fun fixture(action: suspend (InboxDatabase, SharedWorkspace, SharedRepository) -> Unit) {
        val name = "task-actions-${UUID.randomUUID()}.db"
        val db = InboxDatabase.open(context, name)
        val device = UUID.randomUUID().toString()
        val state = SharedWorkspace("scope", UUID.randomUUID().toString(), UUID.randomUUID().toString(), device, "Home")
        db.shared().saveWorkspace(state)
        try { action(db, state, SharedRepository(db, DataLease(), state.scope, device)) }
        finally { db.close(); context.deleteDatabase(name) }
    }
}
