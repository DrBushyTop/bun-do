package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.speech.*
import fi.bundo.identity.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OnlineVoiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private inner class Fixture(val local: Boolean = false) : AutoCloseable {
        val name = "online-voice-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val db = InboxDatabase.open(context, name)
        val lease = DataLease()
        val store = RecordingStore(db, File(root, "audio"), lease)
        val modelRoot = File(root, "models").apply { mkdirs() }
        val model = File(modelRoot, "model-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(model, "test").apply { writeText("test") }
        val manifest = ModelManifest("fixture", "https://example.invalid", 1, "", "", listOf(ModelFile("test", 4, ModelInstaller.digest(file))))
        var controller: VoiceController? = null
        var localCalls = 0
        init {
            if (local) {
                File(model, "manifest-id").writeText("fixture")
                File(modelRoot, "active").writeText(model.name)
            }
        }
        fun start(connected: Boolean = true, online: suspend (ByteArray) -> String): VoiceController {
            instrumentation.runOnMainSync {
                controller = VoiceController(context, store, ModelInstaller(modelRoot, manifest),
                    online = online, connected = { connected }, transcribe = { _, _ -> localCalls++; "Local transcript" })
            }
            await { controller!!.state.value.loaded }
            return controller!!
        }
        suspend fun recording(): String = store.begin().id.also { store.output(it).use { output -> output.write(byteArrayOf(0, 1)) } }
        override fun close() {
            instrumentation.runOnMainSync { controller?.close() }
            db.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }

    @Test fun onlineWithoutModelCommitsOnceAndAllowsHumanCorrection() = runBlocking {
        Fixture().use { f ->
            var calls = 0
            val controller = f.start { calls++; "Buy 2 cartons, no sugar" }
            assertFalse(controller.state.value.modelReady)
            assertTrue(controller.state.value.onlineConfigured)
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals(1, calls)
            assertEquals(1, f.db.inbox().intents().size)
            assertEquals("Buy 2 cartons, no sugar", f.db.inbox().task("voice-$id")!!.title)
            assertFalse(f.store.audio(id).exists())
            assertNull(f.db.recordings().get(id))
            // A repeated UI action after commit cannot recreate audio or another task.
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals(1, calls)
            assertEquals(1, f.db.inbox().intents().size)
            val inbox = InboxRepository(f.db, f.lease)
            inbox.commit(inbox.draft("voice-$id").copy(title = "Buy 3 cartons", description = "No sugar"))
            assertEquals("Buy 3 cartons", f.db.inbox().task("voice-$id")!!.title)
        }
    }
    @Test fun disconnectedAndProviderFailureUseInstalledModelOnlyOnce() = runBlocking {
        for (connected in listOf(false, true)) Fixture(local = true).use { f ->
            var calls = 0
            val controller = f.start(connected) { calls++; throw SpeechFailure("ONLINE_FAILED") }
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals(if (connected) 1 else 0, calls)
            assertEquals(1, f.localCalls)
            assertEquals("Local transcript", f.db.inbox().task("voice-$id")!!.title)
        }
    }
    @Test fun tokenAcquisitionRejectsAuthExplicitlyButRetainsNetworkFallback() = runBlocking {
        for (local in listOf(false, true)) {
            for (fault in listOf("missing-restore", "missing-refresh", "interaction", "denied", "network", "server")) Fixture(local).use { f ->
                var refreshes = 0
                var uploads = 0
                val provider = SharedTokens(object : TokenProvider {
                    override val issuer = IdentityEndpoint.ISSUER
                    override suspend fun restore() = fault != "missing-restore"
                    override suspend fun refresh(): String {
                        refreshes++
                        throw when (fault) {
                            "missing-refresh" -> TokenFailure("no_current_account")
                            "interaction" -> TokenFailure("invalid_grant")
                            "denied" -> TokenFailure("access_denied")
                            "network" -> TokenFailure("device_network_not_available", retryable = true)
                            else -> TokenFailure("service_not_available", retryable = true)
                        }
                    }
                    override suspend fun signIn(activity: android.app.Activity, choice: String?): String = error("Unexpected interactive sign-in")
                    override suspend fun signOut() = Unit
                })
                val controller = f.start {
                    authenticatedSpeech { provider.requestToken(); uploads++; "Unexpected upload" }
                }
                val id = f.recording()
                instrumentation.runOnMainSync { controller.retry(id) }
                await { !controller.state.value.busy }
                assertEquals(0, uploads)
                assertEquals(if (fault == "missing-restore") 0 else 1, refreshes)
                val retryable = fault in setOf("network", "server")
                if (retryable && local) {
                    assertEquals(1, f.localCalls)
                    assertEquals("Local transcript", f.db.inbox().task("voice-$id")!!.title)
                } else {
                    assertEquals(0, f.localCalls)
                    assertEquals(if (retryable) "ONLINE_FAILED" else "SIGN_IN_REQUIRED", f.db.recordings().get(id)!!.reason)
                    assertTrue(f.store.audio(id).exists())
                    assertTrue(f.db.inbox().intents().isEmpty())
                    if (local) {
                        instrumentation.runOnMainSync { controller.retryOffline(id) }
                        await { !controller.state.value.busy }
                        assertEquals(1, f.localCalls)
                        assertEquals("Local transcript", f.db.inbox().task("voice-$id")!!.title)
                    }
                }
            }
        }
    }
    @Test fun errorsAndSilenceRetainRecoverableInputWithoutRetry() = runBlocking {
        for (code in listOf("SIGN_IN_REQUIRED", "ONLINE_FAILED", "TOO_LONG", "SILENCE", "OFFLINE_MODEL_REQUIRED")) Fixture().use { f ->
            var calls = 0
            val controller = f.start(code != "OFFLINE_MODEL_REQUIRED") {
                calls++
                if (code == "SILENCE") "" else throw SpeechFailure(code)
            }
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            await { !controller.state.value.busy }
            assertEquals(if (code == "OFFLINE_MODEL_REQUIRED") 0 else 1, calls)
            assertEquals(code, f.db.recordings().get(id)!!.reason)
            assertTrue(f.store.audio(id).exists())
            assertTrue(f.db.inbox().intents().isEmpty())
            val exported = java.io.ByteArrayOutputStream()
            exportWave(f.store.readAudio(id), exported)
            assertEquals("RIFF", exported.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII))
            f.store.delete(id)
            assertFalse(f.store.audio(id).exists())
        }
    }
    @Test fun cancelFallbackAndAccountRevocationFenceLateOnlineReply() = runBlocking {
        for (action in listOf("cancel", "fallback", "cancel-fallback", "revoke")) Fixture(local = true).use { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val controller = f.start {
                entered.countDown()
                withContext(NonCancellable) { check(release.await(10, TimeUnit.SECONDS)); "Late cloud text" }
            }
            val id = f.recording()
            instrumentation.runOnMainSync { controller.retry(id) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                when (action) {
                    "fallback" -> controller.useOffline()
                    "cancel-fallback" -> { controller.useOffline(); controller.cancel() }
                    "revoke" -> { f.lease.revoke(); controller.close() }
                    else -> controller.cancel()
                }
            }
            release.countDown()
            if (action == "fallback") await { controller.state.value.savedTaskId != null }
            else await { !controller.state.value.busy }
            if (action == "fallback") {
                assertEquals("Local transcript", f.db.inbox().task("voice-$id")!!.title)
                assertEquals(1, f.db.inbox().intents().size)
            } else {
                assertTrue(f.db.inbox().intents().isEmpty())
                assertTrue(f.store.audio(id).exists())
            }
        }
    }
    @Test fun processInterruptionLeavesAnExplicitRetryNotAnAutomaticUpload() = runBlocking {
        Fixture().use { f ->
            val id = f.recording()
            f.store.transcribing(id)
            var calls = 0
            f.start { calls++; "Unexpected" }
            assertEquals("INTERRUPTED", f.db.recordings().get(id)!!.reason)
            assertEquals(0, calls)
            assertTrue(f.store.audio(id).exists())
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) { check(System.nanoTime() < deadline); Thread.sleep(20) }
    }
}
