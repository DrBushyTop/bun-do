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

@RunWith(AndroidJUnit4::class)
class SharedTaskUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val alice = UUID.randomUUID().toString()
    private val bob = UUID.randomUUID().toString()

    private fun fixture(language: String, appearance: String, claimed: Boolean = false, fontScale: Float = 1.3f): Pair<AccountData, SharedWorkspace> {
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
        // Dialog windows read the activity resources rather than only the composition overrides.
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(configuration, compose.activity.resources.displayMetrics)
        val translated = compose.activity.createConfigurationContext(configuration)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalContext provides translated, LocalConfiguration provides configuration,
                    LocalActivityResultRegistryOwner provides compose.activity) {
                    BunDoTheme(appearance) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            SharedWorkspaceScreen(data, state, appearance, {}, {})
                        }
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Vie paperit kierrätykseen").fetchSemanticsNodes().isNotEmpty() }
        return data to state
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

    @Test fun englishSplitInstructionsCanBeQueuedAndCancelledWithoutChangingParent() {
        val (data, state) = fixture("en", "light")
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("checklist-add").performScrollTo().performClick()
        compose.onNodeWithTag("split-instructions").performScrollTo().performTextInput("Separate paper and cardboard")
        compose.onNodeWithTag("split-generate").performScrollTo().performClick()
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Sinulla työn alla").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Sinulla työn alla").performScrollTo().assertIsDisplayed()
        screenshot("tasks-fi-light.png")
        compose.onNodeWithTag("task-complete").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-reopen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-reopen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("task-history-view").performScrollTo().performClick()
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-reopen").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-claim").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("task-claim").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("ClaimTask", "CompleteTask", "ReopenTask"),
            runBlocking { data.database.shared().intents(state.scope).map { it.kind } })
    }

    @Test fun englishConfirmationRejectsAChangedClaimAndMoveButtonsNeedNoDragging() {
        val (data, state) = fixture("en", "dark", claimed = true)
        compose.onNodeWithText("Vie paperit kierrätykseen").performScrollTo().performClick()
        compose.onNodeWithTag("task-complete").performScrollTo().performClick()
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
        compose.onNodeWithTag("task-deleted-view").performScrollTo().performClick()
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
        compose.onNodeWithTag("task-restore").performScrollTo().assertIsDisplayed()
        screenshot("deletion-detail-fi.png")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
