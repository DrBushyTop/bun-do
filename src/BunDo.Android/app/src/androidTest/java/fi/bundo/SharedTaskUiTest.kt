package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.StateRestorationTester
import android.view.ViewGroup
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.SharedWorkspaceScreen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SharedTaskUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val alice = UUID.randomUUID().toString()
    private val bob = UUID.randomUUID().toString()

    private fun fixture(language: String, appearance: String, claimed: Boolean = false, fontScale: Float = 1.3f, restoration: StateRestorationTester? = null): Pair<AccountData, SharedWorkspace> {
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
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language)); this.fontScale = fontScale
        }
        val translated = compose.activity.createConfigurationContext(configuration)
        val content: @Composable () -> Unit = {
                CompositionLocalProvider(LocalContext provides translated, androidx.compose.ui.platform.LocalResources provides translated.resources, LocalConfiguration provides configuration,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    fi.bundo.ui.HouseholdMotionProvider { BunDoTheme("light") {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            SharedWorkspaceScreen(data, state, appearance, {}, {})
                        }
                    } }
                }
        }
        if (restoration == null) compose.runOnUiThread { compose.activity.setContent(content = content) }
        else {
            compose.runOnUiThread { compose.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews() }
            restoration.setContent(content)
        }
        compose.waitUntil(10_000) { runCatching { compose.onAllNodesWithText("Vie paperit kierrätykseen").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        return data to state
    }

    @Test fun tabHistorySurvivesSavedStateAndDirectHomeClearsIt() {
        val restoration = StateRestorationTester(compose)
        fixture("en", "light", fontScale = 1f, restoration = restoration)
        compose.onNodeWithTag("household-activity").performClick()
        compose.onNodeWithTag("household-together").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("household-together").assertIsSelected()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("household-activity").assertIsSelected()
        compose.onNodeWithTag("household-together").performClick()
        compose.onNodeWithTag("household-queue").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("household-queue").assertIsSelected()
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun systemBackRetracesTabsAndNestedAdventuresWithoutFinishingHome() {
        fixture("en", "light", fontScale = 1f)
        fun back() {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
        compose.onNodeWithTag("household-activity").performClick()
        compose.onNodeWithTag("household-together").performClick()
        back(); compose.onNodeWithTag("household-activity").assertIsSelected()
        back(); compose.onNodeWithTag("household-queue").assertIsSelected()
        back(); assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("household-activity").performClick()
        compose.onNodeWithTag("settings").performClick()
        back(); compose.onNodeWithTag("household-activity").assertIsSelected()
        back(); compose.onNodeWithTag("household-queue").assertIsSelected()
        compose.onNodeWithTag("adventure-signpost").performClick()
        compose.onNodeWithTag("adventure-create").performScrollTo().performClick()
        back(); compose.onNodeWithTag("adventure-screen").assertIsDisplayed()
        back(); compose.onNodeWithTag("household-queue").assertIsSelected()
        repeat(2) { back() }; assertFalse(compose.activity.isFinishing)
    }

    @Test fun backGestureReturnsToPreviousTabAndDoesNotExitHome() {
        org.junit.Assume.assumeTrue(android.provider.Settings.Secure.getInt(compose.activity.contentResolver, "navigation_mode", 0) == 2)
        fixture("en", "light", fontScale = 1f)
        compose.onNodeWithTag("household-activity").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = compose.activity.resources.displayMetrics
        fun gesture() {
            val y = metrics.heightPixels / 2
            automation.executeShellCommand("input swipe 1 $y ${metrics.widthPixels / 2} $y 350").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
            }
            compose.waitForIdle()
        }
        gesture()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("household-queue") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        gesture(); assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("capture").assertIsDisplayed()
    }

    @Test fun journeyOpensFromTogetherAndReturnsToTasksWithoutChangingThem() {
        val (data, state) = fixture("en", "light", fontScale = 2f)
        runBlocking { data.database.shared().saveWorkspace(state.copy(progress = journeyFixture().toString())) }
        compose.onNodeWithTag("household-together").performClick()
        compose.onNodeWithTag("journey-open").performScrollTo().performClick()
        compose.onNodeWithTag("journey-progress").performScrollTo().assertTextEquals("2 of 5 tasks toward the next stop")
        compose.onNodeWithTag("household-queue").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertExists()
        assertEquals(2, runBlocking { data.database.shared().projectionRows(state.scope, "initial") }.count {
            !JSONObject(it.snapshot).has("entityType")
        })
    }

    @Test fun householdDestinationsOpenActivityTasksAndKeepTogetherAccessibleAtLargeText() {
        val (data, state) = fixture("en", "light", fontScale = 2f)
        val id = runBlocking {
            val task = data.database.shared().base(state.scope).map { JSONObject(it.snapshot) }.first { it.optString("title") == "Vie paperit kierrätykseen" }
            data.database.shared().saveWorkspace(state.copy(progress = progressFixture(task.getString("id"), alice).toString()))
            task.getString("id")
        }
        compose.onNodeWithTag("household-activity").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("activity-task-$id").fetchSemanticsNodes().isNotEmpty() }
        screenshot("progress-native-activity-large")
        compose.onNodeWithTag("activity-task-$id").performScrollTo().performClick()
        compose.onNodeWithTag("task-complete").performScrollTo().assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("household-together").performClick()
        screenshot("progress-native-together-large")
        compose.onNodeWithTag("progress-streak").performScrollTo().assertExists()
        compose.onNodeWithTag("household-queue").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().assertExists()
    }

    @Test fun gardenCanBeHiddenWhileAdventureAccessRemainsDuringReordering() {
        val preferences = compose.activity.getSharedPreferences("appearance", 0)
        val previous = preferences.getBoolean("world", true)
        preferences.edit().putBoolean("world", true).commit()
        try {
            fixture("en", "light", fontScale = 1f)
            compose.onNodeWithTag("household-world").assertIsDisplayed()
            compose.onNodeWithTag("voice").assertTextContains("Speak a task", substring = true)
            compose.onNodeWithTag("adventure-signpost").assertIsDisplayed()
            screenshot("walkthrough-en-garden.png")
            compose.onNodeWithTag("queue-reorder").performClick()
            compose.onNodeWithTag("household-world").assertDoesNotExist()
            compose.onNodeWithTag("adventure-signpost").assertIsDisplayed()
            compose.onNodeWithTag("reorder-done").performClick()
            compose.onNodeWithTag("settings").performClick()
            compose.onNodeWithTag("show-world").performScrollTo().performClick()
            compose.onNodeWithTag("back").performClick()
            compose.onNodeWithTag("household-world").assertDoesNotExist()
        } finally { preferences.edit().putBoolean("world", previous).commit() }
    }

    @Test fun finnishGardenKeepsCaptureAndQueueWithinReach() {
        fixture("fi", "light", fontScale = 1f)
        compose.onNodeWithTag("voice").assertTextContains("Puhu tehtävä", substring = true)
        compose.onNodeWithTag("capture").assertIsDisplayed()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertIsDisplayed()
        screenshot("walkthrough-fi-garden.png")
    }

    @Test fun tomorrowCaptureExplainsPlacementAndPersistsUrgencyInEnglish() {
        val (data, state) = fixture("en", "light")
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("Collect parcel")
        compose.onNodeWithTag("due-tomorrow").performScrollTo().performClick()
        compose.onNodeWithTag("task-urgent").performScrollTo().performClick()
        compose.onNodeWithTag("placement-hint").performScrollTo().assertTextEquals("Added after the urgent and soon-due tasks at the front of the queue.")
        screenshot("details-en-placement")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) {
            runBlocking { data.database.shared().intents(state.scope).any { it.kind == "CreateTask" } }
        }
        compose.onNodeWithText("Saved among urgent and soon-due tasks.").assertExists()
        val intent = runBlocking { data.database.shared().intents(state.scope).first { it.kind == "CreateTask" } }
        assertTrue(JSONObject(intent.details!!).getBoolean("urgent"))
        assertEquals("DATE_ONLY", JSONObject(intent.details).getJSONObject("due").getString("kind"))
    }

    @Test fun finnishLargeTextShowsAttributionAndSnoozesOffline() {
        val (data, state) = fixture("fi", "light", fontScale = 2f)
        runBlocking {
            val dao = data.database.shared()
            val base = dao.base(state.scope).first { JSONObject(it.snapshot).optString("title") == "Vie paperit kierrätykseen" }
            val task = JSONObject(base.snapshot).put("creation", JSONObject().put("actorId", alice)
                .put("capturedAt", "2026-09-14T07:00:00Z").put("acceptedAt", "2026-09-14T08:00:00Z"))
                .put("lastChange", JSONObject().put("actorId", JSONObject.NULL).put("source", "AI").put("at", "2026-09-14T08:10:00Z"))
            dao.saveBase(base.copy(snapshot = task.toString()))
            dao.saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
        }
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-creation").performScrollTo().assertTextContains("Alice", substring = true)
        compose.onNodeWithTag("task-last-change").performScrollTo().assertExists()
        screenshot("details-fi-attribution")
        compose.onNodeWithTag("snooze-tomorrow").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { data.database.shared().intents(state.scope).any { it.kind == "SetSnooze" } } }
        compose.onNodeWithTag("task-claim").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("task-last-change").performScrollTo().assertExists()
        screenshot("details-fi-snoozed")
    }

    @Test fun localModificationTimeIsDistinctFromAcceptedHistory() {
        val (data, state) = fixture("en", "light")
        val id = runBlocking {
            data.database.shared().base(state.scope).map { JSONObject(it.snapshot) }
                .first { it.optString("title") == "Vie paperit kierrätykseen" }.getString("id")
        }
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        runBlocking { repository.commit(repository.draft(id).copy(description = "Offline edit")) }
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("task-last-change").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-last-change").assertTextContains("Local change, Alice", substring = true)
        runBlocking {
            val row = data.database.shared().task(state.scope, id)!!
            val task = JSONObject(row.snapshot)
            task.getJSONObject("lastChange").put("source", "HUMAN").put("at", "2026-09-15T08:00:00Z")
            data.database.shared().saveProjection(row.copy(snapshot = task.toString()))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Changed by Alice", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-last-change").assertTextContains("Changed by Alice", substring = true)
    }

    @Test fun englishSplitInstructionsCanBeQueuedAndCancelledWithoutChangingParent() {
        val (data, state) = fixture("en", "light")
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-add").performScrollTo().performClick()
        compose.onNodeWithTag("split-instructions").performScrollTo().performTextInput("Separate paper and cardboard")
        compose.onNodeWithTag("split-generate").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("split-cancel") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("split-cancel").performScrollTo().assertIsEnabled()
        screenshot("split-en-pending")
        compose.onNodeWithTag("split-cancel").performClick()
        compose.waitUntil(5_000) { runBlocking { data.database.shared().intents(state.scope).any { it.kind == "CancelCleanup" } } }
        assertEquals(2, runBlocking { data.database.shared().projectionRows(state.scope, state.projectionGeneration).count { it.id != "root-order" } })
    }

    @Test fun finnishLargeSplitPreviewCanBeEditedAndSelectedBeforeAcceptance() {
        val (data, state) = fixture("fi", "light", fontScale = 2f)
        runBlocking {
            val dao = data.database.shared()
            val base = dao.base(state.scope).first { JSONObject(it.snapshot).optString("title") == "Vie paperit kierrätykseen" }
            val task = JSONObject(base.snapshot)
            val source = JSONObject().put("sourceTitle", task.getString("title")).put("sourceDescription", JSONObject.NULL)
                .put("title", task.getJSONObject("titleVersion")).put("description", task.getJSONObject("descriptionVersion"))
                .put("state", JSONObject().put("lifecycle", "1").put("claim", "1").put("hierarchy", "1").put("deletion", "1").put("subtree", "0").put("snooze", "0"))
            task.put("cleanup", JSONObject().put("id", "split-fi").put("mode", "SPLIT").put("status", "READY")
                .put("splitSource", source).put("proposal", JSONObject().put("items", JSONArray().put("Kerää paperit").put("Vie pahvit"))))
            dao.saveBase(base.copy(snapshot = task.toString()))
            dao.saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
        }
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-add").performScrollTo().performClick()
        compose.onNodeWithTag("split-review").performScrollTo().performClick()
        compose.onNodeWithTag("split-item-0").performScrollTo().performTextReplacement("Kerää paperit keittiöstä")
        compose.onNodeWithTag("split-select-1").performScrollTo().performClick()
        screenshot("split-fi-preview-large")
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(5_000) { runBlocking { data.database.shared().intents(state.scope).any { it.kind == "SplitTask" } } }
        val split = runBlocking { data.database.shared().intents(state.scope).single { it.kind == "SplitTask" } }
        assertEquals("Kerää paperit keittiöstä", split.description)
        assertEquals("Vie paperit kierrätykseen", split.title)
    }

    @Test fun accessRemovalDismissesOpenSplitPreview() = removedSplit(false)
    @Test fun accessRemovalDismissesSplitDictation() = removedSplit(true)

    private fun removedSplit(dictating: Boolean) {
        val (data, state) = fixture("en", "light")
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val id = runBlocking { repository.taskStates.first().first { it.getString("title") == "Vie paperit kierrätykseen" }.getString("id") }
        runBlocking {
            val draft = repository.checklistDraft(id)
            repository.saveChecklistDraft(draft.copy(details = JSONObject().put("sourceDescription", "Private parent description")
                .put("rows", JSONArray().put(JSONObject().put("text", "Private generated step").put("originalText", "Private generated step").put("selected", true))).toString()))
        }
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-add").performScrollTo().performClick()
        compose.onNodeWithTag("split-item-0").assertExists()
        if (dictating) {
            compose.onNodeWithTag("split-dictate").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("split-item-0").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Dictate instructions").assertExists()
        }
        runBlocking { repository.block(repository.prepare(1000, 1)!!, "FORBIDDEN") }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("checklist-save").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("Dictate instructions").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Private parent description").assertDoesNotExist()
        compose.onNodeWithText("Private generated step").assertDoesNotExist()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertDoesNotExist()
    }

    @Test fun newTaskOffersSplitAfterLocalSave() {
        val (data, state) = fixture("en", "light")
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("Prepare kitchen")
        compose.onNodeWithTag("editor-split").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("split-instructions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("split-instructions").performScrollTo().assertExists()
        val intents = runBlocking { data.database.shared().intents(state.scope) }
        assertEquals(1, intents.size); assertEquals("CreateTask", intents.single().kind)
        assertNotNull(runBlocking { data.database.shared().draft(state.scope, "checklist:${intents.single().taskId}") })
    }

    @Test fun cleanupPendingAndCancelStayUsableAtLargeFinnishText() {
        fixture("fi", "light", fontScale = 2f)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup-request").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("cleanup-cancel") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cleanup-cancel").performScrollTo().assertIsEnabled()
        screenshot("cleanup-fi-pending")
        compose.onNodeWithTag("cleanup-cancel").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cleanup-request").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cleanup-request").performScrollTo().assertIsEnabled()
    }

    @Test fun cleanupSuggestionCanBeComparedAndAppliedInEnglish() {
        val (data, state) = fixture("en", "light")
        runBlocking {
            val dao = data.database.shared()
            val base = dao.base(state.scope).first { JSONObject(it.snapshot).optString("title") == "Vie paperit kierrätykseen" }
            val task = JSONObject(base.snapshot).put("cleanup", JSONObject().put("id", "request-1").put("status", "READY")
                .put("proposal", JSONObject().put("title", "Vie paperit keräykseen").put("description", "Myös pahvit")
                    .put("language", "fi").put("needsReview", true)))
            dao.saveBase(base.copy(snapshot = task.toString()))
            dao.saveProjection(SharedProjection(state.scope, task.getString("id"), task.toString()))
        }
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup-compare").performScrollTo().performClick()
        compose.onNodeWithTag("cleanup-apply").performScrollTo().assertIsEnabled()
        screenshot("cleanup-en-compare")
        compose.onNodeWithTag("cleanup-apply").performClick()
        compose.waitUntil(5_000) {
            runBlocking { data.database.shared().intents(state.scope).any { it.kind == "ApplyCleanup" } }
        }
    }

    @Test fun finnishChecklistPreviewProgressAndChildNavigationAtLargeText() {
        val (data, state) = fixture("fi", "light", fontScale = 2f)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-add").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-draft").performTextInput("Kerää paperit\nVie keräykseen")
        compose.runOnIdle {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                (view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
        compose.onNodeWithTag("checklist-save").assertIsDisplayed()
        screenshot("checklist-fi-preview-large.png")
        compose.onNodeWithTag("checklist-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Kerää paperit").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kerää paperit").performScrollTo().assertIsDisplayed()
        screenshot("checklist-fi-items-large.png")
        val children = runBlocking { SharedRepository(data.database, DataLease(), state.scope, state.registration).taskStates.first() }.filter { it.nullableString("parentId") != null }
        val first = children.single { it.getString("title") == "Kerää paperit" }.getString("id")
        compose.onNodeWithTag("checklist-check-$first").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Valmiit vaiheet: 1 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Valmiit vaiheet: 1 / 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kerää paperit").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-parent").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Valmiit vaiheet: 1 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Valmiit vaiheet: 1 / 2").performScrollTo().assertIsDisplayed()
    }

    @Test fun finnishClaimCompleteAndReopenKeepDetailsOpenAndHistoryReachable() {
        val (data, state) = fixture("fi", "light")
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-claim").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Alice tekee tätä").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alice tekee tätä").performScrollTo().assertIsDisplayed()
        screenshot("tasks-fi-light.png")
        compose.onNodeWithTag("task-complete").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-reopen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("queue-views").performClick()
        compose.onNodeWithTag("task-history-view").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-reopen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-claim").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-claim").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("ClaimTask", "CompleteTask", "ReopenTask"),
            runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun englishConfirmationRejectsAChangedClaimAndMoveButtonsNeedNoDragging() {
        val (data, state) = fixture("en", "light", claimed = true)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-complete").performScrollTo().performClick()
        compose.onNodeWithText("Bob is working on this task. Mark it complete anyway?").assertIsDisplayed()
        screenshot("tasks-en-light-confirm.png")
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

    @Test fun englishDeleteUndoAndRecoveryView() {
        val (data, state) = fixture("en", "light")
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-delete").performScrollTo().performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).size == 2 } }
        compose.onNodeWithTag("task-delete").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).size == 3 } }
        compose.onNodeWithTag("edit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertDoesNotExist()
        compose.onNodeWithTag("queue-views").performClick()
        compose.onNodeWithTag("task-deleted-view").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().assertIsDisplayed()
        screenshot("deletion-recovery-en.png")
        compose.onNodeWithText("Vie paperit kierrätykseen").performClick()
        compose.onNodeWithTag("task-restore").performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).size == 4 } }
        assertEquals(listOf("DeleteTask", "RestoreTask", "DeleteTask", "RestoreTask"),
            runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun finnishDeletedDetailsRemainReadable() {
        fixture("fi", "light", fontScale = 2.0f)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-delete").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("task-restore").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-restore").performScrollTo().assertIsDisplayed()
        screenshot("deletion-detail-fi.png")
    }

    @Test fun compactFiltersReorderFullQueueWithKeyboardAndAccessibleButtons() {
        val (data, state) = fixture("en", "light", fontScale = 1f)
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        val ids = runBlocking { repository.taskStates.first().map { it.getString("id") } }
        runBlocking { repository.act("ClaimTask", repository.taskStates.first().first().toString()) }
        compose.onNodeWithTag("queue-filter").performClick()
        compose.onNodeWithTag("filter-mine").performClick()
        compose.onNodeWithText("Järjestä hylly").assertDoesNotExist()
        compose.onNodeWithTag("queue-reorder").performClick()
        compose.onNodeWithText("Järjestä hylly").assertExists()
        compose.onNodeWithTag("queue-filter").assertIsNotEnabled()
        screenshot("finish-en-reorder.png")
        compose.onNodeWithTag("reorder-handle-${ids[1]}").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("reorder-handle-${ids[1]}").performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitUntil(5000) { runBlocking { repository.taskStates.first().first().getString("id") == ids[1] } }
        compose.onNodeWithTag("reorder-later-${ids[1]}").performClick()
        compose.waitUntil(5000) { runBlocking { repository.taskStates.first().first().getString("id") == ids[0] } }
        compose.onNodeWithTag("reorder-handle-${ids[0]}").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("reorder-handle-${ids[0]}").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("reorder-done").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("queue-reorder") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue-reorder").performClick()
        compose.onNodeWithTag("reorder-done").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("queue-filter").assertIsEnabled()
        compose.onNodeWithTag("queue-filter").performClick()
        compose.onNodeWithTag("filter-unclaimed").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertDoesNotExist()
        compose.onNodeWithText("Järjestä hylly").assertExists()
        assertEquals(listOf("ClaimTask", "MoveTask", "MoveTask"), runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun queueSwipeRequiresOtherClaimConfirmationAndUndoIsImmediate() {
        val (data, state) = fixture("en", "light", claimed = true, fontScale = 1f)
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        val id = runBlocking { repository.taskStates.first().first().getString("id") }
        compose.onNodeWithTag("queue-row-$id").performTouchInput { swipeRight() }
        compose.onNodeWithText("Bob is working on this task. Mark it complete anyway?").assertIsDisplayed()
        assertTrue(runBlocking { data.database.shared().intents(state.scope).isEmpty() })
        compose.onNodeWithTag("queue-confirm-complete").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000) { runBlocking { data.database.shared().intents(state.scope).size == 2 } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Vie paperit kierrätykseen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Vie paperit kierrätykseen").assertExists()
        assertEquals(listOf("CompleteTask", "ReopenTask"), runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
        screenshot("finish-en-queue.png")
    }

    @Test fun handleDragCanCancelOrCommitWithoutCompletingTask() {
        val (data, state) = fixture("en", "light", fontScale = 1f)
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        val ids = runBlocking { repository.taskStates.first().map { it.getString("id") } }
        compose.onNodeWithTag("queue-reorder").performClick()
        val first = compose.onNodeWithTag("reorder-handle-${ids[0]}").fetchSemanticsNode().boundsInRoot.center
        val second = compose.onNodeWithTag("reorder-handle-${ids[1]}").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(first); advanceEventTime(700); moveTo(second); cancel() }
        compose.waitForIdle()
        assertTrue(runBlocking { data.database.shared().intents(state.scope).isEmpty() })
        compose.onRoot().performTouchInput { down(first); advanceEventTime(700); moveTo(second); advanceEventTime(100); up() }
        compose.waitUntil(5000) { runBlocking { data.database.shared().intents(state.scope).isNotEmpty() } }
        assertEquals(listOf("MoveTask"), runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
        compose.onNodeWithTag("reorder-done").performClick()
    }

    @Test fun finnishLargeTextHasIllustratedDetailAndMotionInsteadOfThemeSwitch() {
        val (data, state) = fixture("fi", "light", fontScale = 2f)
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        val id = runBlocking { repository.commit(repository.draft(InboxRepository.NEW_DRAFT).copy(title = "Varaa pyörän huolto", description = "Tarkista jarrut ja vaihteet.")) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Varaa pyörän huolto").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("queue").performScrollToNode(hasText("Varaa pyörän huolto"))
        compose.onNodeWithText("Varaa pyörän huolto").performClick()
        compose.onNodeWithTag("task-creation").assertExists()
        compose.onNodeWithTag("task-last-change").assertDoesNotExist()
        screenshot("finish-fi-large-art.png")
        compose.onNodeWithTag("edit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("decorative-motion").performClick()
        compose.onNodeWithTag("decorative-motion").assertIsOff()
        compose.onNodeWithText("Tumma").assertDoesNotExist()
        screenshot("finish-fi-large-settings.png")
        compose.onNodeWithTag("decorative-motion").performClick()
        compose.onNodeWithTag("back").performClick()
        assertTrue(runBlocking { repository.taskStates.first().any { it.getString("id") == id } })
    }

    @Test fun newCaptureHasImmediateUndoWithoutDeletingAnEditedTask() {
        val (data, state) = fixture("en", "light", fontScale = 1f)
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("Buy apples")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5000) { runBlocking { data.database.shared().intents(state.scope).size == 2 } }
        compose.onNodeWithText("Buy apples").assertDoesNotExist()
        assertEquals(listOf("CreateTask", "DeleteTask"), runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun claimFiltersIncludeChecklistStepsAndTreatDepartedClaimsAsUnclaimed() {
        val (data, state) = fixture("en", "light", fontScale = 1f)
        val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
        runBlocking {
            val root = repository.taskStates.first().first().getString("id")
            repository.saveChecklistDraft(repository.checklistDraft(root).copy(text = "Collect paper"))
            repository.commitChecklist(root)
            val child = repository.taskStates.first().single { it.nullableString("parentId") == root }
            repository.act("ClaimTask", child.toString())
        }
        compose.onNodeWithTag("queue-filter").performClick()
        compose.onNodeWithTag("filter-mine").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertExists()
        compose.onNodeWithText("Järjestä hylly").assertDoesNotExist()
        compose.onNodeWithTag("queue-filter").performClick()
        compose.onNodeWithTag("filter-unclaimed").performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").assertDoesNotExist()
        runBlocking {
            val dao = data.database.shared()
            val second = dao.base(state.scope).first { JSONObject(it.snapshot).optString("title") == "Järjestä hylly" }
            val task = JSONObject(second.snapshot).put("claimantId", bob)
            dao.saveBase(second.copy(snapshot = task.toString()))
            dao.saveProjection(SharedProjection(state.scope, second.id, task.toString()))
            val current = dao.workspace(state.scope)!!
            val members = JSONObject(current.membership!!)
            members.getJSONArray("members").getJSONObject(1).put("active", false)
            dao.saveWorkspace(current.copy(membership = members.toString()))
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Järjestä hylly").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Järjestä hylly").assertExists()
    }

    @androidx.test.filters.LargeTest
    @Test fun talkBackExposesNamedReorderActionsAndCanMoveWithoutTouchDragging() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = compose.activity.contentResolver
        val previousServices = android.provider.Settings.Secure.getString(resolver, "enabled_accessibility_services")
        val previousEnabled = android.provider.Settings.Secure.getInt(resolver, "accessibility_enabled", 0)
        fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val talkBack = "com.google.android.marvin.talkback"
        val notificationPermission = "android.permission.POST_NOTIFICATIONS"
        val notificationsGranted = compose.activity.packageManager.checkPermission(notificationPermission, talkBack) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val service = "$talkBack/com.google.android.marvin.talkback.TalkBackService"
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted) shell("pm grant $talkBack $notificationPermission")
            shell("settings put secure enabled_accessibility_services $service")
            shell("settings put secure accessibility_enabled 1")
            val manager = compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            compose.waitUntil(10_000) { manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" } }
            android.os.SystemClock.sleep(1000)
            compose.activityRule.scenario.recreate()
            val (data, state) = fixture("en", "light", fontScale = 1.3f)
            val repository = SharedRepository(data.database, data.lease, state.scope, data.registrationId!!)
            val ids = runBlocking { repository.taskStates.first().map { it.getString("id") } }
            compose.onNodeWithTag("queue-reorder").performSemanticsAction(SemanticsActions.OnClick) { it() }
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.viewIdResourceName == "reorder-handle-${ids[1]}") return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            var handle: android.view.accessibility.AccessibilityNodeInfo? = null
            compose.waitUntil(10_000) { handle = find(automation.rootInActiveWindow); handle != null }
            assertTrue(handle!!.contentDescription.toString().contains("Järjestä hylly"))
            val move = handle!!.actionList.single { it.label?.toString() == "Move earlier" }
            assertTrue(handle!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(handle!!.performAction(move.id))
            compose.waitUntil(5000) { runBlocking { repository.taskStates.first().first().getString("id") == ids[1] } }
            val bitmap = automation.takeScreenshot()
            File(compose.activity.filesDir, "finish-talkback-reorder.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            if (previousServices.isNullOrEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services '$previousServices'")
            shell("settings put secure accessibility_enabled $previousEnabled")
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationsGranted) shell("pm revoke $talkBack $notificationPermission")
            android.os.SystemClock.sleep(500)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the rendered buffer, not only the semantics tree.
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
