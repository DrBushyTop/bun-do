package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.speech.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in paid device proof. Private fixture/config/report files never leave app-private storage
 * except via the invoking runner. This uses a function-key protected verification route, not MSA login. */
@RunWith(AndroidJUnit4::class)
class OnlineSpeechDeviceTest {
    @Test fun prerecordedStopThroughBackendToCommittedText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineSpeechProof") == "true")
        val context = instrumentation.targetContext
        val input = File(context.filesDir, "speech-proof")
        val config = JSONObject(File(input, "config.json").readText())
        val endpoint = URL(config.getString("endpoint"))
        require(endpoint.protocol == "https" && endpoint.host.endsWith(".azurewebsites.net") && endpoint.path == "/verification/speech")
        val cases = JSONArray(File(input, "cases.json").readText())
        val report = JSONArray()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val audio = File(input, "$id.pcm").readBytes()
            assertEquals(case.getString("sha256"), java.security.MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) })
            val name = "speech-proof-${UUID.randomUUID()}.db"
            val root = File(context.cacheDir, name).apply { mkdirs() }
            val db = InboxDatabase.open(context, name)
            val store = RecordingStore(db, File(root, "audio"), encryptionKey = ByteArray(32) { 7 })
            val recorded = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            var uploadMs = 0L
            lateinit var controller: VoiceController
            try {
                instrumentation.runOnMainSync {
                    controller = VoiceController(context, store,
                        installer = ModelInstaller(File(root, "models"), loadSpeechManifest(context)),
                        online = { pcm ->
                            val start = System.nanoTime()
                            val text = OnlineSpeech("https://${endpoint.host}") {
                                (endpoint.openConnection() as HttpURLConnection).apply {
                                    setRequestProperty("x-functions-key", config.getString("key"))
                                }
                            }.transcribe("fixture-not-a-user-token", UUID.randomUUID().toString(), pcm, case.getString("language"))
                            uploadMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                            text
                        }, connected = { true }, recorderFactory = {
                            object : RecordingInput {
                                override fun stop() { stopped.countDown() }
                                override fun record(output: OutputStream, progress: (Int, Float) -> Unit) {
                                    var offset = 0
                                    while (offset < audio.size) {
                                        val count = minOf(8192, audio.size - offset)
                                        output.write(audio, offset, count)
                                        offset += count
                                    }
                                    output.flush(); recorded.countDown()
                                    check(stopped.await(15, TimeUnit.SECONDS))
                                }
                            }
                        })
                }
                await { controller.state.value.loaded }
                instrumentation.runOnMainSync { controller.record() }
                assertTrue(recorded.await(15, TimeUnit.SECONDS))
                val start = System.nanoTime()
                instrumentation.runOnMainSync { controller.stop() }
                await { !controller.state.value.busy }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                val taskId = controller.state.value.savedTaskId
                val text = taskId?.let { db.inbox().task(it)!!.let { task -> task.description.ifEmpty { task.title } } }
                report.put(JSONObject().put("id", id).put("status", if (text != null) "result" else controller.state.value.message)
                    .put("text", text).put("uploadToFinalMs", uploadMs).put("stopToCommittedMs", elapsed))
                File(input, "report.json").writeText(report.toString())
                assertNotNull("Transcription failed; inspect private report, do not retry automatically", taskId)
                assertEquals(1, db.inbox().intents().size)
                assertTrue(db.recordings().all().isEmpty())
            } finally {
                stopped.countDown()
                instrumentation.runOnMainSync { controller.close() }
                db.close(); context.deleteDatabase(name); root.deleteRecursively(); audio.fill(0)
            }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(160)
        while (!condition()) { check(System.nanoTime() < deadline); Thread.sleep(10) }
    }
}
