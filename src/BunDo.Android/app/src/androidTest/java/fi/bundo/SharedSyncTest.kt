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
        val retry = restarted.prepare(100_000)
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
        val replacement = client.prepare(100_000)
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

    /** Deterministic wire peer. Live adapter checks are separate and use the same Android repository. */
    private class Server {
        val workspace = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        var revision = 0
        var rejectNextCreate = false
        val tasks = mutableMapOf<String, JSONObject>()
        private val receipts = mutableMapOf<String, JSONObject>()
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
