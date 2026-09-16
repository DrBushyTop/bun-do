package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.ui.BunDoTheme
import fi.bundo.ui.SharedJourneyScreen
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class JourneyUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private var starts = 0
    private var refreshes = 0
    private var backs = 0
    private fun screen(language: String = "en", scale: Float = 1f, snapshot: JSONObject? = journeyFixture(),
        busy: Boolean = false, failed: Boolean = false, allowed: Boolean = true, dark: Boolean = false, short: Boolean = false) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, androidx.compose.ui.platform.LocalResources provides translated.resources,
                LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme(if (dark) "dark" else "light") { Surface { Box(Modifier.safeDrawingPadding().then(if (short) Modifier.height(360.dp) else Modifier)) {
                        SharedJourneyScreen(snapshot?.toString(), busy, failed, allowed, { starts++ }, { refreshes++ }, { backs++ })
                    } } }
                }
            }
        } }
    }
    @Test fun startIsExplicitAndExplainsFamilyBaseline() {
        screen(snapshot = journeyFixture().put("journey", JSONObject.NULL))
        assertEquals(0, starts)
        compose.onNodeWithTag("journey-start").performScrollTo().performClick()
        assertEquals(1, starts)
        compose.onNodeWithText("Starts online for the whole family.", substring = true).performScrollTo().assertIsDisplayed()
        screenshot("journey-en-start")
    }
    @Test fun cachedProgressAndRetryStayVisibleAfterConnectionFailure() {
        screen(failed = true)
        compose.onNodeWithTag("journey-progress").performScrollTo().assertTextEquals("First completions at this stop: 2 / 5")
        screenshot("journey-en-progress")
        compose.onNodeWithText("Here now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("journey-refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("journey-start").assertDoesNotExist()
    }
    @Test fun finnishDoubleTextKeepsRouteHistoryAndRefreshReachable() {
        screen("fi", 2f, journeyFixture(25))
        compose.onNodeWithTag("household-world").assertDoesNotExist()
        compose.onNodeWithTag("journey-resting").performScrollTo().assertIsDisplayed()
        screenshot("journey-fi-large-rest")
        compose.onNodeWithTag("journey-history-dojo-garden").performScrollTo().assertTextEquals("Dojolta puutarhaan")
        screenshot("journey-fi-large-history")
        compose.onNodeWithTag("journey-refresh").performScrollTo().performClick()
        assertEquals(1, refreshes)
    }
    @Test fun unavailableAndBusyStatesDoNotInventProgressOrOfferInvalidStart() {
        screen(snapshot = null, allowed = false)
        compose.onNodeWithTag("journey-progress").assertDoesNotExist()
        compose.onNodeWithTag("journey-start").assertDoesNotExist()
        compose.onNodeWithTag("journey-refresh").performScrollTo().assertIsNotEnabled()
        screen(snapshot = journeyFixture().put("journey", JSONObject.NULL), busy = true)
        compose.onNodeWithTag("journey-start").performScrollTo().assertIsNotEnabled()
    }
    @Test fun futureRouteHasReadableFallbackAndDarkMode() {
        screen(snapshot = journeyFixture().apply { getJSONObject("journey").put("routeId", "future-route").put("locationId", "future-stop") }, dark = true)
        compose.onNodeWithText("Another path").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop 2").performScrollTo().assertIsDisplayed()
        screenshot("journey-en-dark-future")
    }
    @Test fun shortContentAreaStillScrollsToStartAndBack() {
        screen(snapshot = journeyFixture().put("journey", JSONObject.NULL), short = true)
        compose.onNodeWithTag("journey-start").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, starts)
        compose.onNodeWithText("Back to Together").performScrollTo().performClick()
        assertEquals(1, backs)
    }
    @Test fun hiddenWorldDoesNotHideJourneyOrResetItsProgress() {
        val preferences = compose.activity.getSharedPreferences("appearance", 0)
        val old = preferences.getBoolean("world", true)
        try {
            preferences.edit().putBoolean("world", false).commit()
            screen()
            compose.onNodeWithTag("household-world").assertDoesNotExist()
            compose.onNodeWithTag("journey-progress").performScrollTo().assertIsDisplayed()
        } finally { preferences.edit().putBoolean("world", old).commit() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try { File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
