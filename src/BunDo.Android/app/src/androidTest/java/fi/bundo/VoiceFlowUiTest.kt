package fi.bundo

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.speech.*
import fi.bundo.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VoiceFlowUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun tapRecordsThenReviewsBeforeSaveEnglish() { flow("en", 1f) }
    @Test fun tapRecordsThenReviewsBeforeSaveFinnishLargeText() { flow("fi", 2f) }

    private fun flow(language: String, scale: Float) = runBlocking {
        val context = compose.activity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val name = "voice-flow-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val db = InboxDatabase.open(context, name)
        val store = RecordingStore(db, File(root, "audio"))
        val workspace = SharedWorkspace("home", UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Home",
            membership = JSONObject().put("me", UUID.randomUUID().toString()).toString())
        db.shared().saveWorkspace(workspace)
        val stop = CountDownLatch(1)
        lateinit var controller: VoiceController
        var saved: String? = null
        try {
            compose.runOnUiThread {
                controller = VoiceController(context, store, ModelInstaller(File(root, "models"), loadSpeechManifest(context)),
                    online = { "Ostoslista: maitoa, 6 munaa, ruisleipää" }, connected = { true },
                    recorderFactory = { object : RecordingInput {
                        override fun stop() { stop.countDown() }
                        override fun record(output: OutputStream, progress: (Int, Float) -> Unit) {
                            output.write(byteArrayOf(0, 1)); progress(4, .6f)
                            check(stop.await(30, TimeUnit.SECONDS))
                        }
                    } }, analyze = { VoiceDraft(it, if (language == "fi") "Ostoslista" else "Shopping list", items = listOf("maitoa", "6 munaa", "ruisleipää")) })
            }
            compose.waitUntil(10_000) { controller.state.value.loaded }
            compose.runOnUiThread { controller.configure(localOnly = false, keepAudio = false, analyze = true) }
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
            val translated = context.createConfigurationContext(config)
            var settings by mutableStateOf(false)
            compose.runOnUiThread { context.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides context, LocalContext provides translated,
                    LocalResources provides translated.resources, LocalConfiguration provides config,
                    LocalDensity provides Density(context.resources.displayMetrics.density, scale)) {
                    BunDoTheme("light") {
                        if (settings) Scaffold { padding -> VoiceSettings(controller, {}, Modifier.padding(padding)) }
                        else VoiceSheet(controller, {}, {}, { saved = it }, VoiceTarget(workspace.scope), autoStart = true)
                    }
                }
            } }
            compose.waitUntil(10_000) { controller.state.value.phase == "RECORDING" }
            compose.onNodeWithTag("stop-recording").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("install-model").assertDoesNotExist()
            screenshot("recording-$language")
            compose.onNodeWithTag("stop-recording").performClick()
            compose.waitUntil(10_000) { controller.state.value.draft != null && !controller.state.value.busy }
            assertTrue(db.shared().intents(workspace.scope).isEmpty())
            compose.onNodeWithTag("voice-review-title").performScrollTo().performTextReplacement(if (language == "fi") "Kauppalista" else "Groceries")
            compose.onNodeWithTag("voice-review-items").performScrollTo().performTextReplacement("kauramaitoa\n6 munaa")
            screenshot("review-$language")
            compose.onNodeWithTag("voice-review-save").performScrollTo().performClick()
            compose.waitUntil(10_000) { saved != null }
            assertEquals(listOf("CreateTask", "EditTask", "SplitTask"), db.shared().intents(workspace.scope).map { it.kind })
            assertTrue(db.recordings().all().isEmpty())
            compose.runOnUiThread { settings = true }
            compose.onNodeWithTag("voice-keep-audio").performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("voice-history").performScrollTo().performClick()
            screenshot("voice-settings-$language")
            compose.onNodeWithTag("voice-keep-audio").performScrollTo().performClick().assertIsOff()
        } finally {
            stop.countDown()
            compose.runOnUiThread { controller.close() }
            db.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
