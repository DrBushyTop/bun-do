package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.ui.*
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
class RepeatUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)
    private var saved: String? = null
    private var stopped = false
    private var observed: String? = null
    private val shownTask = mutableStateOf(JSONObject())
    private fun screen(language: String, scale: Float, active: Boolean = false) {
        val task = JSONObject().put("id", UUID.randomUUID().toString()).put("title", "Milk").put("lifecycle", "OPEN")
        if (active) task.put("repeat", JSONObject().put("active", true).put("version", "2").put("title", "Milk")
            .put("rule", JSONObject().put("frequency", "DAILY").put("zoneId", "Europe/Helsinki")))
        shownTask.value = task
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = compose.activity.createConfigurationContext(config)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalContext provides translated, androidx.compose.ui.platform.LocalResources provides translated.resources, LocalConfiguration provides config) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    BunDoTheme("light") { Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp)) {
                        SharedRepeatControls(shownTask.value, JSONObject().put("timeZoneId", "Europe/Helsinki"), true) { displayed, blueprint ->
                            observed = displayed
                            if (blueprint == null) stopped = true else saved = blueprint
                        }
                    } }
                }
            }
        } }
    }
    @Test fun englishWeeklyBlueprintAndStopUseExplicitActions() {
        screen("en", 1f, true)
        compose.onNodeWithTag("repeat-edit").performClick()
        compose.onNodeWithTag("repeat-weekly").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-day-5").performScrollTo().performClick()
        compose.onNodeWithTag("repeat-title").performScrollTo().performTextReplacement("Friday milk")
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        android.os.SystemClock.sleep(350)
        compose.onNodeWithTag("repeat-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        val value = JSONObject(saved!!)
        assertEquals("WEEKLY", value.getString("frequency")); assertEquals(5, value.getInt("weekday"))
        assertEquals("Friday milk", value.getString("title"))
        screenshot("repeat-en")
        compose.onNodeWithTag("repeat-stop").performScrollTo().performClick()
        compose.waitUntil(5_000) { stopped }
        assertTrue(stopped)
    }
    @Test fun finnishDoubleFontDailySetupIsScrollableAndLabelled() {
        screen("fi", 2f)
        compose.onNodeWithText("Aseta toisto").performClick()
        compose.onNodeWithTag("repeat-title").performScrollTo().assertIsDisplayed()
        screenshot("repeat-fi-large")
        compose.onNodeWithText("Tallenna toisto").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null }
        assertEquals("DAILY", JSONObject(saved!!).getString("frequency"))
        assertTrue(JSONObject(saved!!).isNull("weekday"))
    }
    @Test fun remoteScheduleChangeDoesNotRebaseAnOpenForm() {
        screen("en", 1f, true)
        compose.onNodeWithTag("repeat-edit").performClick()
        compose.onNodeWithTag("repeat-title").performScrollTo().performTextReplacement("My draft")
        compose.runOnIdle {
            shownTask.value = JSONObject(shownTask.value.toString()).apply {
                getJSONObject("repeat").put("version", "9").put("title", "Remote title").put("active", false)
            }
        }
        compose.onNodeWithTag("repeat-save").performScrollTo().performClick()
        compose.waitUntil(5_000) { saved != null && observed != null }
        assertEquals("2", JSONObject(observed!!).getJSONObject("repeat").getString("version"))
        assertEquals("My draft", JSONObject(saved!!).getString("title"))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the rendered buffer, not only the semantics tree.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }
}
