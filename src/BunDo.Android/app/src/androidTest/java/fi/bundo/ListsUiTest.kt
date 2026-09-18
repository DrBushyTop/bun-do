package fi.bundo

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.bundo.data.*
import fi.bundo.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ListsUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    @Test fun fourTabsPutActivityLast() {
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { SharedHouseholdNavigation("lists") {} } } }
        val names = listOf("queue", "lists", "together", "activity")
        val lefts = names.map { compose.onNodeWithTag("household-$it").assertExists().fetchSemanticsNode().boundsInRoot.left }
        assertEquals(lefts.sorted(), lefts)
        compose.onNodeWithTag("household-lists").assertIsSelected()
    }
    @Test fun previewPreservesNotesAndSelectionEnglish() = preview("en", 1f)
    @Test fun previewFinnishLargeTextKeepsAddAndSaveUsable() = preview("fi", 1.5f)
    @Test fun conflictReviewKeepsEditsAndRequiresConsentToExactVersion() = conflictReview(false)
    @Test fun deletedDefinitionIsSavedUnderANewIdentityAfterReview() = conflictReview(true)
    private fun conflictReview(deleted: Boolean) {
        val mine = SavedHouseholdList(UUID.randomUUID().toString(), "My edited name", "My notes",
            listOf(HouseholdListItem("Keys", "Spare set"), HouseholdListItem("Milk", "Oat")))
        var draft by mutableStateOf(JSONObject().put("list", mine.json()).put("mode", "save").put("version", "1")
            .put("selected", JSONArray().put(true).put(false)).toString())
        var latest by mutableStateOf(JSONObject().put("version", "2").put("lists",
            JSONArray().also { if (!deleted) it.put(mine.copy(title = "Current shared name").json()) }).toString())
        var committed: SavedHouseholdList? = null
        var committedVersion: String? = null
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            ListPreview(draft, true, "LIST_CHANGED", false, {}, { draft = it }, {}, latest) { value, _, _, version ->
                committed = value; committedVersion = version
            }
        } } } }
        compose.onNodeWithTag("list-commit").assertIsNotEnabled()
        compose.onNodeWithTag("list-review").performScrollTo().performClick()
        compose.onNodeWithTag("list-confirm-review").assertExists()
        // Another refresh must invalidate the open review, not silently replace its consent.
        compose.runOnIdle { latest = JSONObject(latest).put("version", "3").toString() }
        compose.onNodeWithTag("list-confirm-review").assertDoesNotExist()
        compose.onNodeWithTag("list-commit").assertIsNotEnabled()
        compose.onNodeWithTag("list-review").performScrollTo().performClick()
        compose.onNodeWithTag("list-confirm-review").performClick()
        compose.onNodeWithTag("list-name").assertTextContains(mine.title)
        compose.onNodeWithTag("list-commit").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("3", committedVersion)
            assertEquals(mine.title, committed!!.title)
            assertEquals(mine.notes, committed!!.notes)
            assertEquals(listOf(mine.items[0]), committed!!.items)
            if (deleted) assertNotEquals(mine.id, committed!!.id) else assertEquals(mine.id, committed!!.id)
        }
    }
    @Test fun backKeepsDraftUntilExplicitDiscard() = runBlocking {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val state = adventureWorkspace()
        data.database.shared().saveWorkspace(state)
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val value = SavedHouseholdList(UUID.randomUUID().toString(), "My list", "Keep me", listOf(HouseholdListItem("Keys")))
        val draft = JSONObject().put("list", value.json()).put("mode", "start").put("version", "0")
            .put("selected", JSONArray().put(true)).toString()
        repository.saveListDraft("preview", draft)
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            SharedListsScreen(repository, state, emptyMap(), true, {}, {}) { _, _ -> }
        } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("list-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("lists-resume").performScrollTo().assertExists()
        assertEquals(draft, repository.listDraft("preview"))
        compose.onNodeWithTag("lists-new").assertIsNotEnabled()
        compose.onNodeWithTag("lists-resume").performClick()
        compose.onNodeWithTag("list-name").assertTextContains("My list")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("lists-discard").performScrollTo().performClick()
        assertEquals(draft, repository.listDraft("preview"))
        compose.onNodeWithTag("lists-confirm-discard").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.listDraft("preview") == null } }
        compose.onNodeWithTag("lists-resume").assertDoesNotExist()
    }
    @Test fun completedFiniteListsLeaveActiveListsButStandingAndSavedListsRemain() = runBlocking {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val saved = SavedHouseholdList(UUID.randomUUID().toString(), "Reusable shopping template", null, listOf(HouseholdListItem("Milk")))
        val state = adventureWorkspace().copy(listLibrary = JSONObject().put("version", "1").put("lists", JSONArray().put(saved.json())).toString())
        data.database.shared().saveWorkspace(state)
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        fun root(title: String, lifecycle: String, kind: String?) = JSONObject().put("id", UUID.randomUUID().toString()).put("title", title)
            .put("parentId", JSONObject.NULL).put("deletion", JSONObject.NULL).put("isChecklist", true)
            .put("lifecycle", lifecycle).put("listKind", kind ?: JSONObject.NULL).put("childOrder", JSONArray())
        val complete = root("Finished finite shopping list", "COMPLETED", null)
        val standing = root("Ongoing groceries", "OPEN", "STANDING")
        val active = root("Unfinished shopping list", "OPEN", null)
        val doneItem = root("Bought milk", "COMPLETED", null).put("parentId", active.getString("id"))
        val openItem = root("Find flour", "OPEN", null).put("parentId", active.getString("id"))
        active.put("childOrder", JSONArray().put(doneItem.getString("id")).put(openItem.getString("id")))
        val tasks = listOf(complete, standing, active, doneItem, openItem).associateBy { it.getString("id") }
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            SharedListsScreen(repository, state, tasks, true, {}, {}) { _, _ -> }
        } } } }
        compose.onNodeWithText("Finished finite shopping list").assertDoesNotExist()
        compose.onNodeWithText("Ongoing groceries").assertExists()
        compose.onNodeWithText("Unfinished shopping list").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.checklist_progress, 1, 2), substring = true).assertExists()
        screenshot("lists-active.png")
        compose.onNodeWithText("Reusable shopping template").assertExists()
        compose.onNodeWithTag("lists-view").performClick()
        compose.onNodeWithTag("lists-completed").performClick()
        compose.onNodeWithText("Finished finite shopping list").assertExists()
        compose.onNodeWithText("Ongoing groceries").assertDoesNotExist()
        screenshot("lists-completed.png")
        compose.onNodeWithText("Reusable shopping template").assertExists()
        Unit
    }

    @Test fun cachedListsStayUsableWhileRefreshIsPendingAndAfterRefreshFails() = runBlocking {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val saved = SavedHouseholdList(UUID.randomUUID().toString(), "Cached packing list", null, listOf(HouseholdListItem("Keys")))
        val state = adventureWorkspace().copy(listLibrary = JSONObject().put("version", "1").put("lists", JSONArray().put(saved.json())).toString())
        data.database.shared().saveWorkspace(state)
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            SharedListsScreen(repository, state, emptyMap(), true, {}, {}) { command, _ ->
                assertNull(command); calls++
                if (calls == 1) response.await()
            }
        } } } }
        compose.waitUntil(5_000) { calls == 1 }
        compose.onNodeWithTag("lists-action-progress").assertDoesNotExist()
        compose.onNodeWithTag("lists-initial-loading").assertDoesNotExist()
        compose.onNodeWithTag("lists-new").assertIsEnabled()
        compose.onNodeWithText(saved.title).performScrollTo().performClick()
        compose.onNodeWithTag("list-commit").assertIsEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        response.completeExceptionally(java.io.IOException("Offline"))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("lists-refresh-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(saved.title).assertExists()
        compose.onNodeWithTag("lists-refresh").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { calls == 2 }
        compose.onNodeWithTag("lists-refresh-error").assertDoesNotExist()
        Unit
    }

    @Test fun firstSavedLibraryLoadDoesNotBlockNewLocalLists() = runBlocking {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val state = adventureWorkspace()
        data.database.shared().saveWorkspace(state)
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        var started = false
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            SharedListsScreen(repository, state, emptyMap(), true, {}, {}) { _, _ -> started = true; response.await() }
        } } } }
        compose.waitUntil(5_000) { started }
        compose.onNodeWithTag("lists-initial-loading").assertExists()
        compose.onNodeWithTag("lists-action-progress").assertDoesNotExist()
        compose.onNodeWithTag("lists-new").assertIsEnabled().performClick()
        response.complete(Unit)
        Unit
    }

    @Test fun pendingSaveRemainsRetryableDuringBackgroundRefresh() = runBlocking {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val state = adventureWorkspace().copy(listLibrary = JSONObject().put("version", "1").put("lists", JSONArray()).toString())
        data.database.shared().saveWorkspace(state)
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val pending = JSONObject().put("action", "save").put("command", JSONObject().put("operationId", UUID.randomUUID().toString())).toString()
        repository.saveListDraft("pending", pending)
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        var retryStarted = false
        compose.runOnUiThread { compose.activity.setContent { BunDoTheme("light") { Surface {
            SharedListsScreen(repository, state, emptyMap(), true, {}, {}) { command, retry ->
                assertNull(command)
                if (retry) {
                    assertEquals(pending, repository.listDraft("pending"))
                    retryStarted = true
                    repository.saveListDraft("pending", null)
                } else response.await()
            }
        } } } }
        val retryLabel = compose.activity.getString(R.string.lists_retry_save)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(retryLabel).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { retryStarted && compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isEmpty() }
        assertNull(repository.listDraft("pending"))
        response.complete(Unit)
        Unit
    }

    @Test fun listTypeChoicesReuseIconsAndHideUnrelatedListsEnglish() = types("en", 1f)
    @Test fun listTypeChoicesRemainUsableFinnishLargeText() = types("fi", 1.5f)
    private fun types(language: String, scale: Float) {
        val data = (compose.activity.application as BunDoApplication).accounts.active.value!!
        val state = adventureWorkspace()
        runBlocking { data.database.shared().saveWorkspace(state) }
        val repository = SharedRepository(data.database, data.lease, state.scope, state.registration)
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") { Surface { Box(Modifier.safeDrawingPadding()) {
                        SharedListsScreen(repository, state, emptyMap(), true, {}, {}) { _, _ -> }
                    } } }
                }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("lists-new") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lists-new").performClick()
        compose.onNodeWithTag("list-types").assertExists()
        compose.onNodeWithTag("lists-screen").assertDoesNotExist()
        screenshot("list-types-$language.png")
        for (index in 0..5) {
            compose.onNodeWithTag("list-type-$index").performScrollTo().assertHasClickAction()
            compose.onNodeWithTag("list-type-icon-$index", useUnmergedTree = true).assertIsDisplayed()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("lists-new").assertExists()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.filesDir, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun preview(language: String, scale: Float) {
        val list = SavedHouseholdList(UUID.randomUUID().toString(), "Cottage", "Weekend", listOf(HouseholdListItem("Keys", "Spare set")))
        var draft by mutableStateOf(JSONObject().put("list", list.json()).put("mode", "start").put("standing", false)
            .put("version", "0").put("selected", JSONArray().put(true)).toString())
        var committed: SavedHouseholdList? = null
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") { Surface { Box(Modifier.safeDrawingPadding()) {
                        ListPreview(draft, true, null, false, {}, { draft = it }, {}) { value, _, _, _ -> committed = value }
                    } } }
                }
            }
        } }
        compose.onNodeWithTag("list-add-text").performScrollTo().performTextInput("Milk")
        compose.onNodeWithTag("list-add").performScrollTo()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(if (language == "fi") "Lisää" else "Add", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        compose.onNodeWithTag("list-add").performClick()
        compose.onNodeWithTag("list-commit").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Keys", "Milk"), committed!!.items.map { it.title })
            assertEquals("Spare set", committed!!.items[0].notes)
        }
        val image = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), "lists-$language-$scale.png")
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
