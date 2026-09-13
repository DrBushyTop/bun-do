package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.household.HouseholdEndpoint
import fi.bundo.identity.IdentityEndpoint
import fi.bundo.identity.createTokenProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit live adapter check. Ordinary emulator tests never contact a cloud household. */
@RunWith(AndroidJUnit4::class)
class CloudHouseholdDeviceTest {
    @Test fun cachedMicrosoftAccountCanCreateInviteCancelAndDeleteThroughCosmos() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("householdCloudSmoke") == "true")
        assumeTrue(BuildConfig.IDENTITY_API_BASE.startsWith("https://"))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BunDoApplication
        val provider = createTokenProvider(application)
        check(provider.restore()) { "This test needs an existing Microsoft sign-in." }
        val token = provider.refresh()
        val identityApi = IdentityEndpoint(provider.issuer)
        val identity = identityApi.verify(token)
        val installation = application.accounts.registrationInstallation(identity)
        val registration = identityApi.register(token, installation)
        val endpoint = HouseholdEndpoint()
        val id = UUID.randomUUID().toString()
        suspend fun call(action: String, body: JSONObject = JSONObject()) = endpoint.send(token, registration,
            body.put("action", action).put("workspaceId", id))
        var epoch: String? = null
        try {
            val home = call("create", JSONObject().put("name", "Synthetic cloud household check")
                .put("displayName", "Test owner")).getJSONObject("household")
            epoch = home.getString("stateEpoch")
            val invitation = call("invite", JSONObject().put("stateEpoch", epoch))
            assertTrue(invitation.getString("invitationLink").startsWith("https://"))
            val record = invitation.getJSONObject("household").getJSONArray("invitations").getJSONObject(0)
            val cancelled = call("cancel", JSONObject().put("stateEpoch", epoch)
                .put("invitationId", record.getString("id")).put("expectedVersion", record.getString("version")))
            assertEquals("Cancelled", cancelled.getJSONObject("household").getJSONArray("invitations").getJSONObject(0).getString("phase"))
            val retry = call("get").getJSONObject("household")
            assertEquals(id, retry.getString("id"))
        } finally {
            if (epoch != null) {
                val current = call("get").getJSONObject("household")
                call("delete", JSONObject().put("stateEpoch", epoch).put("expectedVersion", current.getString("membershipVersion")))
                assertFalse(call("get").getJSONObject("household").getBoolean("active"))
            }
        }
    }
}
