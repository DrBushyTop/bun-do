package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.SharedProgressScreen
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
class ProgressUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private val task = UUID.randomUUID().toString()
    private val member = UUID.randomUUID().toString()
    private var opened: String? = null
    private fun screen(language: String, scale: Float, activity: Boolean = false, retained: Boolean = true,
        former: Boolean = false, empty: Boolean = false, available: Boolean = true) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        val membership = JSONObject().put("members", JSONArray().put(JSONObject().put("id", member).put("displayName", "Alice").put("active", !former)))
        val tasks = if (retained) mapOf(task to JSONObject().put("id", task).put("title", "Milk")) else emptyMap()
        val snapshot = progressFixture(task, member).apply {
            if (empty) {
                put("activity", JSONArray())
                val stats = getJSONObject("statistics")
                for (key in listOf("weekCount", "monthCount", "lifetimeCount", "streak", "reachedMilestone")) stats.put(key, 0)
                stats.put("nextMilestone", 10)
                for (key in listOf("weekDays", "monthWeeks")) {
                    val buckets = stats.getJSONArray(key)
                    for (index in 0 until buckets.length()) buckets.getJSONObject(index).put("count", 0)
                }
            }
        }
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, androidx.compose.ui.platform.LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") {
                        Box(Modifier.safeDrawingPadding()) {
                            SharedProgressScreen(snapshot.toString().takeIf { available }, activity, tasks, membership, {}, { opened = it })
                        }
                    }
                }
            }
        } }
    }
    @Test fun englishChartShowsSameTotalsAndStaticMilestonesAcrossPeriodChanges() {
        screen("en", 1f)
        compose.onNodeWithText("3 tasks completed").assertExists()
        screenshot("progress-en-week")
        compose.onNodeWithTag("progress-month").performClick()
        compose.onNodeWithText("8 tasks completed").assertExists()
        compose.onNodeWithTag("progress-streak").performScrollTo().assertTextEquals("2 week streak")
        compose.onNodeWithTag("progress-lifetime").performScrollTo().assertTextEquals("25 things done together")
        compose.onNodeWithText("Milestone reached: 25.").performScrollTo().assertExists()
        screenshot("progress-en-month-milestone")
    }
    @Test fun finnishDoubleFontExplainsAcceptanceAndAllCountsHaveText() {
        screen("fi", 2f)
        compose.onNodeWithText("3 tehtävää hoidettu").assertExists()
        compose.onNodeWithTag("progress-streak").performScrollTo().assertTextEquals("2 viikon putki")
        compose.onNodeWithTag("progress-lifetime").performScrollTo().assertTextEquals("25 asiaa hoidettu yhdessä")
        compose.onNodeWithText("Jokainen päätehtävä lasketaan kerran.", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("progress-fi-large")
    }
    @Test fun activityLinksToRetainedTaskAndShowsActor() {
        screen("en", 1f, activity = true)
        compose.onNodeWithText("Alice marked complete").assertExists()
        compose.onNodeWithTag("activity-task-$task").performClick()
        assertEquals(task, opened)
        screenshot("progress-en-activity")
    }
    @Test fun removedMemberAndPurgedTaskHaveReadableFallbacks() {
        screen("fi", 2f, activity = true, retained = false, former = true)
        compose.onNodeWithText("Entinen jäsen", substring = true).assertExists()
        compose.onNodeWithText("Tehtävä ei ole enää saatavilla tällä laitteella.").performScrollTo().assertExists()
        compose.onNodeWithTag("activity-task-$task").assertDoesNotExist()
    }
    @Test fun zeroCompletionsAreNormalAndDoNotShowAnUnearnedMilestone() {
        screen("en", 1f, empty = true)
        compose.onNodeWithText("0 tasks completed").assertExists()
        compose.onNodeWithTag("progress-streak").performScrollTo().assertTextEquals("0 week streak")
        compose.onNodeWithTag("progress-lifetime").performScrollTo().assertTextEquals("0 things done together")
        compose.onNodeWithText("Milestone reached:", substring = true).assertDoesNotExist()
    }
    @Test fun missingSnapshotOffersRefreshRatherThanInventingZeroCounts() {
        screen("en", 1f, available = false)
        compose.onNodeWithText("Sync to see shared activity and completions.").assertExists()
        compose.onNodeWithTag("progress-refresh").assertHasClickAction()
        compose.onNodeWithTag("progress-period-count").assertDoesNotExist()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the rendered buffer, not only the semantics tree.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        finally { bitmap.recycle() }
    }
}
