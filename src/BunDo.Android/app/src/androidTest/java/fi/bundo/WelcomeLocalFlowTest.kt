package fi.bundo

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.household.*
import fi.bundo.identity.*
import fi.bundo.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Opt-in real local HTTP/identity/household walkthrough. Never uses a cloud account. */
@RunWith(AndroidJUnit4::class)
class WelcomeLocalFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(IsolatedUiAccountRule()).around(compose)

    private fun waitEnabled(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun createInviteJoinApproveAndEnterWithoutMovingLocalText() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("welcomeLocalFlow") == "true")
        assumeTrue(BuildConfig.BUILD_TYPE == "local")
        val app = compose.activity.application as BunDoApplication
        val names = listOf("welcome-local-owner-test", "welcome-local-joiner-test")
        val stores = names.map { AccountStore(app, it) }
        val welcomes = names.map { WelcomeStore(app, "$it-setup", existingInstall = false) }
        val models = mutableListOf<SignInModel>()
        compose.runOnUiThread {
            stores.forEach { accounts ->
                val provider = createTokenProvider(app)
                models += SignInModel(app, provider, IdentityEndpoint(provider.issuer)::verify, accounts)
            }
        }
        var current by mutableIntStateOf(0)
        var homeId: String? = null
        suspend fun ownerCall(action: String, extra: JSONObject = JSONObject()): JSONObject = models[0].withAccountToken(stores[0].active.value!!) { token, registration ->
            HouseholdEndpoint().send(token, registration, extra.put("action", action).put("workspaceId", homeId))
        }
        try {
            stores[0].active.value!!.inbox.commit(EditorDraft("new", "Private local note"))
            compose.runOnUiThread { compose.activity.setContent { key(current) {
                val account by stores[current].active.collectAsState()
                val progress by welcomes[current].state.collectAsState()
                val model = models[current]
                LaunchedEffect(account?.identity, model.busy) { if (!model.busy && account != null) welcomes[current].bind(account?.identity) }
                BunDoTheme("light") {
                    if (progress.visible) WelcomeRoute(welcomes[current], account, model, {})
                    else Text("Setup complete")
                }
            } } }
            compose.waitUntil(10_000) { models.none { it.busy } }
            compose.onNodeWithTag("welcome-family").performScrollTo().performClick()
            compose.onNodeWithTag("welcome-create").performScrollTo().performClick()
            compose.onNodeWithTag("welcome-sign-in-alice").performScrollTo().performClick()
            compose.waitUntil(30_000) { !models[0].busy }
            assertEquals("Owner sign-in: ${models[0].errorCode}", "alice", stores[0].active.value?.identity?.subject)
            waitEnabled("welcome-name")
            compose.onNodeWithTag("welcome-name").performScrollTo().performTextReplacement("Synthetic welcome family")
            compose.onNodeWithTag("welcome-your-name").performScrollTo().performTextInput("Alice")
            waitEnabled("welcome-submit")
            compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(30_000) { welcomes[0].state.value.step == WelcomeStep.HOME }
            homeId = welcomes[0].state.value.homeId
            assertTrue(stores[0].recovery(stores[0].active.value!!).isEmpty())
            assertEquals("Private local note", stores[0].anonymousPreview(stores[0].active.value!!).single().title)
            waitEnabled("welcome-invite")
            compose.onNodeWithTag("welcome-invite").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(app.getString(R.string.household_share)).fetchSemanticsNodes().isNotEmpty() }
            val ownerHome = ownerCall("get").getJSONObject("household")
            val issued = ownerCall("invite", JSONObject().put("stateEpoch", ownerHome.getString("stateEpoch")))
            var link = issued.getString("invitationLink")
            var inviteId = InvitationLink.parse(link)!!.invitation
            welcomes[1].incoming(link)
            compose.runOnUiThread { current = 1 }
            compose.onNodeWithTag("welcome-sign-in-bob").performScrollTo().performClick()
            compose.waitUntil(30_000) { stores[1].active.value?.identity?.subject == "bob" && !models[1].busy }
            waitEnabled("welcome-link")
            compose.onNodeWithTag("welcome-link").performScrollTo().assertTextContains(link)
            compose.onNodeWithTag("welcome-your-name").performScrollTo().performTextInput("Bob")
            waitEnabled("welcome-submit")
            compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsEnabled().performClick()
            try {
                compose.waitUntil(30_000) { welcomes[1].state.value.step == WelcomeStep.HOME ||
                    compose.onAllNodesWithTag("welcome-error").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                val stalled = compose.activity.viewModelStore["welcome-households-${stores[1].active.value!!.lease.generation}"] as HouseholdModel
                throw AssertionError("Join stalled: step=${welcomes[1].state.value.step}, busy=${stalled.busy}, failure=${stalled.failure}, hasHome=${stalled.current != null}, validLink=${InvitationLink.parse(welcomes[1].state.value.link) != null}, hasName=${welcomes[1].state.value.displayName.isNotBlank()}", error)
            }
            val joinModel = compose.activity.viewModelStore["welcome-households-${stores[1].active.value!!.lease.generation}"] as HouseholdModel
            assertEquals("Join outcome: ${joinModel.failure}", WelcomeStep.HOME, welcomes[1].state.value.step)
            compose.onNodeWithTag("welcome-enter").assertDoesNotExist()
            compose.onNodeWithTag("welcome-code").performScrollTo().assertExists()
            assertNull(stores[1].active.value!!.selectedHousehold.value)
            // A cancelled candidate remains in the family listing. A fresh invite must still be redeemable.
            val oldInvitation = ownerCall("get").getJSONObject("household").getJSONArray("invitations").let { rows ->
                (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == inviteId }
            }
            ownerCall("cancel", JSONObject().put("stateEpoch", ownerHome.getString("stateEpoch"))
                .put("invitationId", inviteId).put("expectedVersion", oldInvitation.getString("version")))
            compose.onNodeWithTag("welcome-refresh").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("welcome-code").fetchSemanticsNodes().isEmpty() }
            link = ownerCall("invite", JSONObject().put("stateEpoch", ownerHome.getString("stateEpoch"))).getString("invitationLink")
            inviteId = InvitationLink.parse(link)!!.invitation
            welcomes[1].incoming(link)
            waitEnabled("welcome-link")
            compose.onNodeWithTag("welcome-link").assertExists()
            waitEnabled("welcome-submit")
            compose.onNodeWithTag("welcome-submit").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(30_000) { welcomes[1].state.value.step == WelcomeStep.HOME }
            compose.onNodeWithTag("welcome-code").performScrollTo().assertExists()
            val code = ownerCall("get").getJSONObject("household").getJSONArray("invitations").let { rows ->
                (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == inviteId }.getString("confirmationCode")
            }
            compose.onNodeWithTag("welcome-later").performScrollTo().performClick()
            assertFalse(welcomes[1].state.value.visible)
            welcomes[1].reopen()
            compose.onNodeWithTag("welcome-code").performScrollTo().assertExists()
            compose.runOnUiThread { current = 0 }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("welcome-confirm-$inviteId").fetchSemanticsNodes().isNotEmpty() }
            waitEnabled("welcome-confirm-$inviteId")
            compose.onNodeWithTag("welcome-confirm-$inviteId").performScrollTo().performTextInput(code)
            compose.onNodeWithTag("welcome-approve-$inviteId").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("welcome-confirm-$inviteId").fetchSemanticsNodes().isEmpty() }
            compose.runOnUiThread { current = 1 }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("welcome-enter").fetchSemanticsNodes().isNotEmpty() }
            waitEnabled("welcome-enter")
            compose.onNodeWithTag("welcome-enter").performScrollTo().performClick()
            compose.waitUntil(30_000) { !welcomes[1].state.value.visible }
            assertEquals(homeId, stores[1].active.value!!.selectedHousehold.value)
            // Journey starts through the production Android transport and fenced cache.
            val joined = stores[1].active.value!!
            val workspace = joined.database.shared().workspaces(joined.registrationId!!).single { it.workspaceId == homeId }
            val repository = SharedRepository(joined.database, joined.lease, workspace.scope, workspace.registration)
            models[1].withAccountToken(joined) { token, _ -> SharedJourney.refresh(app, repository, token, enable = true) }
            val started = JourneyProgress.read(JSONObject(joined.database.shared().workspace(workspace.scope)!!.progress!!))!!
            assertEquals(0, started.credits)
            models[1].withAccountToken(joined) { token, _ -> SharedJourney.refresh(app, repository, token, enable = true) }
            models[1].withAccountToken(joined) { token, _ -> SharedJourney.refresh(app, repository, token, enable = false) }
            val ownerJourney = models[0].withAccountToken(stores[0].active.value!!) { token, registration ->
                JourneyProgress.read(JourneyEndpoint().send(token, workspace.copy(registration = registration), enable = false))!!
            }
            assertEquals(started, ownerJourney)
        } finally {
            compose.runOnUiThread { compose.activity.setContent { Text("Local walkthrough finished") } }
            if (homeId != null) {
                val home = ownerCall("get").getJSONObject("household")
                ownerCall("delete", JSONObject().put("stateEpoch", home.getString("stateEpoch")).put("expectedVersion", home.getString("membershipVersion")))
            }
            for (i in stores.indices) {
                welcomes[i].awaitSaved(); welcomes[i].close(); stores[i].signOut(delete = true); stores[i].close()
                // Keep only this fixture installation proof so repeated runs reuse server registration slots.
                File(app.noBackupFilesDir, "${names[i]}-setup").deleteRecursively()
                app.deleteDatabase("${names[i]}-anonymous.db")
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    listOf("bundo.${names[i]}.active", "bundo.${names[i]}-setup").forEach(::deleteEntry)
                }
            }
        }
    }
}
