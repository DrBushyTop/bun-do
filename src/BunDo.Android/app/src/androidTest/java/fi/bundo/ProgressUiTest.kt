package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
    private var refreshed = false
    private fun screen(language: String, scale: Float, activity: Boolean = false, retained: Boolean = true,
        former: Boolean = false, empty: Boolean = false, available: Boolean = true, many: Boolean = false,
        theme: String = "light") {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        val membership = JSONObject().put("members", JSONArray().put(JSONObject().put("id", member).put("displayName", "Alice").put("active", !former)))
        val titles = if (language == "fi") listOf("Osta sokerittomia limuja", "Osta paikallisia vihanneksia",
            "Osta vanulappuja", "Napolin kauppareissu", "Siivoa keittiö", "Kastele kasvit",
            "Tämä on pitkä tehtävän nimi, jonka kaikki tiedot saa esiin kuvakkeesta")
        else listOf("Pick up sugar-free drinks", "Buy local vegetables", "Cotton pads", "Shopping in Naples",
            "Tidy the kitchen", "Water the plants", "A long task title whose full details are available from the event icon")
        val tasks = if (!retained) emptyMap() else if (many) titles.mapIndexed { i, title ->
            "$task-$i" to JSONObject().put("id", "$task-$i").put("title", title)
        }.toMap() else mapOf(task to JSONObject().put("id", task).put("title", "Milk"))
        val snapshot = progressFixture(task, member).apply {
            if (many) put("asOf", "2026-09-15T21:14:00Z").put("activity", JSONArray(titles.mapIndexed { i, _ ->
                JSONObject().put("revision", "${20 - i}").put("taskId", "$task-$i").put("actorId", member)
                    .put("action", listOf("CompleteTask", "CompleteTask", "CompleteTask", "AddChildren",
                        "ClaimTask", "SetSnooze", "CreateTask")[i])
                    .put("acceptedAt", if (i < 3) "2026-09-15T21:13:00Z" else "2026-09-15T20:22:00Z")
            }))
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
                    BunDoTheme(theme) {
                        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                            SharedProgressScreen(snapshot.toString().takeIf { available }, activity, tasks, membership,
                                { refreshed = true }, { opened = it })
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
        compose.onNodeWithContentDescription("Alice marked complete", substring = true).assertExists()
        compose.onNodeWithTag("activity-task-$task").performClick()
        assertEquals(task, opened)
        screenshot("progress-en-activity")
    }
    @Test fun removedMemberAndPurgedTaskHaveReadableFallbacks() {
        screen("fi", 2f, activity = true, retained = false, former = true)
        compose.onNodeWithText("Entinen jäsen", substring = true).assertExists()
        compose.onNodeWithText("Tehtävä ei ole enää saatavilla tällä laitteella.").performScrollTo().assertExists()
        compose.onNodeWithTag("activity-task-$task").assertDoesNotExist()
        compose.onNodeWithTag("activity-details-$task").performClick()
        compose.onNodeWithTag("activity-expanded-$task").performScrollTo().assertIsDisplayed()
        screenshot("activity-fi-large-unavailable")
    }
    @Test fun compactFinnishEventsShareDateHeadingsAndKeepFullDetails() {
        screen("fi", 1f, activity = true, many = true)
        for (i in 0..6) {
            compose.onNodeWithTag("activity-row-$task-$i").assertIsDisplayed()
                .assertHeightIsEqualTo(48.dp)
            val icon = compose.onNodeWithTag("activity-details-$task-$i").fetchSemanticsNode().boundsInRoot
            val link = compose.onNodeWithTag("activity-task-$task-$i").fetchSemanticsNode().boundsInRoot
            assertEquals(8f * compose.activity.resources.displayMetrics.density, link.left - icon.right, 1f)
        }
        compose.onAllNodesWithTag("activity-date-2026-09-16").assertCountEquals(1)
        compose.onAllNodesWithTag("activity-date-2026-09-15").assertCountEquals(1)
        screenshot("activity-fi-compact")
        compose.onNodeWithTag("activity-details-$task-6").performClick()
        compose.onNodeWithTag("activity-expanded-$task-6").performScrollTo()
            .assertTextContains("Tämä on pitkä tehtävän nimi", substring = true)
        compose.onNodeWithTag("activity-details-$task-6").performClick()
        compose.onNodeWithTag("activity-expanded-$task-6").assertDoesNotExist()
        compose.onNodeWithTag("activity-task-$task-6").performClick()
        assertEquals("$task-6", opened)
    }
    @Test fun activityLargeTextAndDarkThemeKeepEventsNavigable() {
        screen("fi", 2f, activity = true, many = true, theme = "dark")
        compose.onNodeWithTag("progress-refresh").performClick()
        assertTrue(refreshed)
        screenshot("activity-fi-large-dark")
        compose.onNodeWithTag("activity-details-$task-6").performScrollTo().performClick()
        compose.onNodeWithTag("activity-expanded-$task-6").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("activity-task-$task-6").performScrollTo().performClick()
        assertEquals("$task-6", opened)
    }
    @Test fun emptyActivityHasRefreshAndNoFabricatedEvents() {
        screen("en", 1f, activity = true, empty = true)
        compose.onNodeWithTag("progress-refresh").performClick()
        assertTrue(refreshed)
        compose.onNodeWithTag("activity-row-$task").assertDoesNotExist()
        screenshot("activity-en-empty")
    }
    @Test fun missingActivitySnapshotCanRefresh() {
        screen("en", 1f, activity = true, available = false)
        compose.onNodeWithText("Sync to see shared activity and completions.").assertIsDisplayed()
        compose.onNodeWithTag("progress-refresh").performClick()
        assertTrue(refreshed)
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
