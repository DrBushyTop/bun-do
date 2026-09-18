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
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class OnlineVoiceUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(IsolatedVoicePreferencesRule()).around(compose)

    @Test fun englishCloudCaptureWithoutModelAndTypedFallback() = screen("en", 1f, false)
    @Test fun finnishRecoverableErrorAtDoubleFontSize() = screen("fi", 2f, true)

    @Test fun recoveredHouseholdAudioCannotTargetAnotherHouseholdsSameTaskId() = recoveryDestination("household", "other")
    @Test fun recoveredHouseholdAudioCannotTargetLocalInbox() = recoveryDestination("household", "local")
    @Test fun recoveredLocalAudioCannotTargetHousehold() = recoveryDestination("local", "household")
    @Test fun recoveredChecklistAudioCannotTargetHouseholdCapture() = recoveryDestination("checklist", "household")
    @Test fun recoveredAudioInItsOriginalHouseholdStillOpensTheSavedTask() = recoveryDestination("household", "household")
    @Test fun recoveredChecklistAudioInItsOriginalDraftStillRefreshesInstructions() = recoveryDestination("checklist", "checklist")

    private fun recoveryDestination(recorded: String, visible: String) = runBlocking {
        val context = compose.activity
        val name = "speech-destination-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        val db = InboxDatabase.open(context, name)
        val lease = DataLease()
        val store = RecordingStore(db, File(root, "audio"), lease)
        val registration = UUID.randomUUID().toString()
        val source = SharedWorkspace("source", UUID.randomUUID().toString(), UUID.randomUUID().toString(), registration, "Source",
            membership = JSONObject().put("me", UUID.randomUUID().toString()).toString())
        val other = source.copy(scope = "other", workspaceId = UUID.randomUUID().toString(), name = "Other")
        db.shared().saveWorkspace(source)
        db.shared().saveWorkspace(other)
        val otherRepository = SharedRepository(db, lease, other.scope, registration)
        val otherId = otherRepository.commit(EditorDraft(InboxRepository.NEW_DRAFT, "Unrelated other-household task", ""))
        val otherSnapshot = db.shared().task(other.scope, otherId)!!.snapshot
        db.shared().saveDraft(SharedDraft(source.scope, "checklist:parent", "", "", System.currentTimeMillis(), details = "{}"))
        fun target(kind: String): VoiceTarget? = when (kind) {
            "local" -> null
            "other" -> VoiceTarget(other.scope)
            "checklist" -> VoiceTarget(source.scope, "parent")
            else -> VoiceTarget(source.scope)
        }
        var opened: String? = null
        lateinit var controller: VoiceController
        try {
            compose.runOnUiThread {
                controller = VoiceController(context, store, ModelInstaller(File(root, "models"), loadSpeechManifest(context)),
                    online = { "Recovered task" }, connected = { true })
            }
            compose.waitUntil(15_000) { controller.state.value.loaded }
            val row = store.begin(target = target(recorded))
            store.output(row.id).use { it.write(byteArrayOf(0, 1)) }
            store.failed(row.id, "INTERRUPTED")
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
            val translated = context.createConfigurationContext(config)
            compose.runOnUiThread { context.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides context,
                    LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config) {
                    BunDoTheme("light") { VoiceSheet(controller, {}, {}, { opened = it }, target(visible)) }
                }
            } }
            compose.waitUntil(5_000) { controller.state.value.recordings.any { it.id == row.id } }
            compose.runOnUiThread { controller.retry(row.id) }
            compose.waitUntil(15_000) { !controller.state.value.busy && controller.state.value.message == "SAVED" && controller.state.value.saved == null }
            val actualId = when (recorded) {
                "local" -> db.inbox().intents().single().taskId
                "checklist" -> {
                    assertEquals("Recovered task", JSONObject(db.shared().draft(source.scope, "checklist:parent")!!.details!!).getString("instructions"))
                    "checklist:${source.scope}:parent"
                }
                else -> db.shared().intents(source.scope).single().taskId.also {
                    assertEquals("The collision must be real for this regression", otherId, it)
                }
            }
            if (recorded == visible) assertEquals(actualId, opened) else {
                assertNull("No wrong-screen navigation or Undo callback", opened)
                compose.onNodeWithText(translated.getString(R.string.voice_saved_elsewhere)).performScrollTo().assertIsDisplayed()
            }
            assertEquals(otherSnapshot, db.shared().task(other.scope, otherId)!!.snapshot)
            assertEquals(listOf("CreateTask"), db.shared().intents(other.scope).map { it.kind })
        } finally {
            compose.runOnUiThread { controller.close() }
            db.close(); context.deleteDatabase(name); root.deleteRecursively()
        }
    }

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
                    BunDoTheme("light") { VoiceSheet(controller, {}, { typed = true }, {}, onSettings = {}) }
                }
            } }
            compose.onNodeWithTag("record").assertExists().assertIsEnabled()
            if (failure) {
                compose.onAllNodesWithText(translated.getString(R.string.voice_online_failed))[0].assertExists()
                compose.onNodeWithTag("voice-settings-link").assertExists()
            } else {
                compose.onNodeWithTag("install-model").assertDoesNotExist()
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
