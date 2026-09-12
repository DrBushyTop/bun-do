package fi.bundo

import android.app.Application
import fi.bundo.data.InboxDatabase
import fi.bundo.data.InboxRepository
import fi.bundo.data.RecordingStore
import fi.bundo.speech.VoiceController
import java.io.File
import fi.bundo.data.AudioExpiryWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class BunDoApplication : Application() {
    private val database by lazy { InboxDatabase.open(this) }
    val inbox by lazy { InboxRepository(database) }
    val voice by lazy {
        VoiceController(this, RecordingStore(database, File(noBackupFilesDir, "anonymous-audio")))
    }

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "anonymous-audio-expiry", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AudioExpiryWorker>(6, TimeUnit.HOURS).build(),
        )
    }
}
