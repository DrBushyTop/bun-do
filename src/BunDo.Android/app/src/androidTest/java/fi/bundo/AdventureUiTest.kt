package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AdventureUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val state = adventureWorkspace()
    private val root = adventureTask()
    private var action: JSONObject? = null
    private var requestFailed by mutableStateOf(false)
    private var requestBusy by mutableStateOf(false)
    private var refreshed by mutableStateOf<JSONObject?>(null)
    private fun screen(json: JSONObject? = adventureFixture(state, root), language: String = "en", scale: Float = 1f, busy: Boolean = false, failed: Boolean = false) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") { Surface { Box(Modifier.safeDrawingPadding()) {
                        SharedAdventureScreen((refreshed ?: json)?.let { AdventureSnapshot.read(it).project(state, listOf(root), emptyList()) },
                            busy || requestBusy, failed || requestFailed, true, mapOf(root.getString("id") to root), { action = it }, {}, {})
                    } } }
                }
            }
        } }
    }
    @Test fun explicitFinishConfirmsWithoutCheckingTasks() {
        screen()
        compose.onNodeWithTag("adventure-finish").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-confirm-finish").performClick()
        compose.runOnIdle {
            assertEquals("finish", action!!.getString("action"))
            assertTrue(action!!.getBoolean("confirmed"))
            assertEquals("OPEN", root.getString("lifecycle"))
        }
    }
    @Test fun updatedAdventureRequiresFreshFinishConfirmation() {
        val original = adventureFixture(state, root)
        screen(original)
        compose.onNodeWithTag("adventure-finish").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-confirm-finish").assertExists()
        compose.runOnIdle {
            refreshed = JSONObject(original.toString()).also {
                it.put("revision", "4")
                it.getJSONObject("board").getJSONObject("active").put("version", "4")
            }
        }
        compose.onNodeWithTag("adventure-confirm-finish").assertDoesNotExist()
        assertNull(action)
        compose.onNodeWithTag("adventure-finish").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-confirm-finish").performClick()
        assertEquals("4", action!!.getString("version"))
    }
    @Test fun twoIllustratedProposalsRequireExplicitAcceptance() {
        val json = adventureFixture(state, root, active = false); screen(json)
        val choice = json.getJSONObject("board").getJSONObject("batch").getJSONArray("proposals").getJSONObject(0).getString("id")
        screenshot("adventure-en-chooser")
        compose.onNodeWithTag("adventure-proposal-$choice").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-accept").performScrollTo().assertIsEnabled().performClick()
        assertEquals("accept", action!!.getString("action")); assertEquals(choice, action!!.getString("proposalId"))
        screenshot("adventure-en-preview")
    }
    @Test fun editAndConfirmedLeaveKeepSourceTasksExplicit() {
        screen()
        compose.onNodeWithTag("adventure-task-${root.getString("id")}").performScrollTo().assertTextEquals("Shopping list")
        compose.onNodeWithTag("adventure-edit").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-phase-name-0").performScrollTo().performTextReplacement("Gather supper")
        compose.onNodeWithTag("adventure-save").performScrollTo().performClick()
        assertEquals("edit", action!!.getString("action"))
        assertEquals("Gather supper", action!!.getJSONObject("draft").getJSONArray("phases").getJSONObject(0).getString("name"))
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("adventure-leave").performScrollTo().performClick()
        assertEquals("edit", action!!.getString("action"))
        compose.onNodeWithTag("adventure-confirm-leave").performClick()
        assertEquals("leave", action!!.getString("action")); assertTrue(action!!.getBoolean("confirmed"))
    }
    @Test fun finnishDoubleTextKeepsCompletionAndDismissReachable() {
        val complete = adventureTask("Kauppalista", true)
        screen(adventureFixture(state, complete), "fi", 2f)
        compose.onNodeWithTag("adventure-progress").performScrollTo().assertTextEquals("1 / 1 tehtävä valmis")
        screenshot("adventure-fi-large-complete")
        compose.onNodeWithTag("adventure-dismiss").performScrollTo().performClick()
        assertEquals("dismiss", action!!.getString("action"))
        screenshot("adventure-fi-large-dismiss")
    }
    @Test fun busyAndFailureDoNotHideCachedAdventure() {
        screen(busy = true, failed = true)
        compose.onNodeWithTag("adventure-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("adventure-edit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("adventure-progress").performScrollTo().assertTextEquals("0 of 1 task done")
        screenshot("adventure-en-offline")
    }
    @Test fun failedAcceptExplainsRetryInsidePreview() {
        val json = adventureFixture(state, root, active = false); screen(json)
        val id = json.getJSONObject("board").getJSONObject("batch").getJSONArray("proposals").getJSONObject(0).getString("id")
        compose.onNodeWithTag("adventure-proposal-$id").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-accept").performScrollTo().performClick()
        compose.runOnIdle { requestBusy = true }
        compose.onNodeWithTag("adventure-dialog-busy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("adventure-accept").assertIsNotEnabled()
        compose.runOnIdle { requestBusy = false; requestFailed = true }
        compose.onNodeWithTag("adventure-dialog-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("adventure-accept").performScrollTo().assertTextEquals("Retry").performClick()
        screenshot("adventure-en-accept-error")
    }
    @Test fun failedEditPreservesDraftAndExplainsRetryInsideEditor() {
        screen()
        compose.onNodeWithTag("adventure-edit").performScrollTo().performClick()
        compose.onNodeWithTag("adventure-phase-name-0").performScrollTo().performTextReplacement("Gather supper")
        compose.onNodeWithTag("adventure-save").performScrollTo().performClick()
        compose.runOnIdle { requestFailed = true }
        compose.onNodeWithTag("adventure-dialog-error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("adventure-save").performScrollTo().assertTextEquals("Retry").performClick()
        assertEquals("Gather supper", action!!.getJSONObject("draft").getJSONArray("phases").getJSONObject(0).getString("name"))
        compose.onNodeWithTag("adventure-save").performScrollTo()
        screenshot("adventure-en-edit-error")
    }
    @Test fun queueIgnoresAStaleVisibleRowWhileItsCanonicalGenerationIsRemoved() {
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") {
            SharedQueue(listOf(SharedProtocol.inbox(root)), emptyList(), null, false, {}, {}, {}, {}, {})
        } } }
        compose.onAllNodesWithText("Shopping list").assertCountEquals(0)
    }
    private fun screenshot(name: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("adventure-screen").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
