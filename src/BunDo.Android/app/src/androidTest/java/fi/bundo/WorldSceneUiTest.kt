package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.*
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
class WorldSceneUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private lateinit var data: AccountData
    private lateinit var state: SharedWorkspace
    private lateinit var tasks: List<JSONObject>
    private fun fixture(language: String = "en", scale: Float = 1f, complete: Boolean = false, empty: Boolean = false, adventureProgress: Int? = null) {
        data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val me = UUID.randomUUID().toString()
        val initial = adventureWorkspace().copy(registration = data.registrationId!!)
        state = initial.copy(scope = "${initial.workspaceId}/${initial.epoch}/${data.registrationId}", revision = "3",
            progress = JSONObject().put("activity", JSONArray()).toString(),
            membership = JSONObject().put("me", me).put("ownerId", me).put("members", JSONArray().put(JSONObject().put("id", me).put("active", true).put("displayName", "Alice"))).toString())
        tasks = if (empty) emptyList() else if (adventureProgress != null)
            listOf("Clear the counter", "Sort the cupboard", "Plan supper", "Pick up groceries").mapIndexed { i, title -> adventureTask(title, i < adventureProgress) }
        else listOf(adventureTask("Shopping list", complete), adventureTask("Water the plants", complete))
        state = state.copy(adventure = tasks.firstOrNull()?.let {
            val snapshot = adventureFixture(state, it)
            if (adventureProgress != null) {
                snapshot.getJSONObject("board").getJSONObject("active").put("draft",
                    AdventureDraft(if (language == "fi") "Keittiö kuntoon" else "A calmer kitchen", "",
                        tasks.map { task -> AdventurePhase(task.getString("id"), task.getString("title"), 1, 10) }).json())
                snapshot.getJSONObject("progress").put("total", tasks.size).put("completed", adventureProgress).put("isComplete", adventureProgress == tasks.size)
                    .put("roots", JSONArray(tasks.map { task -> JSONObject().put("rootId", task.getString("id"))
                        .put("available", true).put("task", task).put("checklist", JSONArray()) }))
            }
            snapshot.toString()
        })
        runBlocking {
            data.database.shared().saveWorkspace(state)
            val order = SharedTaskActions.orderEntity(JSONArray(tasks.map { it.getString("id") }), "3")
            for (task in tasks + order) {
                data.database.shared().saveBase(SharedBase(state.scope, task.getString("id"), task.toString()))
                data.database.shared().saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
            }
        }
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides compose.activity) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    HouseholdMotionProvider { BunDoTheme("light") { SharedWorkspaceScreen(data, state, "light", {}, {}) } }
                }
            }
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(if (empty) "world-rest" else "signpost-progress", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("adventure-signpost").assertIsDisplayed()
    }
    @Test fun active_adventure_shows_real_progress_in_the_home_signpost() {
        fixture(adventureProgress = 2)
        compose.onNodeWithTag("signpost-progress", useUnmergedTree = true).assertTextEquals("2 / 4 main tasks")
        compose.onNodeWithTag("capture").assertIsDisplayed()
        screenshot("adventure-home-active")
        if (InstrumentationRegistry.getArguments().getString("adventurePreview") == "true") {
            // Only this isolated synthetic fixture changes; never starts an owner's adventure.
            compose.mainClock.autoAdvance = false
            File(compose.activity.getExternalFilesDir(null), "adventure-preview-ready").writeText("ready")
            fun showFrames(milliseconds: Int) {
                repeat(milliseconds / 16) { compose.mainClock.advanceTimeByFrame(); android.os.SystemClock.sleep(16) }
            }
            showFrames(6500)
            runBlocking {
                val next = JSONObject(tasks[2].toString()).put("lifecycle", "COMPLETED").put("lifecycleVersion", "2")
                data.database.shared().saveBase(SharedBase(state.scope, next.getString("id"), next.toString()))
                data.database.shared().saveProjection(SharedProjection(state.scope, next.getString("id"), next.toString()))
            }
            showFrames(1000)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("3 / 4 main tasks").fetchSemanticsNodes().isNotEmpty() }
            showFrames(8500)
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("adventure-signpost").performClick()
        compose.onNodeWithTag("adventure-screen").assertIsDisplayed()
    }
    @Test fun large_finnish_adventure_progress_keeps_capture_and_navigation() {
        fixture(language = "fi", scale = 2f, adventureProgress = 2)
        compose.onNodeWithTag("household-world").assertDoesNotExist()
        compose.onNodeWithTag("signpost-progress", useUnmergedTree = true).assertTextEquals("2 / 4 päätehtävää")
        compose.onNodeWithTag("capture").assertIsDisplayed()
        screenshot("adventure-home-fi-large")
    }
    @Test fun continuous_scene_signpost_and_capture_survive_reorder_and_world_off() {
        val prefs = compose.activity.getSharedPreferences("appearance", 0)
        val before = prefs.getBoolean("world", true)
        try {
            prefs.edit().putBoolean("world", true).commit(); fixture()
            compose.onNodeWithTag("household-world").assertIsDisplayed()
            compose.onNodeWithTag("signpost-progress", useUnmergedTree = true).assertTextEquals("0 / 1 main task")
            compose.onNodeWithTag("capture").assertIsDisplayed()
            screenshot("world-en-queue")
            compose.onNodeWithTag("queue-reorder").performClick()
            compose.onNodeWithTag("household-world").assertDoesNotExist()
            compose.onNodeWithTag("adventure-signpost").assertIsDisplayed()
            screenshot("world-en-reorder")
            compose.onNodeWithTag("reorder-done").performClick()
            compose.onNodeWithTag("household-world").assertIsDisplayed()
            prefs.edit().putBoolean("world", false).commit()
            compose.onNodeWithTag("household-world").assertDoesNotExist()
            compose.onNodeWithTag("adventure-signpost").performClick()
            compose.onNodeWithTag("adventure-screen").assertIsDisplayed()
            screenshot("world-en-adventure")
        } finally { prefs.edit().putBoolean("world", before).commit() }
    }
    @Test fun finnish_large_text_keeps_signpost_progress_and_capture_without_scene() {
        fixture("fi", 2f)
        compose.onNodeWithTag("household-world").assertDoesNotExist()
        compose.onNodeWithTag("signpost-progress", useUnmergedTree = true).assertTextEquals("0 / 1 päätehtävä")
        compose.onNodeWithTag("capture").assertIsDisplayed()
        screenshot("world-fi-large")
        compose.onNodeWithTag("adventure-signpost").performClick()
        compose.onNodeWithTag("adventure-progress").performScrollTo().assertIsDisplayed()
    }
    @Test fun completed_signpost_acknowledgement_survives_return_and_clear_queue_rests() {
        fixture(complete = true)
        compose.onNodeWithText("Adventure complete").assertIsDisplayed()
        compose.onNodeWithTag("world-rest").assertIsDisplayed()
        compose.waitUntil { runBlocking { data.database.shared().workspace(state.scope)!!.adventureSeal != null } }
        val seal = runBlocking { data.database.shared().workspace(state.scope)!!.adventureSeal }
        screenshot("world-en-complete")
        compose.onNodeWithTag("adventure-signpost").performClick()
        compose.onNodeWithTag("adventure-screen").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("household-queue").performClick()
        compose.onNodeWithText("Adventure complete").assertIsDisplayed()
        assertEquals(seal, runBlocking { data.database.shared().workspace(state.scope)!!.adventureSeal })
    }
    @Test fun empty_household_keeps_explicit_creator_entry() {
        fixture(empty = true)
        compose.onNodeWithTag("world-rest").assertIsDisplayed()
        screenshot("adventure-home-idle")
        compose.onNodeWithTag("adventure-signpost").performClick()
        compose.onNodeWithTag("adventure-create").performScrollTo().performClick()
        compose.onNodeWithTag("guided-outcome").performScrollTo().assertIsDisplayed()
        screenshot("world-en-creator")
    }
    @Test fun paperwork_uses_canonical_tasks_and_joy_requires_a_new_accepted_completion() {
        fixture()
        runBlocking {
            for (i in 1..6) {
                val task = adventureTask("Overdue task $i").put("due", JSONObject().put("kind", "DATE_ONLY")
                    .put("localDate", "2026-01-01").put("zoneId", "Europe/Helsinki"))
                data.database.shared().saveBase(SharedBase(state.scope, task.getString("id"), task.toString()))
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("world-paperwork").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("world-paperwork").assertIsDisplayed()
        screenshot("world-en-paperwork")
        fun accepted(count: Int) = runBlocking {
            val saved = data.database.shared().workspace(state.scope)!!
            data.database.shared().saveWorkspace(saved.copy(progress = JSONObject().put("activity",
                if (count == 0) JSONArray() else JSONArray().put(JSONObject().put("action", "CompleteTask").put("revision", count.toString()))).toString()))
        }
        accepted(0); compose.waitForIdle()
        compose.onNodeWithTag("world-joy").assertDoesNotExist()
        accepted(1)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("world-joy").fetchSemanticsNodes().isNotEmpty() }
        screenshot("world-en-joy")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("world-joy").fetchSemanticsNodes().isEmpty() }
        accepted(1); compose.waitForIdle()
        compose.onNodeWithTag("world-joy").assertDoesNotExist()
    }
    @Test fun short_display_keeps_task_access_without_decoration() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("worldShort") == "true")
        fixture()
        compose.onNodeWithTag("household-world").assertDoesNotExist()
        compose.onNodeWithTag("adventure-signpost").assertIsDisplayed()
        compose.onNodeWithTag("capture").assertIsDisplayed()
        val row = "queue-row-${tasks.first().getString("id")}"
        compose.onNodeWithTag("queue").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).assertIsDisplayed()
        screenshot("world-en-short")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(250)
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
