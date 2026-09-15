package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.ValidatedIdentity
import fi.bundo.reminders.ReminderCandidate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderAcknowledgementTest {
    private class Sink : ReminderSink {
        var posts = 0
        var shown = false
        override fun permitted() = true
        override fun cancel() { shown = false }
        override fun visible() = shown
        override fun schedule(at: Instant?) {}
        override fun post(candidates: List<ReminderCandidate>, window: Long, silent: Boolean): Boolean {
            posts++; shown = true; return true
        }
    }

    @Test fun offlineCreateEditSnoozeAndRestoreKeepTheirReminderIdentityAfterAcceptedReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "reminder-ack-${UUID.randomUUID()}"
        val db = InboxDatabase.open(context, "$name.db")
        val lease = DataLease(name)
        val registration = UUID.randomUUID().toString()
        val me = UUID.randomUUID().toString()
        val audio = java.io.File(context.cacheDir, name)
        val data = AccountData(ValidatedIdentity("test", "me"), lease, db,
            RecordingStore(db, audio, lease), null, registration, context)
        val state = SharedWorkspace("scope", UUID.randomUUID().toString(), UUID.randomUUID().toString(), registration, "Home",
            membership = JSONObject().put("me", me).put("members", JSONArray()
                .put(JSONObject().put("id", me).put("active", true))).toString())
        val repository = SharedRepository(db, lease, state.scope, registration)
        val sink = Sink()
        val coordinator = ReminderCoordinator(data, sink)
        val now = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        fun due(at: Instant): JSONObject {
            val local = at.atOffset(ZoneOffset.UTC)
            return JSONObject().put("kind", "DATE_TIME").put("localDate", local.toLocalDate().toString())
                .put("localTime", local.toLocalTime().toString()).put("zoneId", "UTC")
        }
        suspend fun current(id: String) = JSONObject(db.shared().task(state.scope, id)!!.snapshot)
        suspend fun key() = ReminderCoordinator.candidates(data, db.reminders().settings()!!).single().key
        suspend fun accept(id: String, groups: Set<String>) {
            val request = repository.prepare(android.os.SystemClock.elapsedRealtime(), 1)!!
            val task = current(id)
            val revision = (request.workspace.revision.toULong() + 1u).toString()
            for (group in groups) {
                if (group in listOf("title", "description", "due"))
                    task.put("${group}Version", JSONObject().put("fieldVersion", revision).put("humanVersion", revision))
                else task.put(if (group == "urgent") "urgencyVersion" else "${group}Version", revision)
            }
            task.optJSONObject("creation")?.put("acceptedAt", now.toString())
            task.optJSONObject("lastChange")?.put("source", "HUMAN")?.put("at", now.toString())
            val wire = JSONObject(request.envelope!!)
            val receipt = JSONObject().put("operationId", "$registration:${wire.getString("sequence")}")
                .put("fingerprint", sha256(request.envelope)).put("code", "ACCEPTED")
                .put("effectRevision", revision).put("task", task)
            val payload = JSONArray().put(task).toString()
            repository.apply(request, JSONObject().put("workspaceId", state.workspaceId).put("stateEpoch", state.epoch)
                .put("afterRevision", request.workspace.revision).put("throughRevision", revision)
                .put("headRevision", revision).put("targetRevision", revision).put("cursor", "cursor-$revision")
                .put("hasMore", false).put("receipts", JSONArray().put(receipt))
                .put("groups", JSONArray().put(JSONObject().put("revision", revision).put("partCount", 1)
                    .put("digest", sha256(payload)).put("parts", JSONArray().put(JSONObject().put("partIndex", 0)
                        .put("entityIds", JSONArray().put(id)).put("payload", payload))))))
        }
        try {
            db.shared().saveWorkspace(state)
            ReminderCoordinator.updateSettings(data) { it.copy(enabled = true) }
            val id = repository.commit(EditorDraft(InboxRepository.NEW_DRAFT, "Milk",
                details = JSONObject().put("due", due(now)).put("urgent", false).toString()))
            val createdKey = key()
            coordinator.reconcile(now)
            assertEquals(1, sink.posts)
            accept(id, SharedTaskActions.groups.toSet() + setOf("title", "description", "due"))
            assertEquals(createdKey, key())
            coordinator.reconcile(now.plusSeconds(901))
            assertEquals(1, sink.posts)

            val draft = repository.draft(id)
            repository.commit(draft.copy(details = JSONObject(draft.details!!).put("due", due(now.plusSeconds(1200))).toString()))
            val editedKey = key()
            assertNotEquals(createdKey, editedKey)
            coordinator.reconcile(now.plusSeconds(1200))
            assertEquals(2, sink.posts)
            accept(id, setOf("due"))
            assertEquals(editedKey, key())
            coordinator.reconcile(now.plusSeconds(2101))
            assertEquals(2, sink.posts)

            repository.act("SetSnooze", current(id).toString(), until = now.plusSeconds(3600).toString())
            val snoozedKey = key()
            coordinator.reconcile(now.plusSeconds(3600))
            assertEquals(3, sink.posts)
            accept(id, setOf("snooze"))
            assertEquals(snoozedKey, key())
            coordinator.reconcile(now.plusSeconds(4501))
            assertEquals(3, sink.posts)

            repository.act("DeleteTask", current(id).toString())
            accept(id, setOf("deletion"))
            repository.act("RestoreTask", current(id).toString())
            val restoredKey = key()
            coordinator.reconcile(now.plusSeconds(5400))
            assertEquals(4, sink.posts)
            accept(id, setOf("deletion"))
            assertEquals(restoredKey, key())
            coordinator.reconcile(now.plusSeconds(6301))
            assertEquals(4, sink.posts)
        } finally { data.revoke(); data.close(); context.deleteDatabase("$name.db"); audio.deleteRecursively() }
    }
}
