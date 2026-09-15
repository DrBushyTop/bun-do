package fi.bundo.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fi.bundo.BunDoApplication
import fi.bundo.MainActivity
import fi.bundo.R
import fi.bundo.data.AccountData
import fi.bundo.data.ReminderCoordinator
import fi.bundo.data.ReminderSink
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

internal class AndroidReminders(private val context: Context, private val data: AccountData) : ReminderSink {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val tag = "reminders:${data.lease.owner}"

    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminders_title),
        NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }) }

    override fun permitted() = permissionGranted(context)
    override fun cancel() { manager.cancel(tag, 1) }
    override fun visible() = manager.activeNotifications.any { it.tag == tag }

    override fun post(candidates: List<ReminderCandidate>, window: Long, silent: Boolean): Boolean {
        if (!permitted()) return false
        // A new window may alert; retries inside the same window only replace the existing post.
        val previous = manager.activeNotifications.firstOrNull { it.tag == tag }
        if (previous != null && previous.notification.extras.getLong("reminderWindow") != window) cancel()
        val content = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction("fi.bundo.REMINDERS")
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val generic = context.getString(R.string.reminders_waiting)
        val public = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.bun_do)
            .setContentTitle(context.getString(R.string.app_name)).setContentText(generic)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        val titles = candidates.take(5).map { it.task.title.take(120).replace('\n', ' ') }
        val more = candidates.size - titles.size
        val style = NotificationCompat.InboxStyle()
        titles.forEach(style::addLine)
        if (more > 0) style.setSummaryText(context.resources.getQuantityString(R.plurals.reminders_more, more, more))
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.bun_do).setContentTitle(generic)
            .setContentText(titles.first()).setStyle(style).setContentIntent(content)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setOnlyAlertOnce(true).setSilent(silent).setAutoCancel(true)
            .addExtras(android.os.Bundle().apply {
                // Bounded extras even when thousands of tasks became due while Android delayed us.
                putString("reminderEligibility", fingerprint(candidates.map { it.key }.toSet()))
                putLong("reminderWindow", window)
            }).build()
        // Permission can be revoked between the preceding check and this call.
        return try { manager.notify(tag, 1, notification); permitted() } catch (_: SecurityException) { cancel(); false }
    }

    private fun fingerprint(keys: Set<String>) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(keys.sorted().joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }

    private fun alarm(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction("fi.bundo.REMINDER_ALARM")
            .setData("bundo-reminder:${data.lease.owner}".toUri())
            .putExtra("owner", data.lease.owner).putExtra("generation", data.lease.generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun schedule(at: Instant?) {
        val pending = alarm()
        alarms.cancel(pending)
        if (at != null) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    companion object {
        const val CHANNEL = "household-reminders"
        fun permissionGranted(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun stop(context: Context, data: AccountData) {
            AndroidReminders(context, data).apply { cancel(); schedule(null) }
        }
    }
}

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BunDoApplication
        val data = app.accounts.matching(inputData.getString("owner"), inputData.getString("generation"))
            ?: return Result.success()
        return try {
            ReminderCoordinator(data, AndroidReminders(applicationContext, data)).reconcile()
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun request(context: Context, data: AccountData) {
            if (data.identity == null || !data.lease.active) return
            WorkManager.getInstance(context).enqueueUniqueWork("reminders-now:${data.lease.owner}",
                ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>()
                    .addTag("account:${data.lease.owner}")
                    .setInputData(workDataOf("owner" to data.lease.owner, "generation" to data.lease.generation)).build())
        }
        fun start(context: Context, data: AccountData) {
            if (data.identity == null) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders:${data.lease.owner}",
                ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
                    .addTag("account:${data.lease.owner}")
                    .setInputData(workDataOf("owner" to data.lease.owner, "generation" to data.lease.generation)).build())
            request(context, data)
        }
    }
}

/** System events carry no household content. The active account is restored through AccountStore. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accounts = (context.applicationContext as BunDoApplication).accounts
        val data = if (intent.action == "fi.bundo.REMINDER_ALARM")
            accounts.matching(intent.getStringExtra("owner"), intent.getStringExtra("generation"))
        else accounts.active.value
        data?.let { ReminderWorker.request(context, it) }
    }
}
