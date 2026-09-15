package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.ReminderSettingsSection
import fi.bundo.ui.SharedWorkspaceScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    private fun settings(language: String, scale: Float): AccountData {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        runBlocking { ReminderCoordinator.updateSettings(data) { it.copy(permissionAsked = true) } }
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalContext provides translated, LocalConfiguration provides config,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        BunDoTheme("light") {
                            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp)) {
                                ReminderSettingsSection(data)
                            }
                        }
                    }
                }
            }
        }
        return data
    }

    @Test fun englishSettingsSaveScopeAndTimeWithoutRepeatingPermissionPrompt() {
        val data = settings("en", 1.0f)
        compose.onNodeWithTag("reminders-mode-1").performClick()
        compose.waitUntil(5000) { runBlocking { data.database.reminders().settings()!!.enabled } }
        compose.onNodeWithTag("reminders-mode-1").assertIsSelected()
        screenshot("reminders-en-settings")
        compose.onNodeWithTag("reminders-time").performScrollTo().performTextReplacement("10:30")
        compose.onNodeWithText("Save reminder time").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { data.database.reminders().settings()!!.dateOnlyTime == "10:30" } }
        compose.onNodeWithTag("reminders-mode-0").performScrollTo().performClick()
        compose.waitUntil(5000) { runBlocking { !data.database.reminders().settings()!!.enabled } }
    }

    @Test fun finnishSettingsRemainUsableAtDoubleFontSize() {
        settings("fi", 2.0f)
        compose.onNodeWithTag("reminders-mode-2").performScrollTo().assertIsDisplayed()
        screenshot("reminders-fi-large")
        compose.onNodeWithTag("reminders-time").performScrollTo().performTextReplacement("25:70")
        compose.onNodeWithText("Tallenna muistutusaika").performScrollTo().assertIsNotEnabled()
        screenshot("reminders-fi-large-time")
    }

    @Test fun dueListStillWorksWithRemindersOffAndKeepsOldTasks() {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val id = UUID.randomUUID().toString()
        val state = SharedWorkspace("$id/epoch/${data.registrationId}", id, "epoch", data.registrationId!!, "Home",
            membership = JSONObject().put("me", "me").put("members", JSONArray()
                .put(JSONObject().put("id", "me").put("active", true))).toString())
        val task = JSONObject().put("id", UUID.randomUUID().toString()).put("title", "Overdue milk")
            .put("description", JSONObject.NULL).put("lifecycle", "OPEN")
            .put("titleVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
            .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
            .put("due", JSONObject().put("kind", "DATE_TIME").put("localDate", "2026-01-01").put("localTime", "09:00")
                .put("zoneId", "Europe/Helsinki").put("instant", "2026-01-01T07:00:00Z"))
        runBlocking {
            data.database.shared().saveWorkspace(state)
            data.database.shared().saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
        }
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { SharedWorkspaceScreen(data, state, "light", {}, {}) } } }
        // Compose can be idle while Room is still loading the initial projection.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Overdue milk").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-due-view").performScrollTo().performClick()
        compose.onNodeWithTag("queue").performScrollToNode(hasText("Overdue milk"))
        compose.onNodeWithText("Overdue milk").assertExists()
        assertFalse(runBlocking { data.database.reminders().settings()?.enabled ?: false })
        screenshot("reminders-due-list")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
