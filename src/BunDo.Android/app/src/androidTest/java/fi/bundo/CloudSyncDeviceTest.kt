package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.household.HouseholdEndpoint
import fi.bundo.identity.IdentityEndpoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicit opt-in. Uses cached Microsoft sign-in, synthetic text and two temporary registrations only. */
@RunWith(AndroidJUnit4::class)
class CloudSyncDeviceTest {
    @Test fun realCosmosSyncPreservesRetriesConvergesAndDeniesDeletedHousehold() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("syncCloudSmoke") == "true")
        assumeTrue(BuildConfig.IDENTITY_API_BASE.startsWith("https://"))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BunDoApplication
        val tokens = application.tokens
        check(tokens.restore())
        val token = tokens.refresh()
        val identityApi = IdentityEndpoint(tokens.issuer)
        val identity = identityApi.verify(token)
        val installation = application.accounts.registrationInstallation(identity)
        val ownerRegistration = identityApi.register(token, installation)
        val registrations = mutableListOf<String>()
        val databases = mutableListOf<Pair<String, InboxDatabase>>()
        val workspace = UUID.randomUUID().toString()
        var epoch: String? = null
        val households = HouseholdEndpoint()
        suspend fun home(action: String, body: JSONObject = JSONObject()) = households.send(token, ownerRegistration,
            body.put("action", action).put("workspaceId", workspace))
        try {
            epoch = home("create", JSONObject().put("name", "Synthetic sync device check")
                .put("displayName", "Test owner")).getJSONObject("household").getString("stateEpoch")
            for (index in 0..1) registrations += identityApi.register(token, UUID.randomUUID().toString())
            val repositories = registrations.map { registration ->
                val name = "cloud-sync-${UUID.randomUUID()}.db"
                val db = InboxDatabase.open(application, name)
                databases += name to db
                val key = "$workspace/$epoch/$registration"
                db.shared().saveWorkspace(SharedWorkspace(key, workspace, epoch, registration, "Cloud fixture"))
                SharedRepository(db, DataLease(), key, registration)
            }
            for ((index, repository) in repositories.withIndex()) {
                val id = repository.commit(EditorDraft("new", "Synthetic capture $index", ""))
                repository.commit(EditorDraft(id, "Synthetic edited $index", ""))
            }
            val lost = checkNotNull(repositories[0].prepare(1000, 1))
            val original = SharedEndpoint().send(token, lost)
            assertEquals("ACCEPTED", original.getString("code"))
            repositories[0].release(lost) // Simulate the receipt never reaching durable local storage.
            suspend fun sync(repository: SharedRepository) {
                for (attempt in 0 until 40) {
                    val request = checkNotNull(repository.prepare(android.os.SystemClock.elapsedRealtime(), 1))
                    val reply = SharedEndpoint().send(token, request)
                    check(reply.getString("code") in listOf("ACCEPTED", "BUSY"))
                    if (!repository.apply(request, reply)) return
                }
                error("Cloud sync did not finish")
            }
            coroutineScope { repositories.map { async { sync(it) } }.awaitAll() }
            for (repository in repositories) {
                sync(repository)
                assertEquals(setOf("Synthetic edited 0", "Synthetic edited 1"), repository.tasks.first().map { it.title }.toSet())
            }
            val receipt = JSONObject(databases[0].second.shared().intents(repositories[0].scope).first().receipt!!)
            assertEquals(original.getJSONArray("receipts").getJSONObject(0).getString("effectRevision"), receipt.getString("effectRevision"))
            repositories[0].commit(EditorDraft("new", "Synthetic unsent recovery", ""))
            val current = home("get").getJSONObject("household")
            home("delete", JSONObject().put("stateEpoch", epoch).put("expectedVersion", current.getString("membershipVersion")))
            val denied = checkNotNull(repositories[0].prepare(android.os.SystemClock.elapsedRealtime(), 1))
            try {
                SharedEndpoint().send(token, denied)
                fail("Deleted household must deny sync")
            } catch (error: SyncFailure) {
                assertEquals("FORBIDDEN", error.code)
                repositories[0].block(denied, error.code)
            }
            assertTrue(repositories[0].tasks.first().isEmpty())
            assertTrue(databases[0].second.shared().recovery().any { it.title == "Synthetic unsent recovery" && it.status == "QUARANTINED" })
        } finally {
            if (epoch != null) {
                val current = home("get").getJSONObject("household")
                if (current.getBoolean("active"))
                    home("delete", JSONObject().put("stateEpoch", epoch).put("expectedVersion", current.getString("membershipVersion")))
            }
            for (registration in registrations) identityApi.register(token, installation, registration)
            for ((name, database) in databases) { database.close(); application.deleteDatabase(name) }
        }
    }
}
