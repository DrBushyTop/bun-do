package fi.bundo

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.household.*
import fi.bundo.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class WelcomeUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun welcomeAndFamilyFlowEnglish() = flow("en", 1f)
    @Test fun welcomeAndFamilyFlowFinnishLargeText() = flow("fi", 2f)

    @Test fun cancelledSignInKeepsChosenJoinPathAndInvitation() = runBlocking {
        val app = compose.activity.application as BunDoApplication
        val name = "welcome-cancel-${UUID.randomUUID()}"
        val store = WelcomeStore(app, name, existingInstall = false)
        val link = "bundo://join#${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/${"A".repeat(64)}"
        store.incoming(link)
        lateinit var model: fi.bundo.identity.SignInModel
        try {
            compose.runOnUiThread {
                val provider = object : fi.bundo.identity.TokenProvider {
                    override val issuer = "urn:bun-do:local"
                    override val choices = emptyList<String>()
                    override suspend fun restore() = false
                    override suspend fun signIn(activity: android.app.Activity, choice: String?): String = throw fi.bundo.identity.SignInCancelled()
                    override suspend fun refresh(): String = error("No account")
                    override suspend fun signOut() = Unit
                }
                model = fi.bundo.identity.SignInModel(app, provider, { error("No token on cancellation") })
                compose.activity.setContent { BunDoTheme("light") { WelcomeRoute(store, null, model, {}) } }
            }
            compose.waitUntil(5000) { !model.busy }
            compose.onNodeWithTag("welcome-sign-in-").performScrollTo().performClick()
            compose.onNodeWithText(app.getString(R.string.identity_cancelled)).performScrollTo().assertIsDisplayed()
            assertEquals(WelcomeStep.JOIN, store.state.value.step)
            assertEquals(link, store.state.value.link)
            compose.onNodeWithTag("welcome-later").performScrollTo().performClick()
            store.awaitSaved()
            assertFalse(store.state.value.visible)
        } finally {
            store.awaitSaved(); store.close()
            File(app.noBackupFilesDir, name).deleteRecursively()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("bundo.$name") }
        }
    }

    private fun flow(language: String, scale: Float) {
        val context = compose.activity
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val translated = context.createConfigurationContext(config)
        var progress by mutableStateOf(WelcomeProgress())
        var signedIn by mutableStateOf(false)
        var home by mutableStateOf<Household?>(null)
        var message by mutableStateOf<Int?>(null)
        var entered = false
        var submitted = false
        var dismissed = false
        val invitation = "bundo://join#${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/${"A".repeat(64)}"
        val pending = HouseholdInvitation("invite", "v1", "Pending", "2026-09-17T12:00:00Z", "123456", "Alex")
        val fixture = Household("home", "epoch", "v1", "me", false, false, "Our home", false, emptyList(), listOf(pending))
        val actions = WelcomeActions(
            back = { progress = progress.copy(step = WelcomeStep.FAMILY) }, dismiss = { dismissed = true },
            family = { progress = progress.copy(step = WelcomeStep.FAMILY) }, create = { progress = progress.copy(step = WelcomeStep.CREATE) },
            join = { progress = progress.copy(step = WelcomeStep.JOIN) }, signIn = { signedIn = true }, accountHelp = {},
            edit = { progress = it }, submit = { submitted = true }, open = {}, refresh = {}, enter = { entered = true },
            invite = {}, share = {}, approve = { _, code -> assertEquals("123456", code) })
        compose.runOnUiThread { context.setContent {
            CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources,
                LocalConfiguration provides config, LocalDensity provides Density(context.resources.displayMetrics.density, scale)) {
                BunDoTheme("light") { WelcomeScreen(progress, signedIn, signedIn, emptyList(), home, false, message, false, actions) }
            }
        } }
        capture("start-$language")
        compose.onNodeWithTag("welcome-local").performScrollTo().performClick()
        assertTrue(dismissed)
        compose.onNodeWithTag("welcome-family").performScrollTo().performClick()
        capture("family-$language")
        compose.onNodeWithTag("welcome-join").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("welcome-continue").assertExists()
        compose.onNodeWithTag("welcome-sign-in-").assertDoesNotExist()
        compose.onNodeWithTag("welcome-create").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("welcome-continue").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-name").assertDoesNotExist()
        capture("sign-in-$language")
        compose.onNodeWithTag("welcome-sign-in-").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("welcome-name").performScrollTo().performTextInput("Our home")
        compose.onNodeWithTag("welcome-your-name").performScrollTo().performTextInput("Alex")
        capture("create-$language")
        compose.onNodeWithTag("welcome-submit").performScrollTo().performClick()
        assertTrue(submitted)
        compose.runOnUiThread { progress = progress.copy(step = WelcomeStep.JOIN, link = "not a link") }
        compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("welcome-link").performScrollTo().performTextReplacement(invitation)
        compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsEnabled()
        compose.runOnUiThread { progress = progress.copy(step = WelcomeStep.HOME); home = fixture }
        capture("waiting-$language")
        compose.onNodeWithTag("welcome-code").performScrollTo().assertTextEquals("123456")
        compose.onNodeWithTag("welcome-enter").assertDoesNotExist()
        compose.onNodeWithTag("welcome-later").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { home = fixture.copy(invitations = listOf(pending.copy(phase = "Expired"))) }
        compose.onNodeWithTag("welcome-code").assertDoesNotExist()
        compose.onNodeWithTag("welcome-enter").assertDoesNotExist()
        compose.onNodeWithText(translated.getString(R.string.welcome_new_invitation)).performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { home = fixture.copy(active = true, owner = true) }
        capture("ready-$language")
        compose.onNodeWithTag("welcome-confirm-invite").performScrollTo().performTextInput("123456")
        compose.onNodeWithTag("welcome-approve-invite").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-enter").performScrollTo().performClick()
        assertTrue(entered)
        compose.runOnUiThread { message = R.string.household_unavailable }
        compose.onNodeWithTag("welcome-error").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        // One batched visual pass across all onboarding states and both supported locales.
        compose.onNodeWithTag("welcome-title").performScrollTo()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(compose.activity.getExternalFilesDir(null), "welcome-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
