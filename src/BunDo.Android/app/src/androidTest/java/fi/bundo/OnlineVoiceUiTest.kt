package fi.bundo

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.speech.*
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.VoiceSheet
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnlineVoiceUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun englishCloudCaptureWithoutModelAndTypedFallback() = screen("en", 1f, false)
    @Test fun finnishRecoverableErrorAtDoubleFontSize() = screen("fi", 2f, true)

    private fun screen(language: String, scale: Float, failure: Boolean) = runBlocking {
        val context = compose.activity
        val name = "speech-ui-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val db = InboxDatabase.open(context, name)
        val store = RecordingStore(db, File(root, "audio"))
        val installer = ModelInstaller(File(root, "models"), loadSpeechManifest(context))
        var typed = false
        lateinit var controller: VoiceController
        try {
            compose.runOnUiThread { controller = VoiceController(context, store, installer,
                online = { throw SpeechFailure("ONLINE_FAILED") }, connected = { true }) }
            compose.waitUntil(15_000) { controller.state.value.loaded }
            if (failure) {
                val row = store.begin()
                store.output(row.id).use { it.write(byteArrayOf(0, 1)) }
                compose.runOnUiThread { controller.retry(row.id) }
                compose.waitUntil(15_000) { !controller.state.value.busy }
                assertEquals("ONLINE_FAILED", controller.state.value.message)
            }
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
            val translated = context.createConfigurationContext(config)
            compose.runOnUiThread { context.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides context,
                    LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config,
                    LocalDensity provides Density(context.resources.displayMetrics.density, scale)) {
                    BunDoTheme("light") { VoiceSheet(controller, {}, { typed = true }, {}) }
                }
            } }
            compose.onNodeWithTag("record").assertExists().assertIsEnabled()
            if (failure) {
                compose.onAllNodesWithText(translated.getString(R.string.voice_online_failed))[0].assertExists()
                compose.onNodeWithText(translated.getString(R.string.voice_recovery)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(translated.getString(R.string.retry)).performScrollTo().assertIsEnabled()
            } else {
                compose.onNodeWithText(translated.getString(R.string.voice_online_private)).assertIsDisplayed()
                compose.onNodeWithTag("install-model").assertExists()
            }
            compose.onNodeWithTag("voice-type").performScrollTo().performClick()
            assertTrue(typed)
            compose.waitForIdle()
            // Native inspection artifact contains only synthetic fixture copy.
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, "speech-ui-$language.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        } finally {
            compose.runOnUiThread { controller.close() }
            db.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }
}
