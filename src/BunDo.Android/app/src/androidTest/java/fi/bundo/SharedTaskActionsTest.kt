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

    @Test fun offlineDeleteUndoAndRetryKeepExactDeletionDependenciesAcrossRestart() = runBlocking {
        fixture { db, state, repository ->
            val task = task("Dishes").put("description", "Keep the draft")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(task.getString("id")))))
            val sequence = repository.act("DeleteTask", task.toString())
            assertFalse(repository.taskStates.first().single().isNull("deletion"))
            repository.undoDelete(task.getString("id"), sequence)
            assertTrue(repository.taskStates.first().single().isNull("deletion"))
            val request = repository.prepare(1001, 1)!!
            val deleted = JSONObject(task.toString()).put("deletionVersion", "2")
                .put("deletion", deletion("${state.registration}:$sequence"))
            repository.apply(request, reply(request, listOf(deleted), receipt(request, deleted)))
            val restore = repository.prepare(1002, 1)!!
            val wire = JSONObject(restore.envelope!!)
            assertEquals("RestoreTask", wire.getString("command"))
            assertEquals("2", wire.getJSONObject("observedVersions").getJSONObject("deletion").getString("fieldVersion"))
            val name = checkNotNull(db.openHelper.databaseName)
            db.close()
            val reopened = InboxDatabase.open(context, name)
            try {
                val restarted = SharedRepository(reopened, DataLease(), state.scope, state.registration)
                assertEquals(restore.envelope, restarted.prepare(100_000, 2)!!.envelope)
                assertTrue(restarted.taskStates.first().single().isNull("deletion"))
                assertTrue(restarted.problems.first().isEmpty())
            } finally { reopened.close() }
        }
    }

    @Test fun remoteDeletionPreservesEditorAndRejectedTextAndPurgeAllowsCopyWithNewIdentity() = runBlocking {
        fixture { db, state, repository ->
            val task = task("Dishes")
            val id = task.getString("id")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(id))))
            val draft = repository.draft(id).copy(title = "My unsaved title", description = "My notes")
            repository.saveDraft(draft)
            val poll = repository.prepare(1001, 1)!!
            val deleted = JSONObject(task.toString()).put("deletionVersion", "2").put("deletion", deletion("remote:1"))
            repository.apply(poll, reply(poll, listOf(deleted)))
            assertEquals("My unsaved title", repository.draft(id).title)
            repository.commit(draft)
            assertEquals("Dishes", repository.taskStates.first().single().getString("title"))
            assertEquals("TASK_DELETED", repository.problems.first().single().problem)
            val edit = repository.prepare(1002, 1)!!
            repository.apply(edit, reply(edit, emptyList(), receipt(edit, deleted, "DELETION_CONFLICT")))
            assertEquals("My unsaved title", repository.problems.first().single().title)
            val purge = repository.prepare(1003, 1)!!
            val marker = JSONObject().put("id", id).put("entityType", "PURGED_TASK").put("version", "4")
            repository.apply(purge, reply(purge, listOf(marker)))
            assertTrue(repository.taskStates.first().isEmpty())
            val recovered = repository.copyText("My unsaved title", "My notes")
            assertNotEquals(id, recovered)
            assertEquals("My unsaved title", repository.tasks.first().single().title)
            assertEquals("My notes", db.shared().intents(state.scope).first().description)
        }
    }

    @Test fun undoAfterSyncRestoresOnlyTheDeletionThatProducedTheUndo() = runBlocking {
        fixture { _, state, repository ->
            val task = task("Dishes")
            val first = repository.prepare(1000, 1)!!
            repository.apply(first, reply(first, listOf(task, order(task.getString("id")))))
            val sequence = repository.act("DeleteTask", task.toString())
            val request = repository.prepare(1001, 1)!!
            val deleted = JSONObject(task.toString()).put("deletionVersion", "2").put("deletion", deletion("${state.registration}:$sequence"))
            repository.apply(request, reply(request, listOf(deleted), receipt(request, deleted)))
            repository.undoDelete(task.getString("id"), sequence)
            assertTrue(repository.taskStates.first().single().isNull("deletion"))
            val restore = repository.prepare(1002, 1)!!
            val restored = JSONObject(task.toString()).put("deletionVersion", "3")
            repository.apply(restore, reply(restore, listOf(restored), receipt(restore, restored)))
            val poll = repository.prepare(1003, 1)!!
            val again = JSONObject(task.toString()).put("deletionVersion", "4").put("deletion", deletion("remote:2"))
            repository.apply(poll, reply(poll, listOf(again)))
            assertTrue(runCatching { repository.undoDelete(task.getString("id"), sequence) }.isFailure)
            assertFalse(repository.taskStates.first().single().isNull("deletion"))
        }
    }

    @Test fun purgeMarkerRetiresAcceptedReceiptsAcrossLaterPullsWithoutResurrectingContent() = runBlocking {
        fixture { _, state, repository ->
            val id = repository.copyText("Old capture", "")
            val create = repository.prepare(1000, 1)!!
            val created = task("Old capture").put("id", id).put("description", "")
            repository.apply(create, reply(create, listOf(created, order(id)), receipt(create, created)))
            val sequence = repository.act("DeleteTask", created.toString())
            val delete = repository.prepare(1001, 1)!!
            val deleted = JSONObject(created.toString()).put("deletionVersion", "2")
                .put("deletion", deletion("${state.registration}:$sequence"))
            repository.apply(delete, reply(delete, listOf(deleted), receipt(delete, deleted)))
            val poll = repository.prepare(1002, 1)!!
            repository.apply(poll, reply(poll, listOf(JSONObject().put("id", id)
                .put("entityType", "PURGED_TASK").put("version", "3"))))
            assertTrue(repository.tasks.first().isEmpty())
            assertFalse(repository.canonical.first().containsKey(id))
            val later = repository.prepare(1003, 1)!!
            repository.apply(later, reply(later, emptyList()))
            assertTrue(repository.tasks.first().isEmpty())
            assertTrue(repository.problems.first().isEmpty())
        }
    }

    @Test fun checklistPreviewSurvivesRestartAndOfflineItemsDependOnSplitReceipts() = runBlocking {
        fixture { db, state, repository ->
            val root = task("Kitchen")
            val id = root.getString("id")
            val poll = repository.prepare(1000, 1)!!
            repository.apply(poll, reply(poll, listOf(root, order(id))))
            repository.saveChecklistDraft(repository.checklistDraft(id).copy(text = "Wash dishes\nDry dishes"))
            assertTrue(repository.drafts.first().isEmpty())
            val restarted = SharedRepository(db, DataLease(), state.scope, state.registration)
            assertEquals("Wash dishes\nDry dishes", restarted.checklistDraft(id).text)
            restarted.commitChecklist(id)
            val children = restarted.taskStates.first().filter { it.nullableString("parentId") == id }
            assertEquals(2, children.size)
            val child = children.single { it.getString("title") == "Wash dishes" }
            assertEquals(SharedProtocol.taskId(state.registration, "1", 1), child.getString("id"))
            restarted.commit(restarted.draft(child.getString("id")).copy(title = "Wash carefully"))
            val edited = restarted.taskStates.first().single { it.getString("id") == child.getString("id") }
            assertEquals("Wash carefully", edited.getString("title"))
            restarted.act("CompleteTask", edited.toString())
            assertEquals("OPEN", restarted.taskStates.first().single { it.getString("id") == id }.getString("lifecycle"))
            val request = restarted.prepare(1001, 1)!!
            val canonicalRoot = JSONObject(root.toString()).put("isChecklist", true).put("subtreeVersion", "2")
                .put("hierarchyVersion", "2").put("childOrder", JSONArray(children.map { it.getString("id") }))
            val canonicalChildren = children.map { item -> task(item.getString("title")).put("id", item.getString("id"))
                .put("parentId", id).apply {
                    for (group in listOf("title", "description")) put("${group}Version", JSONObject().put("fieldVersion", "2").put("humanVersion", "2"))
                    for (group in listOf("lifecycle", "claim", "hierarchy", "deletion", "orderIntent")) put("${group}Version", "2")
                } }
            val accepted = receipt(request, canonicalRoot).put("relatedTasks", JSONArray(listOf(canonicalRoot) + canonicalChildren))
            restarted.apply(request, reply(request, listOf(canonicalRoot) + canonicalChildren, accepted))
            val edit = restarted.prepare(1002, 1)!!
            val wire = JSONObject(edit.envelope!!)
            assertEquals("EditTask", wire.getString("command"))
            assertEquals("2", wire.getJSONObject("observedVersions").getJSONObject("title").getString("humanVersion"))
            assertEquals("Wash carefully", restarted.taskStates.first().single { it.getString("id") == child.getString("id") }.getString("title"))
            assertEquals("COMPLETED", restarted.taskStates.first().single { it.getString("id") == child.getString("id") }.getString("lifecycle"))
            assertTrue(restarted.problems.first().isEmpty())
        }
    }

    @Test fun offlineChecklistCascadesPreserveIndependentDeletionAndSnoozeBlocksChildren() = runBlocking {
        fixture { _, _, repository ->
            val root = task("Kitchen")
            val id = root.getString("id")
            val poll = repository.prepare(1000, 1)!!
            repository.apply(poll, reply(poll, listOf(root, order(id))))
            repository.saveChecklistDraft(repository.checklistDraft(id).copy(text = "One\nTwo"))
            repository.commitChecklist(id)
            suspend fun current(id: String) = repository.taskStates.first().single { it.getString("id") == id }
            val children = repository.taskStates.first().filter { it.nullableString("parentId") == id }.map { it.getString("id") }
            repository.act("DeleteTask", current(children[0]).toString())
            val independent = current(children[0]).getJSONObject("deletion").getString("groupId")
            val deletion = repository.act("DeleteTask", current(id).toString())
            assertTrue(repository.taskStates.first().all { !it.isNull("deletion") })
            repository.undoDelete(id, deletion)
            assertEquals(independent, current(children[0]).getJSONObject("deletion").getString("groupId"))
            assertTrue(current(children[1]).isNull("deletion"))
            repository.act("ClaimTask", current(children[1]).toString())
            repository.act("SetSnooze", current(id).toString(), until = "2099-01-01T00:00:00Z")
            assertTrue(current(children[1]).isNull("claimantId"))
            repository.act("CompleteTask", current(children[1]).toString())
            assertEquals("OPEN", current(children[1]).getString("lifecycle"))
            assertEquals("TASK_SNOOZED", repository.problems.first().single().problem)
        }
    }

    @Test fun staleChecklistPreviewKeepsRejectedTextWithoutPartialChildren() = runBlocking {
        fixture { _, _, repository ->
            val root = task("Kitchen")
            val id = root.getString("id")
            val poll = repository.prepare(1000, 1)!!
            repository.apply(poll, reply(poll, listOf(root, order(id))))
            repository.saveChecklistDraft(repository.checklistDraft(id).copy(text = "Keep this step"))
            val remote = repository.prepare(1001, 1)!!
            root.put("title", "Different task").put("titleVersion", JSONObject().put("fieldVersion", "2").put("humanVersion", "2"))
            repository.apply(remote, reply(remote, listOf(root)))
            repository.commitChecklist(id)
            assertEquals(1, repository.taskStates.first().size)
            assertEquals("Keep this step", repository.problems.first().single().description)
            assertEquals("FIELD_CONFLICT", repository.problems.first().single().problem)
        }
    }

    @Test fun rootEditsAndMovesSupplySubtreeVersionsToOfflineCascades() = runBlocking {
        for (move in listOf(false, true)) fixture { _, _, repository ->
            val root = task("Kitchen").put("isChecklist", true).put("subtreeVersion", "1")
            val id = root.getString("id")
            val child = task("One").put("parentId", id)
            root.put("childOrder", JSONArray().put(child.getString("id")))
            val poll = repository.prepare(1000, 1)!!
            repository.apply(poll, reply(poll, listOf(root, child, order(id))))
            if (move) repository.act("MoveTask", root.toString())
            else repository.commit(repository.draft(id).copy(title = "Kitchen edited"))
            repository.act("DeleteTask", repository.taskStates.first().single { it.getString("id") == id }.toString())
            val request = repository.prepare(1001, 1)!!
            val changed = JSONObject(root.toString()).put("subtreeVersion", "2")
            if (move) changed.put("orderIntentVersion", "2")
            else changed.put("title", "Kitchen edited").put("titleVersion", JSONObject().put("fieldVersion", "2").put("humanVersion", "2"))
            val accepted = receipt(request, changed).put("relatedTasks", JSONArray(listOf(changed, child)))
            repository.apply(request, reply(request, listOf(changed), accepted))
            assertTrue(repository.taskStates.first().all { !it.isNull("deletion") })
            assertTrue(repository.problems.first().isEmpty())
            val deletion = repository.prepare(1002, 1)!!
            val wire = JSONObject(deletion.envelope!!)
            assertEquals("DeleteTask", wire.getString("command"))
            assertEquals("2", wire.getJSONObject("observedVersions").getJSONObject("subtree").getString("fieldVersion"))
        }
    }

    @Test fun removalWithChecklistPreviewClearsFamilyCachesAndKeepsOnlyAuthoredItems() = runBlocking {
        fixture { db, state, repository ->
            val root = task("Remote kitchen").put("isChecklist", true).put("subtreeVersion", "1")
            val id = root.getString("id")
            val child = task("Remote step").put("parentId", id)
            root.put("childOrder", JSONArray().put(child.getString("id")))
            val poll = repository.prepare(1000, 1)!!
            repository.apply(poll, reply(poll, listOf(root, child, order(id))))
            repository.saveChecklistDraft(repository.checklistDraft(id).copy(text = "My queued step"))
            repository.commitChecklist(id)
            val add = repository.prepare(1001, 1)!!
            val changed = JSONObject(root.toString()).put("title", "Other remote title")
                .put("titleVersion", JSONObject().put("fieldVersion", "2").put("humanVersion", "2")).put("subtreeVersion", "2")
            val rejected = receipt(add, changed, "FIELD_CONFLICT").put("relatedTasks", JSONArray(listOf(changed, child)))
            repository.apply(add, reply(add, listOf(changed), rejected))
            repository.saveChecklistDraft(repository.checklistDraft(id).copy(text = "My unfinished step"))
            val request = repository.prepare(1002, 1)!!
            repository.block(request, "FORBIDDEN")
            assertEquals("FORBIDDEN", db.shared().workspace(state.scope)!!.blocked)
            assertTrue(db.shared().base(state.scope).isEmpty())
            assertTrue(repository.taskStates.first().isEmpty())
            val intent = db.shared().intents(state.scope).single()
            assertEquals("", intent.title)
            assertEquals("My queued step", intent.description)
            assertTrue(JSONObject(intent.receipt!!).isNull("task"))
            assertTrue(JSONObject(intent.receipt).isNull("relatedTasks"))
            val draft = db.shared().draft(state.scope, "checklist:$id")!!
            assertNull(draft.basis)
            assertEquals("", draft.title)
            assertEquals("My unfinished step", draft.description)
        }
    }

    private fun deletion(group: String) = JSONObject().put("groupId", group)
        .put("deletedAt", "2026-09-13T12:00:00Z").put("purging", false)

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
