package fi.bundo

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.reminders.AndroidReminders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run after adb shell pm revoke fi.bundo android.permission.POST_NOTIFICATIONS.
 * Revocation kills the app, so it cannot be performed inside a running instrumentation test. */
@RunWith(AndroidJUnit4::class)
class ReminderPermissionDeniedTest {
    @get:Rule val account = IsolatedUiAccountRule()

    @Test fun actualRuntimePermissionDenialCancelsSchedulingWithoutWritingDeliveryLedger() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The general test suite may run with permission granted. The explicit denied run is mandatory slice evidence.
        org.junit.Assume.assumeFalse(AndroidReminders.permissionGranted(context))
        val data = (context.applicationContext as BunDoApplication).accounts.active.value!!
        ReminderCoordinator.updateSettings(data) { it.copy(enabled = true, permissionAsked = true) }
        val sink = AndroidReminders(context, data)
        sink.schedule(java.time.Instant.now().plusSeconds(3600))
        ReminderCoordinator(data, sink).reconcile()
        assertFalse(sink.permitted())
        assertTrue(data.database.reminders().deliveries().isEmpty())
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications
            .none { it.tag == "reminders:${data.lease.owner}" })
        val dump = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys alarm")
        val pending = android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().use { it.readText() }
            .lineSequence().count { it.trim() == "tag=*walarm*:fi.bundo.REMINDER_ALARM" }
        assertEquals(0, pending)
    }
}
