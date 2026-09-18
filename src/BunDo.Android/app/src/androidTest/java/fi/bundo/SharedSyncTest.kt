package fi.bundo

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private val databases = mutableListOf<InboxDatabase>()
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InboxDatabase::class.java)

    private data class Client(val database: InboxDatabase, val state: SharedWorkspace, val lease: DataLease,
        val repository: SharedRepository, val name: String)

    private suspend fun client(server: Server = Server(), name: String = "sync-${UUID.randomUUID()}.db",
        device: String = UUID.randomUUID().toString()): Client {
        names += name
        val db = InboxDatabase.open(context, name).also { databases += it }
        val key = "${server.workspace}/${server.epoch}/$device"
        val state = db.shared().workspace(key) ?: SharedWorkspace(key, server.workspace, server.epoch, device, "Home")
            .also { db.shared().saveWorkspace(it) }
        val lease = DataLease()
        return Client(db, state, lease, SharedRepository(db, lease, key, device), name)
    }

    @After fun close() { databases.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }
    private suspend fun Client.prepare(now: Long = 1000) = checkNotNull(repository.prepare(now, 1))
    private suspend fun Client.synchronize(server: Server) {
        repeat(30) {
            val request = prepare()
            if (!repository.apply(request, server.reply(request))) return
        }
        error("Sync did not finish")
    }

    @Test fun migrationKeepsLocalInboxAndDoesNotPromoteItsIntents() = runBlocking {
        val name = "sync-migrate-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 3).apply {
            execSQL("INSERT INTO inbox_tasks VALUES ('local', 'Keep me', '', 'Keep me', '', 1, 1)")
            execSQL("INSERT INTO inbox_intents (taskId, kind, title, description, createdAt) VALUES ('local', 'CaptureInboxTask', 'Keep me', '', 1)")
            close()
        }
        migrations.runMigrationsAndValidate(name, 5, true, InboxDatabase.MIGRATION_3_4, InboxDatabase.MIGRATION_4_5).close()
        val client = client(name = name)
        assertEquals("Keep me", client.database.inbox().task("local")!!.title)
        assertTrue(client.database.shared().intents(client.state.scope).isEmpty())
        assertNull(client.prepare().envelope)
    }

    @Test fun retainedSharedBuildsUpgradeWithoutRewritingPendingBytesOrRecordingDeadlines() = runBlocking {
        // First shared-data build and the last schema before workspace recording recovery.
        for (version in listOf(5, 11, 12, 14, 15)) {
            val name = "retained-upgrade-${UUID.randomUUID()}.db"
            names += name
            val frozen = " {\"pending\":\"exact original bytes\"} "
            migrations.createDatabase(name, version).use { db ->
                db.execSQL("INSERT INTO shared_intents (scope, sequence, taskId, kind, title, description, titleChanged, descriptionChanged, observedTitle, observedDescription, observedDeletion, captureContext, frozen, status) VALUES ('scope', '7', 'task', 'CreateTask', 'Kesken', 'Keep me', 1, 1, '0', '0', '0', '{}', ?, 'SUBMITTED')",
                    arrayOf(frozen))
                db.execSQL("INSERT INTO shared_drafts (scope, `key`, title, description, savedAt, basis) VALUES ('scope', 'new', 'Unfinished', 'Original draft', 123, '{}')")
                db.execSQL("INSERT INTO voice_recordings (id, createdAt, expiresAt, state, reason) VALUES ('recording', 100, 200, 'FAILED', 'INTERRUPTED')")
            }
            val db = InboxDatabase.open(context, name).also { databases += it }
            val intent = db.shared().intents("scope").single()
            assertEquals(frozen, intent.frozen)
            assertEquals("SUBMITTED", intent.status)
            assertEquals("7", intent.sequence)
            assertEquals("Kesken", intent.title)
            assertEquals("Keep me", intent.description)
            assertEquals("Unfinished", db.shared().allDrafts().single().title)
            assertEquals(200L, db.recordings().all().single().expiresAt)
            assertEquals(17, db.openHelper.readableDatabase.version)
            assertTrue(db.recordings().all().single().keepAudio)
        }
    }

    @Test fun editDuringInflightCreateStaysVisibleAndUsesAcceptedOutputVersions() = runBlocking {
        val server = Server()
        val client = client(server)
        val id = client.repository.commit(EditorDraft("new", "Original", ""))
        val request = client.prepare()
        val frozen = request.envelope
        val response = server.reply(request)
        client.repository.commit(EditorDraft(id, "Edited while offline", ""))
        client.repository.apply(request, response)
        assertEquals("Edited while offline", client.repository.tasks.first().single().title)
        val edit = client.prepare()
        val operation = JSONObject(checkNotNull(edit.envelope))
        assertEquals("1", operation.getJSONObject("observedVersions").getJSONObject("title").getString("humanVersion"))
        assertEquals(frozen, client.database.shared().intents(client.state.scope).first().frozen)
        client.repository.apply(edit, server.reply(edit))
        assertEquals("Edited while offline", server.tasks[id]!!.getString("title"))
    }

    @Test fun lostResponseAndProcessRestartRetryIdenticalFrozenBytes() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.commit(EditorDraft("new", "Durable", ""))
        val request = client.prepare()
        server.reply(request)
        client.database.close()
        val restarted = client(server, client.name, client.state.registration)
        val retry = restarted.prepare(200_000)
        assertEquals(request.envelope, retry.envelope)
        restarted.repository.apply(retry, server.reply(retry))
        assertEquals(1, server.revision)
        assertEquals("Durable", restarted.repository.tasks.first().single().title)
    }

    @Test fun incompleteLaterGroupRollsBackBaseReceiptCursorAndProjectionTogether() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.commit(EditorDraft("new", "Saved locally", ""))
        val request = client.prepare()
        val response = server.reply(request)
        response.put("throughRevision", "2").put("headRevision", "2").put("targetRevision", "2")
        response.getJSONArray("groups").put(JSONObject().put("revision", "2").put("partCount", 1).put("parts", JSONArray()))
        assertFails { client.repository.apply(request, response) }
        assertTrue(client.database.shared().base(client.state.scope).isEmpty())
        assertNull(client.database.shared().intents(client.state.scope).single().receipt)
        assertEquals("0", client.database.shared().workspace(client.state.scope)!!.revision)
        assertEquals("Saved locally", client.repository.tasks.first().single().title)
    }

    @Test fun receiptBeforeEffectsKeepsOptimisticTaskUntilBaseCatchesUp() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.commit(EditorDraft("new", "Still visible", ""))
        val request = client.prepare()
        val response = server.reply(request, limit = 0)
        assertTrue(client.repository.apply(request, response))
        assertEquals("Still visible", client.repository.tasks.first().single().title)
        assertEquals("0", client.database.shared().workspace(client.state.scope)!!.acknowledged)
        client.synchronize(server)
        assertEquals("1", client.database.shared().workspace(client.state.scope)!!.acknowledged)
        assertEquals("Still visible", client.repository.tasks.first().single().title)
    }

    @Test fun replacementWorkerAndRevokedAccountCannotApplyDelayedResponse() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.commit(EditorDraft("new", "Private", ""))
        val old = client.prepare()
        assertNull(client.repository.prepare(2000, 1))
        val replacement = client.prepare(200_000)
        assertFails { client.repository.apply(old, server.reply(old)) }
        assertEquals("0", client.database.shared().workspace(client.state.scope)!!.revision)
        client.lease.revoke()
        assertFails { client.repository.apply(replacement, server.reply(replacement)) }
        assertTrue(client.database.shared().base(client.state.scope).isEmpty())
    }

    @Test fun rejectedCreateConsumesDependentDiscardsButIndependentTaskContinues() = runBlocking {
        val server = Server().also { it.rejectNextCreate = true }
        val client = client(server)
        val id = client.repository.commit(EditorDraft("new", "Rejected create", ""))
        client.repository.commit(EditorDraft(id, "Dependent edit", ""))
        client.repository.commit(EditorDraft("new", "Independent", ""))
        client.synchronize(server)
        val intents = client.database.shared().intents(client.state.scope)
        assertEquals(listOf("REJECTED", "BLOCKED_DEPENDENCY", "ACCEPTED"), intents.map { it.status })
        assertEquals("DiscardBlockedIntent", JSONObject(intents[1].frozen!!).getString("command"))
        assertEquals("Dependent edit", intents[1].title)
        assertEquals("Independent", client.repository.tasks.first().single().title)
    }

    @Test fun replacementRegistrationNeverFreezesOldIntentWithNewIdentity() = runBlocking {
        val server = Server()
        val old = client(server)
        old.repository.commit(EditorDraft("new", "Old registration text", ""))
        val request = old.prepare()
        val replacement = client(server, old.name)
        replacement.database.shared().quarantineOtherRegistrations(replacement.state.registration)
        assertNull(replacement.prepare().envelope)
        val retained = replacement.database.shared().recovery().single()
        assertEquals("QUARANTINED", retained.status)
        assertEquals(request.envelope, retained.frozen)
        replacement.repository.copyText(retained.title, retained.description.orEmpty())
        val newIntent = replacement.database.shared().intents(replacement.state.scope).single()
        assertNotEquals(retained.taskId, newIntent.taskId)
        assertEquals("1", newIntent.sequence)
        assertEquals(retained.title, newIntent.title)
    }

    @Test fun conflictingOfflineEditsConvergeInBothReconnectOrdersAndKeepLosingText() = runBlocking {
        for (reverse in listOf(false, true)) {
            val server = Server()
            val a = client(server)
            val b = client(server)
            val id = a.repository.commit(EditorDraft("new", "Shared", ""))
            a.synchronize(server); b.synchronize(server)
            a.repository.commit(EditorDraft(id, "Alice edit", ""))
            b.repository.commit(EditorDraft(id, "Bob edit", ""))
            val first = if (reverse) b else a
            val second = if (reverse) a else b
            first.synchronize(server); second.synchronize(server); first.synchronize(server)
            assertEquals(first.repository.tasks.first(), second.repository.tasks.first())
            assertEquals(if (reverse) "Bob edit" else "Alice edit", server.tasks[id]!!.getString("title"))
            assertEquals(if (reverse) "Alice edit" else "Bob edit", second.database.shared().recovery().single().title)
        }
    }

    @Test fun explicitImportDoesNotDeleteAnUnfinishedHouseholdDraft() = runBlocking {
        val client = client()
        client.repository.saveDraft(EditorDraft("new", "Unfinished", ""))
        client.repository.copyText("Imported", "")
        assertEquals("Unfinished", client.repository.draft("new").title)
    }

    @Test fun pendingHumanEditRemainsVisibleOverIncomingAiOnlyChange() = runBlocking {
        val server = Server()
        val client = client(server)
        val id = client.repository.commit(EditorDraft("new", "Original", ""))
        client.synchronize(server)
        val pull = client.prepare()
        client.repository.commit(EditorDraft(id, "Human correction", ""))
        server.aiTitle(id, "AI cleanup")
        client.repository.apply(pull, server.reply(pull))
        assertEquals("Human correction", client.repository.tasks.first().single().title)
        assertTrue(client.repository.problems.first().isEmpty())
        client.synchronize(server)
        assertEquals("Human correction", server.tasks[id]!!.getString("title"))
    }

    @Test fun corruptedReceiptRollsBackEvenWhenEveryChangeGroupIsComplete() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.commit(EditorDraft("new", "Keep exact bytes", ""))
        val request = client.prepare()
        val reply = server.reply(request)
        reply.getJSONArray("receipts").getJSONObject(0).put("fingerprint", "invalid")
        assertFails { client.repository.apply(request, reply) }
        assertTrue(client.database.shared().base(client.state.scope).isEmpty())
        assertNull(client.database.shared().intents(client.state.scope).single().receipt)
        assertEquals("0", client.database.shared().workspace(client.state.scope)!!.revision)
        client.repository.release(request)
        val retry = client.prepare()
        assertEquals(request.envelope, retry.envelope)
        client.repository.apply(retry, server.reply(retry))
        assertEquals("Keep exact bytes", client.repository.tasks.first().single().title)
    }

    @Test fun persistedEditorNeverRebasesOnRemoteChangesArrivingBeforeSave() = runBlocking {
        for (sameField in listOf(false, true)) {
            val server = Server()
            val a = client(server)
            val b = client(server)
            val id = a.repository.commit(EditorDraft("new", "Original title", "Original description"))
            a.synchronize(server); b.synchronize(server)
            a.repository.saveDraft(a.repository.draft(id).copy(title = "My title"))
            b.repository.commit(EditorDraft(id, if (sameField) "Other title" else "Original title", "Other description"))
            b.synchronize(server); a.synchronize(server)
            a.database.close()
            val reopened = client(server, a.name, a.state.registration)
            reopened.repository.commit(reopened.repository.draft(id))
            reopened.synchronize(server)
            assertEquals("Other description", server.tasks[id]!!.getString("description"))
            assertEquals(if (sameField) "Other title" else "My title", server.tasks[id]!!.getString("title"))
            if (sameField) assertEquals("My title", reopened.database.shared().recovery().single().title)
        }
    }

    @Test fun unrelatedPrerequisiteFieldDoesNotAuthorizeOverwritingAnUnseenHumanEdit() = runBlocking {
        val server = Server()
        val a = client(server)
        val b = client(server)
        val id = a.repository.commit(EditorDraft("new", "Original title", "Original description"))
        a.synchronize(server); b.synchronize(server)
        a.repository.commit(EditorDraft(id, "My title", "Original description"))
        a.repository.commit(EditorDraft(id, "My title", "My description"))
        a.repository.commit(EditorDraft(id, "My second title", "My description"))
        val intents = a.database.shared().intents(a.state.scope)
        assertNull(intents[2].descriptionAfterSequence)
        assertEquals("2", intents[3].titleAfterSequence)
        b.repository.commit(EditorDraft(id, "Original title", "Other description"))
        b.synchronize(server); a.synchronize(server)
        assertEquals("Other description", server.tasks[id]!!.getString("description"))
        assertEquals("My title", server.tasks[id]!!.getString("title"))
        assertEquals(listOf("FIELD_CONFLICT", "BLOCKED_DEPENDENCY"), a.database.shared().recovery().map { it.problem })
    }

    @Test fun removalErasesCanonicalAndReceiptCachesButKeepsAuthoredText() = runBlocking {
        val server = Server()
        val a = client(server)
        val b = client(server)
        val id = b.repository.commit(EditorDraft("new", "Remote title", "Remote description"))
        b.synchronize(server); a.synchronize(server)
        a.repository.commit(EditorDraft(id, "My conflicting title", "Remote description"))
        b.repository.commit(EditorDraft(id, "Other remote title", "Remote description"))
        b.synchronize(server); a.synchronize(server)
        a.repository.saveDraft(a.repository.draft(id).copy(description = "My unfinished description"))
        val request = a.prepare()
        a.repository.block(request, "FORBIDDEN")
        assertTrue(a.database.shared().base(a.state.scope).isEmpty())
        assertTrue(a.repository.tasks.first().isEmpty())
        val recovery = a.database.shared().recovery().single()
        assertEquals("My conflicting title", recovery.title)
        assertNull(recovery.description)
        assertTrue(JSONObject(recovery.receipt!!).isNull("task"))
        val draft = a.database.shared().draft(a.state.scope, id)!!
        assertNull(draft.basis)
        assertEquals("", draft.title)
        assertEquals("My unfinished description", draft.description)
        assertFails { a.repository.saveDraft(EditorDraft(id, "Other remote title", "My later typing")) }
        assertEquals(draft, a.database.shared().draft(a.state.scope, id))
    }

    private suspend fun assertFails(action: suspend () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Expected response/lease validation failure", failed)
    }

    @Test fun snapshotResumesAfterEveryBoundaryAndIncludesEditsDuringDownload() = runBlocking {
        val server = Server()
        var client = client(server)
        val id = client.repository.copyText("Old view", "")
        client.synchronize(server)
        server.aiTitle(id, "Current shared view")
        val transport = SnapshotPeer(server).apply { rootOrder = listOf(id) }
        var recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        var request = client.prepare()
        recovery.begin(request)
        client.repository.release(request)
        transport.onChunk = { client.repository.copyText("Written during download", "Keep this too") }
        var finished = false
        repeat(20) {
            if (!finished) {
                request = client.prepare()
                finished = !kotlinx.coroutines.withTimeout(5000) { recovery.step(request, transport, Long.MAX_VALUE, 0) }
                client.repository.release(request)
                client.database.close()
                client = client(server, client.name, client.state.registration)
                recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
            }
        }
        assertTrue(finished)
        assertNotNull(client.database.shared().base(client.state.scope).find { it.id == SharedTaskActions.ORDER_ID })
        assertEquals(setOf("Current shared view", "Written during download"), client.repository.tasks.first().map { it.title }.toSet())
        client.synchronize(server)
        assertEquals(2, server.tasks.size)
    }

    @Test fun snapshotReplaysPendingSplitAndChildEditAsOneFamilyAcrossRestart() = runBlocking {
        val server = Server()
        var client = client(server)
        val id = client.repository.copyText("Kitchen", "")
        client.synchronize(server)
        val shared = client.database.shared().workspace(client.state.scope)!!
        client.database.shared().saveWorkspace(shared.copy(membership = JSONObject().put("me", UUID.randomUUID().toString()).toString()))
        client.repository.saveChecklistDraft(client.repository.checklistDraft(id).copy(text = "One\nTwo"))
        client.repository.commitChecklist(id)
        val child = client.repository.taskStates.first().first { it.nullableString("parentId") == id }
        val childId = child.getString("id")
        client.repository.commit(client.repository.draft(childId).copy(title = "Edited step"))
        client.repository.act("CompleteTask", client.repository.taskStates.first().single { it.getString("id") == childId }.toString())
        val transport = SnapshotPeer(server).apply { rootOrder = listOf(id) }
        var recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        var request = client.prepare()
        recovery.begin(request)
        client.repository.release(request)
        var finished = false
        repeat(24) {
            if (!finished) {
                request = client.prepare()
                finished = !recovery.step(request, transport, Long.MAX_VALUE, 0)
                client.repository.release(request)
                client.database.close()
                client = client(server, client.name, client.state.registration)
                recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
            }
        }
        assertTrue(finished)
        val projected = client.repository.taskStates.first()
        assertEquals(3, projected.size)
        assertEquals("Edited step", projected.single { it.getString("id") == childId }.getString("title"))
        assertEquals("COMPLETED", projected.single { it.getString("id") == childId }.getString("lifecycle"))
        assertEquals("OPEN", projected.single { it.getString("id") == id }.getString("lifecycle"))
        assertTrue(client.repository.problems.first().isEmpty())
    }

    @Test fun snapshotKeepsDeletedTaskHiddenAndPreservesPendingText() = runBlocking {
        val server = Server()
        val client = client(server)
        val id = client.repository.copyText("Before deletion", "")
        client.synchronize(server)
        val draft = client.repository.draft(id).copy(title = "My offline text")
        client.repository.commit(draft)
        server.tasks[id]!!.put("deletion", JSONObject().put("groupId", "remote:1")
            .put("deletedAt", "2026-09-13T12:00:00Z").put("purging", false))
        val request = client.prepare()
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(request)
        val transport = SnapshotPeer(server)
        repeat(16) { if (recovery.pending()) recovery.step(request, transport, Long.MAX_VALUE, 0) }
        client.repository.release(request)
        assertFalse(recovery.pending())
        assertFalse(client.repository.taskStates.first().single().isNull("deletion"))
        assertEquals("Before deletion", client.repository.taskStates.first().single().getString("title"))
        assertEquals("My offline text", client.repository.problems.first().single().title)
        assertEquals("TASK_DELETED", client.repository.problems.first().single().problem)
    }

    @Test fun lostCreateResponseThenPurgedSnapshotDoesNotResurrectAcceptedCreate() = runBlocking {
        val server = Server()
        val client = client(server)
        val oldId = client.repository.copyText("Keep as recovery only", "")
        val request = client.prepare()
        server.reply(request) // The response is lost, and later server state no longer contains the task.
        server.tasks.clear()
        val transport = SnapshotPeer(server)
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(request)
        repeat(12) { if (recovery.pending()) recovery.step(request, transport, Long.MAX_VALUE, 0) }
        client.repository.release(request)
        assertFalse(recovery.pending())
        assertTrue(client.repository.tasks.first().isEmpty())
        assertEquals("ACCEPTED", client.database.shared().intents(client.state.scope).single().status)
        client.synchronize(server)
        assertTrue(client.repository.tasks.first().isEmpty())
        assertNotEquals(oldId, client.repository.copyText("Explicit new copy", ""))
    }

    @Test fun snapshotStorageShortageCorruptionAndUnknownSchemaKeepOldViewAndJournal() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.copyText("Still here", "")
        client.synchronize(server)
        client.repository.copyText("Not uploaded", "")
        val request = client.prepare()
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(request)
        val transport = SnapshotPeer(server)
        try { recovery.step(request, transport, 0, 0); fail("Must preserve storage reserve") }
        catch (error: SyncFailure) { assertEquals("STORAGE_REQUIRED", error.code) }
        assertEquals(2, client.repository.tasks.first().size)
        transport.schema = 99
        try { recovery.step(request, transport, Long.MAX_VALUE, 0); fail("Must reject unknown schema") }
        catch (error: SyncFailure) { assertEquals("UNSUPPORTED_SNAPSHOT", error.code) }
        transport.schema = 1
        recovery.step(request, transport, Long.MAX_VALUE, 0)
        transport.corrupt = true
        assertFails { recovery.step(request, transport, Long.MAX_VALUE, 0) }
        assertEquals(0, client.database.shared().recoveryState(client.state.scope)!!.nextChunk)
        assertEquals(2, client.repository.tasks.first().size)
        assertEquals(2, client.database.shared().intents(client.state.scope).size)
        transport.corrupt = false
        repeat(12) { if (recovery.pending()) recovery.step(request, transport, Long.MAX_VALUE, 0) }
        assertFalse(recovery.pending())
        assertEquals(2, client.repository.tasks.first().size)
    }

    @Test fun expiredOutcomeIsQuarantinedInsteadOfReplayingTheCreate() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.copyText("Do not recreate automatically", "")
        val request = client.prepare()
        server.reply(request)
        server.tasks.clear()
        val transport = SnapshotPeer(server).apply { expiredOutcome = true }
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(request)
        repeat(12) { if (recovery.pending()) recovery.step(request, transport, Long.MAX_VALUE, 0) }
        client.repository.release(request)
        assertTrue(client.repository.tasks.first().isEmpty())
        val intent = client.database.shared().intents(client.state.scope).single()
        assertEquals("QUARANTINED", intent.status)
        assertEquals("Do not recreate automatically", intent.title)
        assertNull(client.prepare().envelope)
    }

    @Test fun snapshotLateChunkCannotApplyAfterWorkerReplacement() = runBlocking {
        val server = Server()
        val client = client(server)
        client.repository.copyText("Original", "")
        client.synchronize(server)
        val old = client.prepare()
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(old)
        val transport = SnapshotPeer(server)
        recovery.step(old, transport, Long.MAX_VALUE, 0)
        transport.onChunk = { client.prepare(200_000) }
        assertFails { recovery.step(old, transport, Long.MAX_VALUE, 0) }
        assertEquals(0, client.database.shared().recoveryState(client.state.scope)!!.nextChunk)
        assertEquals("Original", client.repository.tasks.first().single().title)
    }

    @Test fun migrationToGenerationsPreservesExistingSharedRowsAndFrozenBytes() = runBlocking {
        val name = "recovery-migrate-${UUID.randomUUID()}.db"
        names += name
        migrations.createDatabase(name, 5).apply {
            execSQL("INSERT INTO shared_base VALUES ('scope', 'id', 'base bytes')")
            execSQL("INSERT INTO shared_projection VALUES ('scope', 'id', 'projection bytes')")
            close()
        }
        migrations.runMigrationsAndValidate(name, 6, true, InboxDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT generation, snapshot FROM shared_base").use {
                assertTrue(it.moveToFirst()); assertEquals("initial", it.getString(0)); assertEquals("base bytes", it.getString(1))
            }
            db.query("SELECT generation, snapshot FROM shared_projection").use {
                assertTrue(it.moveToFirst()); assertEquals("initial", it.getString(0)); assertEquals("projection bytes", it.getString(1))
            }
        }
    }

    @Test fun recoveryReapplyUsesDisplayedCurrentVersionsAndDismissNeverRemovesText() = runBlocking {
        val server = Server()
        val alice = client(server)
        val bob = client(server)
        val id = alice.repository.copyText("Original", "Original description")
        alice.synchronize(server); bob.synchronize(server)
        alice.repository.draft(id)
        bob.repository.commit(EditorDraft(id, "Other person", "Original description"))
        bob.synchronize(server)
        alice.repository.commit(EditorDraft(id, "My retained edit", "Original description"))
        alice.synchronize(server)
        val rejected = alice.repository.problems.first().single()
        val displayed = alice.repository.canonical.first().getValue(id)
        server.aiTitle(id, "Newer shared value")
        alice.synchronize(server)
        assertFails { alice.repository.reapply(rejected.sequence, displayed) }
        val latest = alice.repository.canonical.first().getValue(id)
        alice.repository.reapply(rejected.sequence, latest)
        alice.synchronize(server)
        assertEquals("My retained edit", server.tasks[id]!!.getString("title"))
        assertEquals("Original description", server.tasks[id]!!.getString("description"))
        val old = alice.database.shared().intents(alice.state.scope).find { it.sequence == rejected.sequence }!!
        assertEquals("DISMISSED", old.status)
        assertEquals("My retained edit", old.title)
        assertTrue(alice.repository.problems.first().isEmpty())
    }

    @Test fun expiredOutcomeWithUnsentSuffixRequiresExplicitRegistrationReplacement() = runBlocking {
        val server = Server()
        val client = client(server)
        val id = client.repository.copyText("Old create", "")
        val request = client.prepare()
        server.reply(request)
        client.repository.commit(EditorDraft(id, "Unsent edit", ""))
        val recovery = SharedSnapshotRecovery(client.database, client.lease, client.state.scope)
        recovery.begin(request)
        val transport = SnapshotPeer(server).apply { expiredOutcome = true }
        repeat(12) {
            if (recovery.pending()) {
                recovery.step(request, transport, Long.MAX_VALUE, 0)
                if (client.database.shared().intents(client.state.scope).any { it.sequence == "2" && it.status == "QUARANTINED" })
                    client.repository.dismiss("2")
            }
        }
        client.repository.release(request)
        assertNull(client.repository.prepare(200_000, 1))
        assertEquals("REGISTRATION_REPLACEMENT_REQUIRED", client.repository.workspace.first()!!.blocked)
        assertTrue(client.database.shared().recovery().any { it.title == "Unsent edit" })
    }

    private class SnapshotPeer(val server: Server) : SnapshotTransport {
        private val revision = server.revision.toString()
        private val count = server.tasks.size
        private val bytes = JSONObject().put("schemaVersion", 1).put("tasks", JSONArray().apply {
            server.tasks.values.forEach { put(JSONObject(it.toString())) }
        }).toString().toByteArray(Charsets.UTF_8)
        var schema = 1
        var corrupt = false
        var expiredOutcome = false
        var rootOrder: List<String>? = null
        var onChunk: (suspend () -> Unit)? = null
        override suspend fun manifest(state: SharedWorkspace, id: String) = JSONObject()
            .put("snapshotId", id).put("workspaceId", server.workspace).put("stateEpoch", server.epoch)
            .put("revision", revision).put("schemaVersion", schema).put("expiresAt", java.time.Instant.now().plusSeconds(1800).toString())
            .put("totalBytes", if (count == 0) 0 else bytes.size).put("documentCount", count)
            .put("cursor", revision).put("chunks", JSONArray().apply { if (count > 0) put(JSONObject().put("index", 0).put("bytes", bytes.size)
                .put("documents", count).put("digest", SharedSnapshotRecovery.digest(bytes))) })
            .apply { rootOrder?.let { put("rootOrder", JSONArray(it)) } }
        override suspend fun chunk(state: SharedWorkspace, id: String, index: Int): ByteArray {
            onChunk?.invoke(); onChunk = null
            return if (corrupt) "corrupt".toByteArray() else bytes
        }
        override suspend fun outcomes(state: SharedWorkspace, first: String, count: Int): JSONObject {
            val receipt = server.receipts["${state.registration}:$first"]
            val outcome = JSONObject().put("sequence", first).put("state", when {
                expiredOutcome -> "OUTCOME_EXPIRED"; receipt == null -> "NOT_SEEN"; receipt.getString("code") == "ACCEPTED" -> "ACCEPTED"; else -> "REJECTED"
            })
            if (receipt != null && !expiredOutcome) outcome.put("receipt", receipt).put("fingerprint", receipt.getString("fingerprint"))
                .put("effectRevision", receipt.getString("effectRevision"))
            val highWater = server.receipts.keys.filter { it.startsWith("${state.registration}:") }.maxOfOrNull { it.substringAfter(':').toULong() } ?: 0u
            return JSONObject().put("code", "ACCEPTED").put("workspaceId", state.workspaceId).put("stateEpoch", state.epoch)
                .put("deviceId", state.registration).put("highWater", highWater.toString()).put("outcomes", JSONArray().put(outcome))
        }
    }

    /** Deterministic wire peer. Live adapter checks are separate and use the same Android repository. */
    private class Server {
        val workspace = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        var revision = 0
        var rejectNextCreate = false
        val tasks = mutableMapOf<String, JSONObject>()
        val receipts = mutableMapOf<String, JSONObject>()
        private val groups = mutableListOf<JSONObject>()
        fun aiTitle(id: String, title: String) {
            revision++
            val task = checkNotNull(tasks[id])
            val human = task.human("title")
            task.put("title", title).put("titleVersion", version().put("humanVersion", human))
            val payload = JSONArray().put(task).toString()
            groups += JSONObject().put("revision", revision.toString()).put("partCount", 1)
                .put("digest", sha256(payload)).put("parts", JSONArray().put(JSONObject()
                    .put("partIndex", 0).put("entityIds", JSONArray().put(id)).put("payload", payload)))
        }
        fun reply(request: SharedRequest, limit: Int = 100): JSONObject {
            var receipt: JSONObject? = null
            request.envelope?.let { bytes ->
                val operation = JSONObject(bytes)
                val opId = "${operation.getString("deviceId")}:${operation.getString("sequence")}"
                receipt = receipts[opId]
                if (receipt == null) {
                    revision++
                    val payload = operation.getJSONObject("payload")
                    var task = payload.optString("taskId").let { tasks[it] }?.let { JSONObject(it.toString()) }
                    var code = "ACCEPTED"
                    val changes = JSONArray()
                    when (operation.getString("command")) {
                        "CreateTask" -> if (rejectNextCreate) { code = "TASK_LIMIT"; rejectNextCreate = false }
                        else {
                            task = JSONObject().put("id", payload.getString("taskId"))
                                .put("title", payload.getString("title")).put("description", payload.get("description"))
                                .put("titleVersion", version()).put("descriptionVersion", version())
                                .put("deletionVersion", revision.toString())
                                .put("capture", JSONObject().put("title", payload.getString("title"))
                                    .put("description", payload.get("description")).put("context", operation.getJSONObject("occurredAtContext")))
                            changes.put(task)
                        }
                        "EditTask" -> {
                            val current = checkNotNull(task)
                            val observed = operation.getJSONObject("observedVersions")
                            if (listOf("title", "description").any { field -> payload.has(field) &&
                                current.human(field) != observed.getJSONObject(field).getString("humanVersion") }) code = "FIELD_CONFLICT"
                            else {
                                for (field in listOf("title", "description")) if (payload.has(field)) {
                                    current.put(field, payload.get(field)).put("${field}Version", version())
                                }
                                changes.put(current)
                            }
                        }
                        else -> code = "BLOCKED_DEPENDENCY"
                    }
                    if (task != null && code == "ACCEPTED") tasks[task!!.getString("id")] = JSONObject(task.toString())
                    val body = changes.toString()
                    groups += JSONObject().put("revision", revision.toString()).put("partCount", 1)
                        .put("digest", sha256(body)).put("parts", JSONArray().put(JSONObject().put("partIndex", 0)
                            .put("entityIds", JSONArray().apply {
                                for (index in 0 until changes.length()) put(changes.getJSONObject(index).getString("id"))
                            }).put("payload", body)))
                    receipt = JSONObject().put("operationId", opId).put("fingerprint", sha256(bytes))
                        .put("code", code).put("effectRevision", revision.toString()).put("task", task ?: JSONObject.NULL)
                        .also { receipts[opId] = it }
                }
            }
            val after = request.workspace.revision.toInt()
            val returned = groups.drop(after).take(limit)
            val through = after + returned.size
            return JSONObject().put("code", receipt?.getString("code") ?: "ACCEPTED")
                .put("workspaceId", workspace).put("stateEpoch", epoch).put("afterRevision", after.toString())
                .put("throughRevision", through.toString()).put("headRevision", revision.toString()).put("targetRevision", revision.toString())
                .put("cursor", through.toString()).put("hasMore", through < revision)
                .put("receipts", JSONArray().apply { receipt?.let { put(JSONObject(it.toString())) } })
                .put("groups", JSONArray().apply { returned.forEach { put(JSONObject(it.toString())) } })
        }
        private fun version() = JSONObject().put("fieldVersion", revision.toString()).put("humanVersion", revision.toString())
    }
}
