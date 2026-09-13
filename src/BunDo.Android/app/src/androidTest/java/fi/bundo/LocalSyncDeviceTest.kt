package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.IdentityEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Opt-in phases let the external runner control both AVDs' actual connectivity and restart each process. */
@RunWith(AndroidJUnit4::class)
class LocalSyncDeviceTest {
    @Test fun localSyncPhase() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("localSyncPhase") != null)
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".local"))
        assumeTrue(BuildConfig.IDENTITY_API_BASE == "http://10.0.2.2:7275/api")
        val phase = args.getString("localSyncPhase")!!
        val workspace = args.getString("workspace")!!.also(UUID::fromString)
        val epoch = args.getString("epoch")!!.also(UUID::fromString)
        val person = args.getString("person")!!.also { require(it in listOf("alice", "bob")) }
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BunDoApplication
        val prefs = application.getSharedPreferences("sync-device-tests", 0)
        val name = "sync-device-$workspace.db"
        if (phase == "cleanup") {
            application.deleteDatabase(name)
            prefs.edit().remove(workspace).commit()
            return@runBlocking
        }
        suspend fun token(): String {
            val connection = URL("${BuildConfig.IDENTITY_API_BASE}/dev/token/$person/valid").openConnection() as HttpURLConnection
            return try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                check(connection.responseCode == 200)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getString("access_token")
            } finally { connection.disconnect() }
        }
        if (phase == "prepare") {
            val credential = token()
            val api = IdentityEndpoint("urn:bun-do:local")
            val identity = api.verify(credential)
            val installation = application.accounts.registrationInstallation(identity)
            val registration = api.register(credential, installation)
            prefs.edit().putString(workspace, registration).commit()
        }
        val registration = checkNotNull(prefs.getString(workspace, null))
        val database = InboxDatabase.open(application, name)
        val key = "$workspace/$epoch/$registration"
        val lease = DataLease()
        try {
            if (database.shared().workspace(key) == null)
                database.shared().saveWorkspace(SharedWorkspace(key, workspace, epoch, registration, "Live sync fixture"))
            val repository = SharedRepository(database, lease, key, registration)
            when (phase) {
                "prepare" -> assertTrue(repository.tasks.first().isEmpty())
                "capture" -> {
                    // Deliberately no token acquisition or HTTP in this phase.
                    val id = repository.commit(EditorDraft("new", "$person original", ""))
                    repository.commit(EditorDraft(id, "$person edited offline", ""))
                    assertEquals("$person edited offline", repository.tasks.first().single().title)
                    assertEquals(2, database.shared().intents(key).size)
                }
                "sync", "verify" -> {
                    val credential = token()
                    var finished = false
                    for (attempt in 0 until 30) {
                        val request = checkNotNull(repository.prepare(android.os.SystemClock.elapsedRealtime(), 1))
                        val reply = SharedEndpoint().send(credential, request)
                        assertEquals("ACCEPTED", reply.getString("code"))
                        if (!repository.apply(request, reply)) { finished = true; break }
                    }
                    assertTrue("Sync must finish its bounded fixture", finished)
                    if (phase == "verify")
                        assertEquals(setOf("alice edited offline", "bob edited offline"), repository.tasks.first().map { it.title }.toSet())
                }
                else -> error("Unknown phase")
            }
        } finally { lease.revoke(); lease.drain(); database.close() }
    }
}
