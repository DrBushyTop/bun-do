package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GuidedAdventureUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val existing = adventureTask()
    private val draft = GuidedDraft("A quiet corner", "", listOf(GuidedPhase(existing.getString("id"), null, "Use what we have", 1, 10), GuidedPhase(null, "Sort papers", "Make room", 1, 15)))
    private var planned: Pair<String, Int?>? = null
    private var approved: GuidedDraft? = null
    private var resumed = false
    private fun screen(stage: String? = null, language: String = "en", scale: Float = 1f, failed: Boolean = false) {
        val creation = stage?.let { GuidedCreation(UUID.randomUUID().toString(), "3", draft, it) }
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") { Surface { Box(Modifier.safeDrawingPadding()) {
                        GuidedAdventureScreen(creation, null, false, failed, mapOf(existing.getString("id") to existing), true,
                            { outcome, minutes -> planned = outcome to minutes }, { approved = it }, { resumed = true }, {}, {}, {})
                    } } }
                }
            }
        } }
    }
    @Test fun empty_queue_can_request_a_draft_without_adding_tasks() {
        screen()
        compose.onNodeWithTag("guided-outcome").performScrollTo().performTextInput("Clear a corner")
        compose.onNodeWithTag("guided-time").performScrollTo().performTextInput("25")
        compose.onNodeWithTag("guided-plan").performScrollTo().performClick()
        assertEquals("Clear a corner" to 25, planned); assertNull(approved)
        screenshot("guided-en-outcome")
    }
    @Test fun review_distinguishes_existing_and_new_tasks_and_keeps_only_approved_work() {
        screen("REVIEW")
        compose.onNodeWithText("Already in your queue").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Estimated difficulty: 1 of 3 stars").assertCountEquals(2)
        screenshot("guided-en-review")
        compose.onNodeWithTag("guided-task-1").performScrollTo().performTextReplacement("Sort just the letters")
        compose.onNodeWithTag("guided-remove-0").performScrollTo().performClick()
        compose.onNodeWithTag("guided-approve").performScrollTo().performClick()
        assertEquals(1, approved!!.phases.size); assertEquals("Sort just the letters", approved!!.phases.single().taskTitle)
    }
    @Test fun finnish_double_text_can_approve_and_recover_failed_start() {
        screen("QUEUED", "fi", 2f, failed = true)
        compose.onNodeWithTag("guided-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guided-resume").performScrollTo().performClick()
        assertTrue(resumed)
        screenshot("guided-fi-large-pending")
    }
    @Test fun invalid_fields_explain_the_disabled_draft_action() {
        screen()
        compose.onNodeWithTag("guided-outcome").performScrollTo().performTextInput("Clear\na corner")
        compose.onNodeWithTag("guided-outcome-error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("guided-time").performScrollTo().performTextInput("25 min")
        compose.onNodeWithTag("guided-time-error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("guided-plan").performScrollTo().assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        screenshot("guided-en-validation")
    }
    @Test fun ended_attempt_can_be_acknowledged_without_creating_tasks() {
        screen("ENDED")
        compose.onNodeWithTag("guided-ended").assertIsDisplayed()
        compose.onNodeWithTag("guided-resume").assertDoesNotExist()
        compose.onNodeWithTag("guided-approve").assertDoesNotExist()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
