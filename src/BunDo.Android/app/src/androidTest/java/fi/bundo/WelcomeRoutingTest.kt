package fi.bundo

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.bundo.data.WelcomeStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WelcomeRoutingTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    @Test fun settingsOpensFamilySetupAndDismissalSurvivesRecreation() = runBlocking {
        val app = compose.activity.application as BunDoApplication
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("account").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-settings").performScrollTo().performClick()
        compose.onNodeWithTag("welcome-create").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("welcome-later").performScrollTo().performClick()
        app.welcome.awaitSaved()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("welcome-title").assertDoesNotExist()
        // Dismissing setup returns to the settings page that opened it.
        compose.onNodeWithTag("settings-language").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings").assertExists()
        Unit
    }

    @Test fun incomingInvitationLeavesIntentAndSurvivesRecreationWithoutAccountMenu() = runBlocking {
        val app = compose.activity.application as BunDoApplication
        val link = "bundo://join#${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/${"A".repeat(64)}"
        // A link also takes over when the user was looking at account settings.
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("account").performClick()
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { app.welcome.state.value.link == link }
        app.welcome.awaitSaved()
        compose.onNodeWithTag("welcome-title").assertExists()
        assertEquals(WelcomeStep.JOIN, app.welcome.state.value.step)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("welcome-title").assertExists()
        assertNull(compose.activity.intent.data)
        assertEquals(link, app.welcome.state.value.link)
        assertEquals(WelcomeStep.JOIN, app.welcome.state.value.step)
    }
}
