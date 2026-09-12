package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxDatabase
import fi.bundo.data.RecordingStore
import fi.bundo.data.ModelFile
import fi.bundo.data.ModelInstaller
import fi.bundo.data.ModelManifest
import fi.bundo.speech.VoiceController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceControllerTest {
    @Test fun canceledNativeResultCannotCreateTaskOrRemoveAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "voice-controller-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val database = InboxDatabase.open(context, name)
        val store = RecordingStore(database, File(root, "audio"))
        val modelRoot = File(root, "models").apply { mkdirs() }
        val directory = File(modelRoot, "model-${UUID.randomUUID()}").apply { mkdirs() }
        val fakeFile = File(directory, "test").apply { writeText("test") }
        val manifest = ModelManifest("fixture", "https://example.invalid", 1, "", "",
            listOf(ModelFile("test", 4, ModelInstaller.digest(fakeFile))))
        File(directory, "manifest-id").writeText("fixture")
        File(modelRoot, "active").writeText(directory.name)
        val decoderStarted = CountDownLatch(1)
        val decoderRelease = CountDownLatch(1)
        lateinit var controller: VoiceController
        try {
            instrumentation.runOnMainSync {
                controller = VoiceController(context, store, ModelInstaller(modelRoot, manifest)) { _, _ ->
                    decoderStarted.countDown()
                    check(decoderRelease.await(10, TimeUnit.SECONDS))
                    "Late transcript must not commit"
                }
            }
            await { controller.state.value.loaded }
            assertTrue(controller.state.value.modelReady)
            val recording = store.begin()
            store.audio(recording.id).writeBytes(byteArrayOf(0, 1))
            instrumentation.runOnMainSync { controller.retry(recording.id) }
            assertTrue(decoderStarted.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.cancel() }
            decoderRelease.countDown()
            await { !controller.state.value.busy }
            assertEquals("CANCELED", database.recordings().get(recording.id)!!.reason)
            assertTrue(database.inbox().intents().isEmpty())
            assertTrue(store.audio(recording.id).exists())
            instrumentation.runOnMainSync { controller.permissionDenied() }
            assertEquals("PERMISSION", controller.state.value.message)
            assertEquals(1, database.recordings().all().size)
        } finally {
            decoderRelease.countDown()
            instrumentation.runOnMainSync { controller.close() }
            database.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Controller did not reach expected state" }
            Thread.sleep(20)
        }
    }
}
