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
