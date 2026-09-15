package fi.bundo

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedRecoveryUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    private fun fixture(storage: Boolean, appearance: String): Pair<AccountData, SharedWorkspace> {
        val app = compose.activity.application as BunDoApplication
        val data = app.accounts.active.value!!
        val workspace = UUID.randomUUID().toString()
        val epoch = UUID.randomUUID().toString()
        val registration = data.registrationId!!
        val state = SharedWorkspace("$workspace/$epoch/$registration", workspace, epoch, registration, "Kotityöt",
            nextSequence = "2", revision = "3")
        val id = SharedProtocol.taskId(registration, "1")
        val task = JSONObject().put("id", id).put("title", "Järjestä varaston hyllyt").put("description", "Yhteinen suunnitelma")
            .put("titleVersion", JSONObject().put("fieldVersion", "2").put("humanVersion", "2"))
            .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
            .put("deletionVersion", "1")
        runBlocking {
            data.database.shared().saveWorkspace(state)
            data.database.shared().saveBase(SharedBase(state.scope, id, task.toString()))
            data.database.shared().saveProjection(SharedProjection(state.scope, id, task.toString()))
            data.database.shared().saveIntent(SharedIntent(state.scope, "1", id, "EditTask", "Siivoa varaston alahylly", "Oma muistiinpano",
                true, true, "1", "1", "1", null, SharedProtocol.context(), status = "REJECTED", problem = "FIELD_CONFLICT"))
            if (storage) data.database.shared().saveRecovery(SharedRecovery(state.scope, UUID.randomUUID().toString(), problem = "STORAGE_REQUIRED"))
        }
        compose.runOnUiThread {
            compose.activity.setContent {
                BunDoTheme("light") {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                        SharedWorkspaceScreen(data, state, appearance, {}, {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("shared-recovery").fetchSemanticsNodes().isNotEmpty() }
        return data to state
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the rendered buffer, not only the semantics tree.
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun rejectedRestoreAfterPurgeOffersANewCopyWithoutReplayingRestore() {
        val (data, state) = fixture(false, "light")
        runBlocking {
            val dao = data.database.shared()
            val intent = dao.intents(state.scope).single()
            dao.saveIntent(intent.copy(kind = "RestoreTask", taskAction = "{}", problem = "ENTITY_MISSING"))
            dao.saveBase(SharedBase(state.scope, intent.taskId, JSONObject().put("id", intent.taskId)
                .put("entityType", "PURGED_TASK").put("version", "3").toString()))
            dao.deleteProjectionTask(state.scope, state.projectionGeneration, intent.taskId)
        }
        compose.onNodeWithTag("shared-recovery").performClick()
        compose.onNodeWithText(text(R.string.shared_copy_new)).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).size == 2 } }
        val intents = runBlocking { data.database.shared().intents(state.scope) }
        assertEquals("RestoreTask", intents[0].kind)
        assertEquals("REJECTED", intents[0].status)
        assertEquals("CreateTask", intents[1].kind)
        assertNotEquals(intents[0].taskId, intents[1].taskId)
        assertEquals("Oma muistiinpano", intents[1].description)
    }

    @Test fun lowStorageKeepsQueueAndOffersExportAndSystemCleanup() {
        fixture(true, "light")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text(R.string.shared_storage_required)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.shared_storage_required)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shared_export_saved)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shared_manage_storage)).performScrollTo().assertIsDisplayed()
        screenshot("recovery-storage-light.png")
    }

    @Test fun conflictShowsCurrentAndRetainedTextAndDismissKeepsTheJournal() {
        val (data, state) = fixture(false, "light")
        compose.onNodeWithTag("shared-recovery").performClick()
        compose.onNodeWithText("Siivoa varaston alahylly").assertIsDisplayed()
        val currentTitle = compose.activity.getString(R.string.shared_current, "Järjestä varaston hyllyt")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(currentTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(currentTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.shared_reapply)).performScrollTo().assertIsDisplayed()
        screenshot("recovery-conflict-light.png")
        compose.onNodeWithText(text(R.string.shared_dismiss)).performScrollTo().performClick()
        compose.waitUntil(10_000) { runBlocking { data.database.shared().intents(state.scope).single().status == "DISMISSED" } }
        assertEquals("Siivoa varaston alahylly", runBlocking { data.database.shared().intents(state.scope).single().title })
    }
}
