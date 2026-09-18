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
            SharedListsScreen(repository, data, state, emptyMap(), true, {}, {})
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
