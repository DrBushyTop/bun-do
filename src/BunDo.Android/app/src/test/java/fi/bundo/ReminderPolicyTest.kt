package fi.bundo

import fi.bundo.reminders.ReminderPolicy
import fi.bundo.reminders.ReminderTask
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderPolicyTest {
    private val now = Instant.parse("2026-09-15T10:00:00Z")
    private fun task(id: String = "task") = ReminderTask("household/epoch/device", id, "Milk", now, "1")
    private fun candidates(vararg tasks: ReminderTask) = ReminderPolicy.candidates(tasks.toList(), "me", false, 1)

    @Test fun completedDeletedCancelledAndOtherClaimsAreSuppressed() {
        assertTrue(candidates(task().copy(open = false), task().copy(deleted = true), task().copy(claimant = "other")).isEmpty())
        assertEquals(2, candidates(task("a"), task("b").copy(claimant = "me")).size)
        assertEquals(1, ReminderPolicy.candidates(listOf(task().copy(claimant = "other")), "me", true, 1).size)
    }

    @Test fun checklistSnoozeDelaysChildrenAndOrphansNeverNotify() {
        val until = now.plusSeconds(3600)
        val rows = candidates(task("parent").copy(snoozeAt = until), task("child").copy(parentId = "parent"))
        assertTrue(ReminderPolicy.missed(rows, emptySet(), now).isEmpty())
        assertEquals(until, ReminderPolicy.next(rows, now))
        assertEquals(2, ReminderPolicy.missed(rows, emptySet(), until).size)
        assertTrue(candidates(task("child").copy(parentId = "missing")).isEmpty())
        assertTrue(candidates(task("parent").copy(deleted = true), task("child").copy(parentId = "parent")).isEmpty())
    }

    @Test fun delayedWorkSummarizesOnlyLastDayInStableOrderAndLedgerSuppressesRetries() {
        val rows = candidates(task("b"), task("a"), task("old").copy(dueAt = now.minusSeconds(86_401)),
            task("edge").copy(dueAt = now.minusSeconds(86_400)), task("future").copy(dueAt = now.plusSeconds(60)))
        val missed = ReminderPolicy.missed(rows, emptySet(), now)
        assertEquals(listOf("edge", "a", "b"), missed.map { it.task.id })
        assertTrue(ReminderPolicy.missed(rows, missed.map { it.key }.toSet(), now).isEmpty())
        assertEquals(now.plusSeconds(60), ReminderPolicy.next(rows, now))
    }

    @Test fun identityIncludesDueSnoozeDeleteRestoreSettingsAndAccountWorkspaceScope() {
        val original = task()
        val key = candidates(original).single().key
        for (changed in listOf(original.copy(dueRevision = "2"), original.copy(snoozeRevision = "2"),
            original.copy(deletionRevision = "2"), original.copy(scope = "other/epoch/device"),
            original.copy(dueAt = now.plusSeconds(60)), original.copy(snoozeAt = now.plusSeconds(60)))) {
            assertNotEquals(key, candidates(changed).single().key)
        }
        assertNotEquals(key, ReminderPolicy.candidates(listOf(original), "me", false, 2).single().key)
    }

    @Test fun dateOnlyUsesSavedZoneGapForwardAndEarlierOverlap() {
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), ReminderPolicy.dateOnly("2026-03-29", "03:30", "Europe/Helsinki"))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), ReminderPolicy.dateOnly("2026-10-25", "03:30", "Europe/Helsinki"))
        assertEquals(Instant.parse("2026-09-15T06:00:00Z"), ReminderPolicy.dateOnly("2026-09-15", "09:00", "Europe/Helsinki"))
    }

    @Test fun snoozeWithoutDeadlineAndCachedOccurrencesUseSameScheduler() {
        val rows = candidates(task("occurrence").copy(dueAt = null, snoozeAt = now))
        assertEquals(listOf("occurrence"), ReminderPolicy.missed(rows, emptySet(), now).map { it.task.id })
    }
}
