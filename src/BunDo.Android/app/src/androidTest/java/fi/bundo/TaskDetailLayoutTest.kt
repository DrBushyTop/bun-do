package fi.bundo

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.InboxTask
import fi.bundo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TaskDetailLayoutTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun imageAReadFirstAndOriginalLastEnglish() = detail("en", 1f, "light")
    @Test fun imageAReadFirstAndOriginalLastFinnishLargeDark() = detail("fi", 2f, "dark")

    private fun detail(language: String, scale: Float, appearance: String) {
        val context = compose.activity
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = context.createConfigurationContext(config)
        val title = if (language == "fi") "Järjestä varasto" else "Organize the shed"
        val task = InboxTask("layout", title, if (language == "fi") "Tee tilaa pyörälle ja pidä työkalut käden ulottuvilla." else "Make room for the bike and keep the tools easy to reach.",
            "Private original capture", "Private original notes", 1, 1)
        var completed = false
        compose.runOnUiThread { context.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources, LocalConfiguration provides config,
                LocalDensity provides Density(context.resources.displayMetrics.density, scale)) {
                BunDoTheme(appearance) { Surface { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TaskDetail(task, {}, InboxUiState(loaded = true), {}, Modifier.fillMaxSize(), false,
                        controls = { _, section, _, _ -> when(section) {
                            TaskDetailSection.CONTENT -> Column {
                                Text(translated.getString(R.string.checklist_title), style = MaterialTheme.typography.titleMedium)
                                repeat(8) { index -> Text("${index + 1}. " + if (language == "fi") "Lajittele tavarat ja tarkista, mitä haluat säilyttää." else "Sort this shelf and check what you want to keep.",
                                    Modifier.fillMaxWidth().padding(vertical = androidx.compose.ui.unit.Dp(12f)).testTag("layout-step-$index")) }
                            }
                            TaskDetailSection.PRIMARY -> Button(onClick = { completed = true }, modifier = Modifier.fillMaxWidth().testTag("layout-complete")) { Text(translated.getString(R.string.task_complete)) }
                            else -> Text("Advanced controls")
                        } }, attribution = { Text("Private history") })
                } } }
            }
        } }
        compose.onNodeWithTag("task-artwork").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(220f)).assertIsDisplayed()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Private original capture").assertDoesNotExist()
        compose.onNodeWithText("Advanced controls").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        screenshot("task-detail-$language-$appearance")
        val footer = compose.onNodeWithTag("layout-complete").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("layout-step-7").performScrollTo().assertIsDisplayed()
        assertEquals(footer, compose.onNodeWithTag("layout-complete").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("layout-complete").assertIsDisplayed().performClick()
        assertTrue(completed)
        compose.onNodeWithTag("task-menu").performScrollTo().performClick()
        val original = compose.onNodeWithTag("task-menu-original").getUnclippedBoundsInRoot()
        assertTrue(original.top > compose.onNodeWithTag("task-menu-more").getUnclippedBoundsInRoot().top)
        compose.onNodeWithTag("task-menu-original").performClick()
        compose.onNodeWithText("Private original capture").assertIsDisplayed()
        compose.onNodeWithText("Private history").assertExists()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(200)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(compose.activity.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
