package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxDatabase
import fi.bundo.data.ModelInstaller
import fi.bundo.data.RecordingStore
import fi.bundo.speech.LocalSpeech
import fi.bundo.speech.loadSpeechManifest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Local opt-in only. Personal samples and transcripts never enter APK assets or test logs. */
@RunWith(AndroidJUnit4::class)
class HumanSpeechDeviceTest {
    @Test fun privateFinnishSamplesCommitAndRemoveWorkingAudio() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("privateSpeechFixtures") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "private-speech-fixtures")
        val report = File(context.cacheDir, "private-speech-results.json")
        val installer = ModelInstaller(File(context.noBackupFilesDir, "speech-models"), loadSpeechManifest(context))
        val model = checkNotNull(installer.verifiedActive()) { "Pinned speech model is not installed" }
        val name = "private-speech-${UUID.randomUUID()}.db"
        val database = InboxDatabase.open(context, name)
        val audio = File(context.cacheDir, name).apply { mkdirs() }
        val results = JSONArray()
        try {
            val store = RecordingStore(database, audio)
            for (number in 1..5) {
                val sample = "fi-0$number"
                val source = File(input, "$sample.pcm")
                check(source.isFile && source.length() in 2..RecordingStore.MAX_AUDIO_BYTES)
                val recording = store.begin()
                source.copyTo(store.audio(recording.id))
                val text = LocalSpeech().transcribe(model, store.audio(recording.id))
                assertTrue("No text for private sample $number", text.isNotBlank())
                val id = store.commit(recording.id, text).taskId
                val task = checkNotNull(database.inbox().task(id))
                assertEquals(text.trim(), if (task.originalDescription.isEmpty()) task.originalTitle else task.originalDescription)
                assertFalse(store.audio(recording.id).exists())
                results.put(JSONObject().put("sample", sample).put("transcript", text).put("committed", true))
            }
            assertEquals(5, database.inbox().intents().size)
        } finally {
            // Read back explicitly into the ignored host evidence folder, then delete this report.
            report.writeText(results.toString())
            database.close()
            context.deleteDatabase(name)
            audio.deleteRecursively()
            input.deleteRecursively()
        }
    }
}
