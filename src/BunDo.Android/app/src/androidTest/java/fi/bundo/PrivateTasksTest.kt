package fi.bundo

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.res.Configuration
import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.HouseholdMotionProvider
import fi.bundo.ui.SharedWorkspaceScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.UUID

class PrivateTasksTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InboxDatabase::class.java)
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(migrations).around(compose)
    private val me = UUID.randomUUID().toString()
    private fun fixture(language: String? = null, fontScale: Float = 1f): Triple<AccountData, SharedWorkspace, SharedWorkspace> {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val registration = data.registrationId!!
        val membership = JSONObject().put("me", me).put("ownerId", me).put("members", JSONArray()
            .put(JSONObject().put("id", me).put("active", true).put("displayName", "Me"))).toString()
        fun workspace(personal: Boolean): SharedWorkspace {
            val id = UUID.randomUUID().toString(); val epoch = UUID.randomUUID().toString()
            return SharedWorkspace("$id/$epoch/$registration", id, epoch, registration, if (personal) "Only me" else "Home",
                revision = "1", membership = membership, personal = personal)
        }
        val home = workspace(false); val personal = workspace(true)
        runBlocking {
            data.database.shared().saveWorkspace(home)
            data.database.shared().saveWorkspace(personal)
            data.selectHousehold(home.workspaceId, home.epoch, home.name)
        }
        val config = Configuration(compose.activity.resources.configuration).apply {
            if (language != null) setLocale(Locale.forLanguageTag(language))
            this.fontScale = fontScale
        }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources,
                LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                val selected by data.selectedWorkspace.collectAsStateWithLifecycle()
                selected?.let { HouseholdMotionProvider { BunDoTheme("light") { SharedWorkspaceScreen(data, it, "light", {}, {}) } } }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("task-audience").fetchSemanticsNodes().isNotEmpty() }
        return Triple(data, home, personal)
    }

    @Test fun capture_can_change_audience_without_publishing_to_household() {
        val (data, home, personal) = fixture()
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("My private appointment")
        compose.onNodeWithTag("description").performTextInput("Private notes")
        compose.onNodeWithTag("task-audience").performClick()
        compose.onNodeWithTag("audience-private").performClick()
        compose.waitUntil(5_000) { data.selectedWorkspace.value?.personal == true }
        compose.onNodeWithTag("title").assertTextContains("My private appointment")
        compose.onNodeWithTag("save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("title").fetchSemanticsNodes().isEmpty() }
        runBlocking {
            assertTrue(data.database.shared().intents(home.scope).isEmpty())
            val intents = data.database.shared().intents(personal.scope)
            assertEquals(1, intents.size)
            assertEquals("My private appointment", intents.single().title)
            assertEquals("Private notes", intents.single().description)
        }
    }

    @Test fun changing_audience_never_overwrites_an_existing_capture_draft() {
        val (data, home, personal) = fixture()
        runBlocking { data.database.shared().saveDraft(SharedDraft(personal.scope, InboxRepository.NEW_DRAFT, "Already private", "Keep this", 1)) }
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("Household draft")
        compose.onNodeWithTag("task-audience").performClick()
        compose.onNodeWithTag("audience-private").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.visibility_draft_conflict)).fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            assertEquals("Already private", data.database.shared().draft(personal.scope, InboxRepository.NEW_DRAFT)!!.title)
            assertEquals("Household draft", data.database.shared().draft(home.scope, InboxRepository.NEW_DRAFT)!!.title)
            assertTrue(data.database.shared().intents(home.scope).isEmpty())
        }
    }

    @Test fun pending_visibility_requests_survive_restart_and_are_not_capture_drafts() = runBlocking {
        val (data, home, personal) = fixture()
        val root = JSONObject().put("id", UUID.randomUUID().toString()).put("title", "Mine").put("description", JSONObject.NULL)
            .put("parentId", JSONObject.NULL).put("creation", JSONObject().put("actorId", me))
        data.database.shared().saveBase(SharedBase(home.scope, root.getString("id"), root.toString()))
        val repository = SharedRepository(data.database, data.lease, home.scope, data.registrationId!!)
        val preview = repository.visibilityPreview(root.getString("id"), personal)
        val request = preview.request()
        assertEquals(request.toString(), preview.request().toString())
        repository.saveVisibilityRequest(request)
        val restarted = SharedRepository(data.database, data.lease, home.scope, data.registrationId!!)
        assertTrue(restarted.drafts.first().isEmpty())
        data.database.shared().saveWorkspace(home.copy(revision = "2"))
        restarted.saveVisibilityRequest(preview.request())
        assertTrue(SharedSplitPreview.recovery(data.database.shared().allDrafts().single { it.key.startsWith("visibility:") }, home.name).isEmpty())
        assertEquals(request.toString(), data.database.shared().allDrafts().single { it.key.startsWith("visibility:") }.description)
        assertEquals(home.workspaceId, request.getString("workspaceId"))
        assertEquals(personal.workspaceId, request.getString("targetWorkspaceId"))
    }

    @Test fun finnish_visibility_preview_and_creator_menu() = visibilityPreview("fi", 1.3f)
    @Test fun english_large_text_visibility_preview_and_creator_menu() = visibilityPreview("en", 2f)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "private-$name.png")
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun visibilityPreview(language: String, fontScale: Float) {
        val (data, home, _) = fixture(language, fontScale)
        val id = UUID.randomUUID().toString()
        val root = JSONObject().put("id", id).put("title", "Plan a quiet birthday dinner").put("description", "Reserve a table and bring the gift.")
            .put("titleVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
            .put("descriptionVersion", JSONObject().put("fieldVersion", "1").put("humanVersion", "1"))
            .put("deletionVersion", "1").put("lifecycleVersion", "1").put("lifecycle", "OPEN")
            .put("claimVersion", "1").put("hierarchyVersion", "1").put("orderIntentVersion", "1")
            .put("parentId", JSONObject.NULL).put("creation", JSONObject().put("actorId", me))
        runBlocking {
            data.database.shared().saveBase(SharedBase(home.scope, id, root.toString()))
            data.database.shared().saveProjection(SharedProjection(home.scope, id, root.toString()))
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("queue-row-$id").fetchSemanticsNodes().isNotEmpty() }
        screenshot("$language-queue")
        compose.onNodeWithTag("queue-row-$id").performClick()
        compose.onNodeWithTag("task-menu").performClick()
        compose.onNodeWithTag("task-menu-more").performClick()
        compose.onNodeWithTag("task-change-visibility").performScrollTo().assertIsDisplayed()
        screenshot("$language-actions")
        compose.onNodeWithTag("task-change-visibility").performClick()
        val label = if (language == "fi") "Muuta yksityiseksi" else "Make private"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodesWithText(label).filterToOne(hasClickAction()).assertIsDisplayed()
        screenshot("$language-preview")
        compose.onNodeWithText(if (language == "fi") "Takaisin" else "Back").performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("capture").performClick()
        compose.onNodeWithTag("title").performTextInput("New private task")
        compose.onNodeWithTag("task-audience").performClick()
        screenshot("$language-audience")
        compose.onNodeWithTag("audience-private").performClick()
        compose.waitUntil(5_000) { data.selectedWorkspace.value?.personal == true }
        compose.onNodeWithTag("save").assertIsDisplayed()
        screenshot("$language-capture")
    }

    @Test fun migration_keeps_existing_household_audience_and_local_only_tasks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "private-migration-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 17).apply {
                execSQL("INSERT INTO inbox_tasks VALUES ('kept','Local task','','Local task','',1,1)")
                execSQL("INSERT INTO shared_workspaces (scope,workspaceId,epoch,registration,name,selected,nextSequence,revision,acknowledged,workerUntil,workerBoot) VALUES ('scope','home','epoch','registration','Home',1,'1','0','0',0,0)")
                close()
            }
            migrations.runMigrationsAndValidate(name, 18, true, InboxDatabase.MIGRATION_17_18).apply {
                query("SELECT personal,name FROM shared_workspaces").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("Home", it.getString(1)) }
                query("SELECT title FROM inbox_tasks WHERE id='kept'").use { assertTrue(it.moveToFirst()); assertEquals("Local task", it.getString(0)) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }
}
