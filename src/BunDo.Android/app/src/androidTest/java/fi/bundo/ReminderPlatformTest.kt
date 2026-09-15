package fi.bundo

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.ValidatedIdentity
import fi.bundo.reminders.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderPlatformTest {
    private suspend fun await(check: () -> Boolean) = withTimeout(5000) {
        while (!check()) delay(50)
    }

    @Test fun privateBoundedSummaryReplacesRetriesAndRevocationCancelsItsAlarmAndNotification() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val name = "reminder-platform-${UUID.randomUUID()}"
        val db = InboxDatabase.open(context, "$name.db")
        val lease = DataLease(name)
        val audio = java.io.File(context.cacheDir, name)
        val data = AccountData(ValidatedIdentity("test", "me"), lease, db,
            RecordingStore(db, audio, lease), null, "registration", context)
        try {
            val sink = AndroidReminders(context, data)
            val now = Instant.now()
            val candidates = ReminderPolicy.candidates((1..1000).map {
                ReminderTask("household", "$it", "Private household title $it " + "x".repeat(200), now, "1")
            }, "me", true, 1)
            assertTrue(sink.permitted())
            assertTrue(sink.post(candidates, 123, silent = false))
            assertTrue(sink.post(candidates, 123, silent = true))
            val manager = context.getSystemService(NotificationManager::class.java)
            await { manager.activeNotifications.any { it.tag == "reminders:$name" } }
            val posted = manager.activeNotifications.filter { it.tag == "reminders:$name" }
            assertEquals(1, posted.size)
            val notification = posted.single().notification
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(5, notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
            assertTrue(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.all { it.length <= 120 })
            assertFalse(notification.publicVersion.extras.toString().contains("Private household"))
            assertNotEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertTrue(notification.extras.getString("reminderEligibility")!!.length == 64)
            sink.cancel()
            await { manager.activeNotifications.none { it.tag == "reminders:$name" } }
            assertTrue(sink.post(candidates, 124, silent = false))
            fun pendingAlarms(): Int {
                val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys alarm")
                return android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().use { it.readText() }
                    .lineSequence().count { it.trim() == "tag=*walarm*:fi.bundo.REMINDER_ALARM" }
            }
            val before = pendingAlarms()
            sink.schedule(now.plusSeconds(3600))
            assertEquals(before + 1, pendingAlarms())
            data.revoke()
            await { manager.activeNotifications.none { it.tag == "reminders:$name" } }
            assertEquals(before, pendingAlarms())
        } finally { data.revoke(); data.close(); context.deleteDatabase("$name.db"); audio.deleteRecursively() }
    }
}
