package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxDatabase
import fi.bundo.data.RecordingStore
import fi.bundo.speech.LocalSpeech
import fi.bundo.data.ModelInstaller
import fi.bundo.speech.loadSpeechManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.Locale

/**
 * Explicit opt-in: first install the pinned model using the app, then disconnect
 * networking and run with -e speechModelInstalled true. Never downloads a model.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetDeviceTest {
    @Test fun finnishAndEnglishTranscribeOfflineAndCommitBeforeCleanup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("speechModelInstalled") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val installer = ModelInstaller(File(context.noBackupFilesDir, "speech-models"), loadSpeechManifest(context))
        if (InstrumentationRegistry.getArguments().getString("speechInstallArchive") == "true") {
            // Explicit local-fixture install for repeatable device tests, never an implicit download.
            val archive = File(context.cacheDir, "speech-model.tar.bz2")
            installer.install({ archive.inputStream() })
            check(archive.delete())
        }
        val model = checkNotNull(installer.verifiedActive()) { "Install the pinned model through Bun Do first" }
        val name = "runtime-${UUID.randomUUID()}.db"
        val database = InboxDatabase.open(context, name)
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        try {
            val store = RecordingStore(database, directory)
            for ((language, reference) in listOf(
                "fi" to "Suurin osa valtiossa työskentelevistä puhuu italiaa myös arkikielenään, mutta uskonnollisissa toimituksissa käytetään usein latinaa.",
                "en" to "Remember to buy milk and bread tomorrow.",
            )) {
                val record = store.begin()
                instrumentation.context.assets.open("speech/$language.pcm").use { input ->
                    store.audio(record.id).outputStream().use { input.copyTo(it) }
                }
                val text = LocalSpeech().transcribe(model, store.audio(record.id))
                // This checks a working language/audio pipeline, not exact spelling or phone quality.
                // Whole-transcript agreement is stronger than two incidental matching keywords.
                assertTrue("Public/test $language fixture exceeded 20% word error: $text", wordErrorRate(reference, text) <= 0.2)
                val id = store.commit(record.id, text).taskId
                assertNotNull(database.inbox().task(id))
                assertFalse(store.audio(record.id).exists())
            }
            assertEquals(2, database.inbox().intents().size)
            val silent = store.begin()
            store.audio(silent.id).writeBytes(ByteArray(32_000))
            assertEquals("", LocalSpeech().transcribe(model, store.audio(silent.id)))
            assertEquals(2, database.inbox().intents().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    private fun wordErrorRate(reference: String, actual: String): Double {
        fun words(text: String) = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val expected = words(reference)
        val observed = words(actual)
        var prior = IntArray(observed.size + 1) { it }
        expected.forEachIndexed { index, word ->
            val next = IntArray(observed.size + 1)
            next[0] = index + 1
            observed.forEachIndexed { column, value ->
                next[column + 1] = minOf(next[column] + 1, prior[column + 1] + 1,
                    prior[column] + if (word == value) 0 else 1)
            }
            prior = next
        }
        return prior.last().toDouble() / expected.size
    }
}
