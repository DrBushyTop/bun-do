package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.BunDoTheme
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
class SharedTaskUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val alice = UUID.randomUUID().toString()
    private val bob = UUID.randomUUID().toString()

    private fun fixture(language: String, appearance: String, claimed: Boolean = false): Pair<AccountData, SharedWorkspace> {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val workspace = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        val membership = JSONObject().put("me", alice).put("ownerId", alice).put("members", JSONArray()
            .put(JSONObject().put("id", alice).put("active", true).put("displayName", "Alice"))
            .put(JSONObject().put("id", bob).put("active", true).put("displayName", "Bob"))).toString()
        val state = SharedWorkspace("$workspace/$epoch/${data.registrationId}", workspace, epoch, data.registrationId!!,
            "Kotityöt", revision = "1", membership = membership)
        val tasks = listOf("Vie paperit kierrätykseen", "Järjestä hylly").map { title ->
            JSONObject().put("id", UUID.randomUUID().toString()).put("title", title).put("description", JSONObject.NULL)
                .put("titleVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
                .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
                .put("deletionVersion", "1").put("lifecycle", "OPEN").put("lifecycleVersion", "1")
                .put("claimantId", if (claimed) bob else JSONObject.NULL).put("claimVersion", "1")
                .put("hierarchyVersion", "1").put("orderIntentVersion", "1")
        }
        runBlocking {
            data.database.shared().saveWorkspace(state)
            val order = SharedTaskActions.orderEntity(JSONArray(tasks.map { it.getString("id") }), "1")
            for (task in tasks + order) {
                data.database.shared().saveBase(SharedBase(state.scope, task.getString("id"), task.toString()))
                data.database.shared().saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
            }
        }
        val configuration = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val translated = compose.activity.createConfigurationContext(configuration)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalContext provides translated, LocalConfiguration provides configuration,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    BunDoTheme(appearance) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                            SharedWorkspaceScreen(data, state, appearance, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Vie paperit kierrätykseen").fetchSemanticsNodes().isNotEmpty() }
        return data to state
    }

    @Test fun finnishClaimCompleteAndReopenKeepDetailsOpenAndHistoryReachable() {
        val (data, state) = fixture("fi", "light")
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-claim").performScrollTo().performClick()
        compose.onNodeWithText("Sinulla työn alla").assertIsDisplayed()
        screenshot("tasks-fi-light.png")
        compose.onNodeWithTag("task-complete").performClick()
        compose.onNodeWithTag("task-reopen").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("task-history-view").performScrollTo().performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-reopen").performClick()
        compose.onNodeWithTag("task-claim").assertIsDisplayed()
        assertEquals(listOf("ClaimTask", "CompleteTask", "ReopenTask"),
            runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun englishConfirmationRejectsAChangedClaimAndMoveButtonsNeedNoDragging() {
        val (data, state) = fixture("en", "dark", claimed = true)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-complete").performClick()
        compose.onNodeWithText("Bob is working on this task. Mark it complete anyway?").assertIsDisplayed()
        screenshot("tasks-en-dark-confirm.png")
        runBlocking {
            val row = data.database.shared().base(state.scope).first {
                it.id != SharedTaskActions.ORDER_ID && JSONObject(it.snapshot).getString("title") == "Vie paperit kierrätykseen"
            }
            val changed = JSONObject(row.snapshot).put("claimVersion", "2")
            data.database.shared().saveBase(row.copy(snapshot = changed.toString()))
            data.database.shared().saveProjection(SharedProjection(state.scope, row.id, changed.toString()))
        }
        compose.onNodeWithTag("task-confirm-complete").performClick()
        compose.onNodeWithText("The task changed or the action did not finish. Check its current state and try again.").assertIsDisplayed()
        assertTrue(runBlocking { data.database.shared().intents(state.scope).isEmpty() })
        compose.onNodeWithTag("task-later").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).size == 1 } }
        assertEquals("MoveTask", runBlocking { data.database.shared().intents(state.scope).single().kind })
        compose.onNodeWithTag("task-later").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
