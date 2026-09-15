package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.ValidatedIdentity
import fi.bundo.reminders.ReminderCandidate
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private class Sink : ReminderSink {
        var allowed = true
        var posts = 0
        var alerts = 0
        var visible = emptySet<String>()
        var next: Instant? = null
        var beforePost: (() -> Unit)? = null
        override fun permitted() = allowed
        override fun cancel() { visible = emptySet() }
        override fun visible() = visible.isNotEmpty()
        override fun post(candidates: List<ReminderCandidate>, window: Long, silent: Boolean): Boolean {
            beforePost?.invoke()
            posts++
            if (!silent) alerts++
            visible = candidates.map { it.key }.toSet()
            return true
        }
        override fun schedule(at: Instant?) { next = at }
    }
    private suspend fun fixture(block: suspend (AccountData, SharedWorkspace, Sink) -> Unit) {
        val name = "reminder-test-${UUID.randomUUID()}.db"
        val db = InboxDatabase.open(context, name)
        val lease = DataLease("reminder-test-${UUID.randomUUID()}")
        val audio = java.io.File(context.cacheDir, lease.owner)
        val data = AccountData(ValidatedIdentity("test", "me"), lease, db, RecordingStore(db, audio, lease),
            null, "registration", context)
        val state = SharedWorkspace("household/epoch/registration", "household", "epoch", "registration", "Home",
            membership = JSONObject().put("me", "me").put("members", JSONArray()
                .put(JSONObject().put("id", "me").put("active", true))
                .put(JSONObject().put("id", "other").put("active", true))).toString())
        try {
            db.shared().saveWorkspace(state)
            ReminderCoordinator.updateSettings(data) { it.copy(enabled = true) }
            block(data, state, Sink())
        } finally { data.revoke(); data.close(); context.deleteDatabase(name); audio.deleteRecursively() }
    }
    private fun task(id: String = "milk", due: Instant = now) = JSONObject()
        .put("id", id).put("title", "Buy milk").put("lifecycle", "OPEN")
        .put("due", JSONObject().put("kind", "DATE_TIME").put("instant", due.toString()))
        .put("dueVersion", JSONObject().put("fieldVersion", "1")).put("deletionVersion", "1").put("snoozeVersion", "1")
    private suspend fun put(data: AccountData, state: SharedWorkspace, task: JSONObject) = data.lease.access {
        data.database.shared().saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
    }

    @Test fun denialThenGrantDuplicateWorkersRestartAndRevocation() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task())
            val coordinator = ReminderCoordinator(data, sink)
            sink.allowed = false
            coordinator.reconcile(now)
            assertEquals(0, sink.posts); assertNull(sink.next)
            assertTrue(data.database.reminders().deliveries().isEmpty())
            sink.allowed = true
            coroutineScope { List(4) { launch { coordinator.reconcile(now) } }.joinAll() }
            assertEquals(1, sink.posts)
            ReminderCoordinator(data, sink).reconcile(now)
            assertEquals(1, sink.posts)
            sink.allowed = false
            coordinator.reconcile(now)
            assertTrue(sink.visible.isEmpty()); assertNull(sink.next)
        }
    }

    @Test fun disconnectedCompletionUsesCachedStateThenSyncCancelsVisibleReminder() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task())
            ReminderCoordinator(data, sink).reconcile(now)
            assertEquals(1, sink.posts) // The device cannot know an unreceived remote completion.
            put(data, state, task().put("lifecycle", "COMPLETED"))
            ReminderCoordinator(data, sink).reconcile(now)
            assertTrue(sink.visible.isEmpty())
            assertEquals(1, sink.posts)
        }
    }

    @Test fun deleteRestoreAndSnoozedChecklistRecheckPersistedProjection() = runBlocking {
        fixture { data, state, sink ->
            val coordinator = ReminderCoordinator(data, sink)
            put(data, state, task().put("deletion", JSONObject().put("groupId", "delete")))
            coordinator.reconcile(now); assertEquals(0, sink.posts)
            put(data, state, task().put("deletionVersion", "3"))
            coordinator.reconcile(now); assertEquals(1, sink.posts)
            val until = now.plusSeconds(3600)
            put(data, state, task().put("snoozedUntil", until.toString()).put("snoozeVersion", "2"))
            put(data, state, task("child").put("parentId", "milk"))
            coordinator.reconcile(now)
            assertTrue(sink.visible.isEmpty()); assertEquals(until, sink.next)
            coordinator.reconcile(until)
            assertEquals(2, sink.posts); assertEquals(2, sink.visible.size)
        }
    }

    @Test fun removedMembershipAndStaleAccountCallbacksCannotPost() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task())
            data.database.shared().saveWorkspace(state.copy(blocked = "FORBIDDEN"))
            ReminderCoordinator(data, sink).reconcile(now)
            assertEquals(0, sink.posts)
            data.database.shared().saveWorkspace(state)
            data.lease.revoke()
            try { ReminderCoordinator(data, sink).reconcile(now); fail("Revoked callback ran") }
            catch (_: CancellationException) { }
            assertEquals(0, sink.posts)
        }
    }

    @Test fun delayedBatchAndDateOnlySettingsUsePersistedValues() = runBlocking {
        fixture { data, state, sink ->
            repeat(9) { put(data, state, task("task-$it", now.minusSeconds(it * 60L))) }
            put(data, state, task("old", now.minusSeconds(86_401)))
            ReminderCoordinator(data, sink).reconcile(now)
            assertEquals(1, sink.posts); assertEquals(9, sink.visible.size)
            ReminderCoordinator.updateSettings(data) { it.copy(dateOnlyTime = "12:00", allTasks = true) }
            assertEquals("12:00", data.database.reminders().settings()!!.dateOnlyTime)
            assertEquals(2, data.database.reminders().settings()!!.revision)
        }
    }

    @Test fun successiveSyncBatchesReplaceSilentlyAndDismissalSurvivesCoordinatorRestart() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task("page-one"))
            ReminderCoordinator(data, sink).reconcile(now)
            assertEquals(1, sink.alerts)
            put(data, state, task("page-two"))
            ReminderCoordinator(data, sink).reconcile(now.plusSeconds(5))
            assertEquals(2, sink.posts); assertEquals(1, sink.alerts)
            assertEquals(2, sink.visible.size)
            sink.cancel() // User dismisses the summary.
            put(data, state, task("page-three"))
            ReminderCoordinator(data, sink).reconcile(now.plusSeconds(10))
            assertEquals(2, sink.posts); assertTrue(sink.visible.isEmpty())
            ReminderCoordinator(data, sink).reconcile(now.plusSeconds(901))
            assertEquals(2, sink.posts) // Suppressed pages do not form a later catch-up storm.
            put(data, state, task("next-window", now.plusSeconds(902)))
            ReminderCoordinator(data, sink).reconcile(now.plusSeconds(902))
            assertEquals(3, sink.posts); assertEquals(2, sink.alerts)
        }
    }

    @Test fun invalidatedSummaryCannotBeRecreatedByAnotherPageInTheSameWindow() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task("first"))
            ReminderCoordinator(data, sink).reconcile(now)
            put(data, state, task("first").put("lifecycle", "COMPLETED"))
            put(data, state, task("second"))
            ReminderCoordinator(data, sink).reconcile(now.plusSeconds(5))
            assertEquals(1, sink.posts); assertTrue(sink.visible.isEmpty())
        }
    }

    @Test fun notificationEffectAndAccountRevocationHaveDefiniteOrder() = runBlocking {
        fixture { data, state, sink ->
            put(data, state, task())
            val entered = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            sink.beforePost = { entered.countDown(); check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val job = async(Dispatchers.IO) { runCatching { ReminderCoordinator(data, sink).reconcile(now) } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            val revoke = async(Dispatchers.IO) { data.lease.revoke(); sink.cancel() }
            finish.countDown()
            job.await(); revoke.await()
            assertTrue(sink.visible.isEmpty())
        }
    }
}
