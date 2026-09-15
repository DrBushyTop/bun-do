package fi.bundo

import android.provider.Settings
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.ui.HouseholdMotionProvider
import fi.bundo.ui.LocalHouseholdMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class HouseholdAccessibilityTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun systemMotionChangesOverrideAppPreferenceWhileScreenIsOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = compose.activity.contentResolver
        val previous = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE) ?: "1"
        val enabled = AtomicBoolean(false)
        fun scale(value: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $value")
            ).use { it.readBytes() }
            assertEquals(value.toFloat(), Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE), 0f)
        }
        try {
            scale("1")
            compose.runOnUiThread { compose.activity.setContent {
                HouseholdMotionProvider {
                    val motion = LocalHouseholdMotion.current
                    SideEffect { motion.preferred = true; enabled.set(motion.enabled) }
                    Text(if (motion.enabled) "Motion on" else "Motion off")
                }
            } }
            compose.waitUntil(5000) { enabled.get() }
            scale("0")
            compose.waitUntil(5000) { !enabled.get() }
            compose.onNodeWithText("Motion off").assertIsDisplayed()
            scale("1")
            compose.waitUntil(5000) { enabled.get() }
        } finally { scale(previous) }
    }

    @Test fun settingsReachAccountRecoveryWithoutRoutineExportWarnings() {
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("decorative-motion").assertExists()
        compose.onNodeWithTag("account").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.account_export_warning)).assertDoesNotExist()
        compose.onNodeWithTag("account-recovery-details").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.account_export_warning)).performScrollTo().assertIsDisplayed()
        screenshot("finish-account-recovery.png")
        compose.onNodeWithTag("account-diagnostics").performScrollTo().performClick()
        compose.onNodeWithTag("account-refresh").performScrollTo().assertIsDisplayed()
        screenshot("finish-account-details.png")
    }

    @Test fun offlineSignInFailureIsVisibleWithoutOpeningDiagnostics() {
        val app = compose.activity.application as BunDoApplication
        lateinit var model: fi.bundo.identity.SignInModel
        compose.runOnUiThread {
            val provider = object : fi.bundo.identity.TokenProvider {
                override val issuer = "urn:bun-do:local"
                override val choices = listOf("Alice")
                override suspend fun restore() = false
                override suspend fun signIn(activity: android.app.Activity, choice: String?): String =
                    throw java.io.IOException("Synthetic offline sign-in")
                override suspend fun refresh(): String = error("Not signed in")
                override suspend fun signOut() = Unit
            }
            model = fi.bundo.identity.SignInModel(app, provider, { error("No token while offline") })
            compose.activity.setContent {
                fi.bundo.ui.BunDoTheme("light") { fi.bundo.ui.AccountScreen(app.accounts, model, onClose = {}) }
            }
            model.signIn(compose.activity, "Alice")
        }
        compose.onNodeWithText(compose.activity.getString(R.string.identity_api_unavailable))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account-refresh").assertDoesNotExist()
    }

    @Test fun nativeDatePickerStaysLightEvenWhenNightModeIsRequested() {
        val previous = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        var dialog: android.app.DatePickerDialog? = null
        try {
            compose.runOnUiThread { androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) }
            compose.activityRule.scenario.recreate()
            assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO,
                compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            compose.runOnUiThread {
                dialog = android.app.DatePickerDialog(compose.activity, null, 2026, 8, 15).also { it.show() }
                val light = android.util.TypedValue()
                assertTrue(dialog!!.context.theme.resolveAttribute(android.R.attr.isLightTheme, light, true))
                assertTrue(light.data != 0)
            }
            screenshot("finish-native-date-light.png")
        } finally {
            compose.runOnUiThread {
                dialog?.dismiss()
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(previous)
            }
        }
    }

    @Test fun englishJoinFormKeepsTypedInputAfterInvalidLink() = joinForm("en", 1.3f)
    @Test fun finnishJoinFormSupportsLargeTextAndBack() = joinForm("fi", 2f)

    private fun joinForm(language: String, scale: Float) {
        val activity = compose.activity
        val data = (activity.application as BunDoApplication).accounts.active.value!!
        val model = androidx.lifecycle.ViewModelProvider(activity)[fi.bundo.identity.SignInModel::class.java]
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(language)); fontScale = scale
        }
        val translated = activity.createConfigurationContext(configuration)
        var closed = false
        compose.runOnUiThread { activity.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides translated,
                androidx.compose.ui.platform.LocalResources provides translated.resources,
                androidx.compose.ui.platform.LocalConfiguration provides configuration,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(activity.resources.displayMetrics.density, scale),
            ) { fi.bundo.ui.BunDoTheme("light") { fi.bundo.ui.HouseholdScreen(data, model, null, {}, { closed = true }) } }
        } }
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("household-join") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("household-join").performScrollTo().performClick()
        compose.onNodeWithTag("household-your-name").performScrollTo().performTextInput("Aino")
        compose.onNodeWithTag("household-link").performScrollTo().performTextInput("not-an-invitation")
        compose.onNodeWithTag("household-join-confirm").performScrollTo().performClick()
        compose.onNodeWithText(translated.getString(R.string.household_invalid_link)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("household-link").performScrollTo().assertTextContains("not-an-invitation")
        screenshot("finish-join-$language.png")
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(closed)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350) // Wait for the rendered buffer, not only the semantics tree.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
