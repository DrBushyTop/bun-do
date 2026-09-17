package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.SignInModel
import fi.bundo.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.RuleChain
import java.io.File
import java.util.Locale

class TaskFirstUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private lateinit var renderedResources: android.content.res.Resources
    private val data get() = (compose.activity.application as BunDoApplication).accounts.active.value!!

    private fun render(language: String, scale: Float, content: @Composable () -> Unit) {
        val config = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language)); fontScale = scale
        }
        val resources = compose.activity.createConfigurationContext(config).resources
        renderedResources = resources
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalResources provides resources, LocalConfiguration provides config,
                LocalDensity provides Density(resources.displayMetrics.density, scale)) {
                BunDoTheme("light") { Surface { Box(Modifier.safeDrawingPadding()) { content() } } }
            }
        } }
    }

    @Test fun settingsEnglish() = settings("en", 1f)
    @Test fun settingsFinnishLarge() = settings("fi", 2f)

    private fun settings(language: String, scale: Float) {
        var destination = ""
        val source = data
        render(language, scale) {
            HouseholdMotionProvider {
                val model: InboxViewModel = viewModel(factory = viewModelFactory {
                    initializer { InboxViewModel(source.inbox, SavedStateHandle()) }
                })
                val state by model.state.collectAsState()
                InboxApp(state, model, "light", {}, voice = source.voice,
                    onAccount = { destination = "account" }, onReminders = { destination = "reminders" },
                    onRecovery = { destination = "recovery" })
            }
        }
        compose.onNodeWithTag("settings").performClick()
        screenshot("settings-$language")
        for ((tag, target) in listOf("account" to "account", "settings-reminders" to "reminders", "settings-recovery" to "recovery")) {
            compose.onNodeWithTag(tag).performScrollTo().assertHasClickAction().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            assertEquals(target, destination)
        }
        compose.onNodeWithTag("decorative-motion").performScrollTo().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        compose.onNodeWithTag("settings-language").performScrollTo().performClick()
        screenshot("language-$language")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings-language").assertExists()
    }

    @Test fun recoveryAndReminderShortcutsReturnToSettings() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("settings-recovery").performScrollTo().performClick()
        compose.onNodeWithTag("recovery-backup").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-language").assertExists()
        compose.onNodeWithTag("settings-reminders").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("reminders-time") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reminders-denied").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-language").assertExists()
    }

    @Test fun voiceModeIsAnExplicitChoiceAndBackReturnsFromOfflineSetup() {
        val controller = data.voice
        render("fi", 2f) { VoiceSettings(controller, {}) }
        compose.waitUntil(5000) { controller.state.value.loaded }
        val previous = controller.state.value.localOnly
        screenshot("voice-fi")
        compose.onNodeWithTag("voice-mode").performScrollTo().performClick()
        assertEquals(previous, controller.state.value.localOnly)
        compose.onNodeWithTag("voice-model-local").performClick()
        compose.waitUntil(5000) { controller.state.value.localOnly }
        compose.onNodeWithTag("voice-mode").assertExists()
        compose.onNodeWithTag("voice-model-setup").performScrollTo().performClick()
        if (controller.state.value.modelReady) compose.onNodeWithText(renderedResources.getString(R.string.voice_model_installed)).performScrollTo().assertIsDisplayed()
        else compose.onNodeWithTag("install-model").performScrollTo().assertHasClickAction()
        screenshot("voice-offline-fi")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("voice-mode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("voice-history").performScrollTo().performClick()
        compose.onNodeWithTag("voice-mode").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("voice-keep-audio").performScrollTo().assertIsOff()
    }

    @Test fun recoveryCopiesOnlySelectedTextToLocalInboxAndKeepsOriginals() {
        val app = compose.activity.application as BunDoApplication
        val source = data
        runBlocking { source.inbox.commit(EditorDraft(InboxRepository.NEW_DRAFT, "Saved grocery note", "Oat milk")) }
        render("en", 1f) {
            val model: SignInModel = viewModel()
            AccountScreen(app.accounts, model, onClose = {})
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("account-sign-out") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("account-en")
        compose.onNodeWithTag("account-recovery-details").performScrollTo().performClick()
        compose.onNodeWithTag("recovery-review").performScrollTo().performClick()
        compose.onNodeWithText("Saved grocery note").assertExists()
        compose.onNodeWithTag("recovery-copy").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Saved grocery note").performScrollTo().performClick()
        screenshot("recovery-en")
        compose.onNodeWithTag("recovery-copy").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { source.database.inbox().allTasks().size == 2 } }
        val tasks = runBlocking { source.database.inbox().allTasks() }
        assertTrue(tasks.all { it.title == "Saved grocery note" && it.description == "Oat milk" })
        assertTrue(runBlocking { source.database.shared().allWorkspaces() }.isEmpty())
        compose.onNodeWithTag("recovery-backup").performScrollTo().performClick()
        compose.onNodeWithText(renderedResources.getString(R.string.account_export_warning)).performScrollTo().assertIsDisplayed()
    }

    @Test fun signOutAndDeletionHaveDifferentWarningsAndCancelKeepsData() = confirmations("en", 1f)
    @Test fun destructiveWarningsStayReadableInFinnishLargeText() = confirmations("fi", 2f)

    private fun confirmations(language: String, scale: Float) {
        val app = compose.activity.application as BunDoApplication
        render(language, scale) {
            val model: SignInModel = viewModel()
            AccountScreen(app.accounts, model, onClose = {})
        }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("account-sign-out") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        screenshot("signout-confirmation-$language")
        compose.onNodeWithText(renderedResources.getString(R.string.account_sign_out_warning), substring = true).assertIsDisplayed()
        compose.onNodeWithText(renderedResources.getString(R.string.account_delete_warning), substring = true).assertDoesNotExist()
        compose.onNodeWithText(renderedResources.getString(R.string.account_cancel)).performClick()
        compose.onNodeWithTag("account-delete-details").performScrollTo().performClick()
        compose.onNodeWithTag("account-delete").performScrollTo().performClick()
        val confirmBounds = compose.onNodeWithText(renderedResources.getString(R.string.account_delete_confirm)).fetchSemanticsNode().boundsInRoot
        val cancelBounds = compose.onNodeWithText(renderedResources.getString(R.string.account_cancel)).fetchSemanticsNode().boundsInRoot
        assertTrue("Confirmation and cancel must not overlap", confirmBounds.bottom <= cancelBounds.top || cancelBounds.bottom <= confirmBounds.top)
        screenshot("delete-confirmation-$language")
        compose.onNode(hasText(renderedResources.getString(R.string.account_delete_warning), substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText(renderedResources.getString(R.string.account_cancel)).performClick()
        assertNotNull(app.accounts.active.value?.identity)
    }

    @Test fun emptyAdventureOffersRealPlanningAndTaskActionsButLoadingDoesNotLookEmpty() {
        val state = adventureWorkspace()
        val fixture = adventureFixture(state, active = false)
        fixture.getJSONObject("board").getJSONObject("batch").put("status", "EMPTY").put("proposals", JSONObject.NULL)
        var busy by mutableStateOf(false)
        var snapshot by mutableStateOf<AdventureSnapshot?>(AdventureSnapshot.read(fixture))
        var planned = false
        var queue = false
        render("fi", 2f) {
            SharedAdventureScreen(snapshot, busy, false, true, emptyMap(), {}, {}, {},
                onCreate = { planned = true }, onQueue = { queue = true })
        }
        screenshot("adventure-empty-fi")
        compose.onNodeWithTag("adventure-create").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-add-tasks").performScrollTo().performClick()
        assertTrue(planned); assertTrue(queue)
        compose.runOnUiThread { busy = true; snapshot = null }
        compose.onNodeWithTag("adventure-status").assertDoesNotExist()
        compose.onNodeWithTag("adventure-add-tasks").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.getExternalFilesDir(null), "task-first").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
