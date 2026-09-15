package fi.bundo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.AccountStore
import fi.bundo.data.EditorDraft
import fi.bundo.data.RecoveryExport
import fi.bundo.identity.ValidatedIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import fi.bundo.data.ModelInstaller
import fi.bundo.data.ModelManifest
import fi.bundo.data.ModelFile
import fi.bundo.speech.VoiceController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AccountIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alice = ValidatedIdentity("urn:bun-do:local", "alice")
    private val bob = alice.copy(subject = "bob")
    private fun registration() = UUID.randomUUID().toString()

    @Test fun switchQuarantineOfflineReopenAndSameAccountUnlock() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        var store = AccountStore(context, name)
        try {
            store.active.value!!.inbox.commit(EditorDraft("new", "Anonymous only"))
            store.unlock(alice, registration())
            val a = store.active.value!!
            assertTrue(store.recovery(a).isEmpty())
            a.inbox.commit(EditorDraft("new", "Alice private text", "Alice note"))
            a.inbox.saveDraft(EditorDraft("new", "Unfinished Alice"))
            val recording = a.recordings.begin()
            val pcm = "private recording sample".toByteArray()
            a.recordings.output(recording.id).use { it.write(pcm) }
            assertArrayEquals(pcm, a.recordings.readAudio(recording.id))
            assertFalse(a.recordings.audio(recording.id).readBytes().toString(Charsets.ISO_8859_1).contains("private recording"))

            store.unlock(bob, registration())
            val b = store.active.value!!
            assertTrue(store.recovery(b).isEmpty())
            assertNull(store.matching(a.lease.owner, a.lease.generation))
            try { a.inbox.commit(EditorDraft("new", "Late Alice result")); fail() }
            catch (_: CancellationException) { }
            try { a.recordings.commit(recording.id, "Late native result"); fail() }
            catch (_: CancellationException) { }
            b.inbox.commit(EditorDraft("new", "Bob private text"))

            store.lockNow()
            assertNull(store.active.value)
            assertFalse(b.lease.active)
            store.signOut()
            assertEquals("Anonymous only", store.recovery(store.active.value!!).single().title)
            store.close()
            store = AccountStore(context, name)
            assertNull(store.active.value!!.identity)
            store.unlock(alice, a.registrationId!!)
            val restored = store.active.value!!
            assertEquals(setOf("Alice private text", "Unfinished Alice"), store.recovery(restored).map { it.title }.toSet())
            assertArrayEquals(pcm, restored.recordings.readAudio(recording.id))
            val output = ByteArrayOutputStream()
            RecoveryExport.write(output, store.recovery(restored), "Account inbox", false, restored.lease)
            val json = output.toString("UTF-8")
            assertTrue(json.contains("Alice note"))
            for (secret in listOf("registrationId", "installationId", "access_token", "sequence", "Bob private")) {
                assertFalse(json.contains(secret))
            }
            // A signed-in account stays usable offline after a normal process restart.
            store.close()
            store = AccountStore(context, name)
            assertEquals(alice, store.active.value!!.identity)
            store.active.value!!.inbox.commit(EditorDraft("new", "Offline after restart"))
            assertEquals(2, store.recovery(store.active.value!!).size)
            store.signOut(delete = true)
            store.unlock(alice, registration())
            assertTrue(store.recovery(store.active.value!!).isEmpty())
        } finally { store.close(); clean(name) }
    }

    @Test fun epochAndRegistrationReplacementRetainAcceptedTextWhoseEffectsHaveNotArrived() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        try {
            val registration = registration()
            store.unlock(alice, registration)
            var data = store.active.value!!
            val workspace = UUID.randomUUID().toString()
            val oldEpoch = UUID.randomUUID().toString()
            data.selectHousehold(workspace, oldEpoch, "Home")
            val state = data.database.shared().workspaces(registration).single()
            val intent = fi.bundo.data.SharedIntent(state.scope, "1", UUID.randomUUID().toString(), "CreateTask",
                "Accepted but not installed", "Keep this", true, true, "0", "0", "0", null, "{}",
                receipt = org.json.JSONObject().put("effectRevision", "9").toString(), status = "ACCEPTED")
            data.database.shared().saveIntent(intent)
            data.selectHousehold(workspace, UUID.randomUUID().toString(), "Home")
            assertEquals("EPOCH_CHANGED", data.database.shared().intents(state.scope).single().problem)
            val fresh = data.database.shared().workspaces(registration).single { it.epoch != oldEpoch }
            data.database.shared().saveIntent(intent.copy(scope = fresh.scope))
            store.unlock(alice, registration())
            data = store.active.value!!
            assertEquals("REGISTRATION_REPLACED", data.database.shared().intents(fresh.scope).single().problem)
            assertEquals(2, store.recovery(data).count { it.title == "Accepted but not installed" })
        } finally { store.close(); clean(name) }
    }

    @Test fun splitInstructionsAndEditedStepsReachRecoveryExportAfterRemoval() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        try {
            store.unlock(alice, registration())
            val data = store.active.value!!
            data.selectHousehold(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Home")
            val state = data.database.shared().workspaces(data.registrationId!!).single()
            val details = org.json.JSONObject().put("instructions", "My dictated instructions")
                .put("sourceDescription", "Private source")
                .put("rows", org.json.JSONArray()
                    .put(org.json.JSONObject().put("text", "My edited step").put("originalText", "Generated original"))
                    .put(org.json.JSONObject().put("text", "Untouched suggestion").put("originalText", "Untouched suggestion")))
            data.database.shared().saveDraft(fi.bundo.data.SharedDraft(state.scope, "checklist:one", "Private parent", "", 1000,
                details = details.toString()))
            data.database.shared().saveDraft(fi.bundo.data.SharedDraft(state.scope, "checklist:two", "Other parent", "", 1000,
                details = org.json.JSONObject().put("instructions", "Only instructions").toString()))
            fun exported(records: List<fi.bundo.data.RecoveryText>, plain: Boolean): String {
                val output = ByteArrayOutputStream()
                RecoveryExport.write(output, records, "Home", plain, data.lease)
                return output.toString("UTF-8")
            }
            val before = exported(store.recovery(data), true)
            assertTrue(before.contains("My edited step")); assertTrue(before.contains("My dictated instructions"))
            val repository = fi.bundo.data.SharedRepository(data.database, data.lease, state.scope, state.registration)
            repository.block(repository.prepare(1000, 1)!!, "FORBIDDEN")
            val records = store.recovery(data)
            for (plain in listOf(true, false)) {
                val output = exported(records, plain)
                for (authored in listOf("My edited step", "My dictated instructions", "Only instructions")) assertTrue(output.contains(authored))
                for (remote in listOf("Private parent", "Other parent", "Private source", "Generated original", "Untouched suggestion")) assertFalse(output.contains(remote))
            }
        } finally { store.close(); clean(name) }
    }

    @Test fun versionedRecoveryExportKeepsLabelsAndDatesAndImportsOnlyTextWithNewIds() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        try {
            store.unlock(alice, registration())
            val data = store.active.value!!
            val source = fi.bundo.data.RecoveryText("ignored-old-identity", "Retained text", "Retained description",
                1_700_000_000_000L, "Our household", "OUTCOME_EXPIRED")
            val output = ByteArrayOutputStream()
            RecoveryExport.write(output, listOf(source), "Fallback", false, data.lease)
            val json = org.json.JSONObject(output.toString("UTF-8"))
            val record = json.getJSONArray("records").getJSONObject(0)
            assertEquals("Our household", record.getString("workspaceLabel"))
            assertEquals("2023-11-14T22:13:20Z", record.getString("capturedAt"))
            assertEquals("OUTCOME_EXPIRED", record.getJSONArray("variants").getJSONObject(0).getString("reason"))
            record.put("taskId", "old-id").put("sequence", "999").put("frozen", "not a command")
            val imported = RecoveryExport.read(json.toString().byteInputStream())
            store.importText(data, imported)
            val saved = data.database.inbox().allTasks().single()
            assertNotEquals("old-id", saved.id)
            assertEquals("Retained text", saved.title)
            assertEquals(1L, data.database.inbox().intents().single().sequence)
            assertTrue(data.database.shared().recovery().isEmpty())
            json.put("formatVersion", 99)
            try { RecoveryExport.read(json.toString().byteInputStream()); fail("Unknown recovery version") }
            catch (_: IllegalArgumentException) { }
            store.unlock(bob, registration())
            try { store.importText(data, imported); fail("Late import must not cross accounts") }
            catch (_: CancellationException) { }
            assertTrue(store.recovery(store.active.value!!).isEmpty())
        } finally { store.close(); clean(name) }
    }

    @Test fun explicitAnonymousImportCreatesNewIdsAndDoesNotReplayIntents() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        try {
            val original = store.active.value!!.inbox.commit(EditorDraft("new", "Choose me", "Keep original"))
            store.active.value!!.inbox.commit(EditorDraft("new", "Do not choose me"))
            store.unlock(alice, registration())
            val data = store.active.value!!
            val preview = store.anonymousPreview(data)
            assertEquals(2, preview.size)
            assertTrue(store.recovery(data).isEmpty())
            store.importAnonymous(data, setOf("task:$original"))
            val copied = store.recovery(data).single()
            assertEquals("Choose me", copied.title)
            assertNotEquals("task:$original", copied.source)
            assertEquals(2, store.anonymousPreview(data).size)
            // SQLCipher must not leave an ordinary SQLite header or plaintext household text.
            val database = File(context.noBackupFilesDir, name).walkTopDown().first { it.name == "inbox.db" }
            val bytes = database.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.startsWith("SQLite format 3"))
            assertFalse(bytes.contains("Choose me"))
        } finally { store.close(); clean(name) }
    }

    @Test fun missingInstallationKeyQuarantinesFilesAndNeverReusesDeviceIdentity() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        var store = AccountStore(context, name)
        val firstInstallation = store.installationId
        try {
            store.unlock(alice, registration())
            store.active.value!!.inbox.commit(EditorDraft("new", "Unexpected restored data"))
            store.close()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.deleteEntry("bundo.$name.installation")
            store = AccountStore(context, name)
            assertTrue(store.unexpectedFiles)
            assertNotEquals(firstInstallation, store.installationId)
            assertNull(store.active.value!!.identity)
            assertTrue(File(context.noBackupFilesDir, name).parentFile!!.listFiles()!!
                .any { it.name.startsWith("$name-unexpected-") })
            store.unlock(alice, registration())
            assertTrue(store.recovery(store.active.value!!).isEmpty())
        } finally { store.close(); clean(name) }
    }

    @Test fun signOutInvalidatesPendingDocumentPickerAndLateAuthentication() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        try {
            store.unlock(alice, registration())
            val data = store.active.value!!
            val request = store.authentication.beginSignIn()
            store.authentication.signOut()
            store.lockNow()
            store.signOut()
            try {
                store.unlock(alice, registration()) { store.authentication.isCurrent(request) }
                fail()
            } catch (_: IllegalStateException) { }
            val output = ByteArrayOutputStream()
            try { RecoveryExport.write(output, emptyList(), "Account inbox", false, data.lease); fail() }
            catch (_: CancellationException) { }
            assertEquals(0, output.size())
        } finally { store.close(); clean(name) }
    }

    @Test fun lateNativeResultCannotWriteAfterSwitchEvenWithoutCancelingTheDecoder() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        val store = AccountStore(context, name)
        val modelRoot = File(context.cacheDir, name).apply { mkdirs() }
        val model = File(modelRoot, "model-${UUID.randomUUID()}").apply { mkdirs() }
        val sample = File(model, "sample").apply { writeText("sample") }
        File(model, "manifest-id").writeText("fixture")
        File(modelRoot, "active").writeText(model.name)
        val manifest = ModelManifest("fixture", "https://example.invalid", 1, "", "",
            listOf(ModelFile("sample", 6, ModelInstaller.digest(sample))))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var controller: VoiceController? = null
        try {
            store.unlock(alice, registration())
            val data = store.active.value!!
            instrumentation.runOnMainSync {
                controller = VoiceController(context, data.recordings, ModelInstaller(modelRoot, manifest)) { _, _ ->
                    started.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    "A late Alice transcript"
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!controller!!.state.value.loaded) {
                check(System.nanoTime() < deadline)
                Thread.sleep(20)
            }
            val recording = data.recordings.begin()
            data.recordings.output(recording.id).use { it.write(byteArrayOf(0, 1)) }
            instrumentation.runOnMainSync { controller!!.retry(recording.id) }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            store.unlock(bob, registration())
            release.countDown()
            while (controller!!.state.value.busy) {
                check(System.nanoTime() < deadline)
                Thread.sleep(20)
            }
            assertTrue(store.recovery(store.active.value!!).isEmpty())
            store.unlock(alice, data.registrationId!!)
            assertTrue(store.recovery(store.active.value!!).isEmpty())
            store.active.value!!.recordings.recover()
            assertTrue(store.active.value!!.recordings.audio(recording.id).exists())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { controller?.close() }
            store.close()
            clean(name)
            modelRoot.deleteRecursively()
        }
    }

    @Test fun mismatchedInstallationProofDoesNotOpenOldAccountFiles() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        var store = AccountStore(context, name)
        try {
            store.unlock(alice, registration())
            val old = store.installationId
            store.close()
            File(context.noBackupFilesDir, "$name/installation").writeBytes(ByteArray(48))
            store = AccountStore(context, name)
            assertTrue(store.unexpectedFiles)
            assertNotEquals(old, store.installationId)
            assertNull(store.active.value!!.identity)
        } finally { store.close(); clean(name) }
    }

    @Test fun replacementRegistrationIsExplicitDurableAndAccountScoped() = runBlocking {
        val name = "account-test-${UUID.randomUUID()}"
        var store = AccountStore(context, name)
        try {
            assertEquals(store.installationId, store.registrationInstallation(alice))
            store.unlock(alice, registration())
            store.active.value!!.inbox.commit(EditorDraft("new", "Retained after retirement"))
            val replacement = store.registrationInstallation(alice, replace = true)
            assertNotEquals(store.installationId, replacement)
            assertEquals(store.installationId, store.registrationInstallation(bob))
            store.close()
            store = AccountStore(context, name)
            assertEquals(replacement, store.registrationInstallation(alice))
            assertEquals("Retained after retirement", store.recovery(store.active.value!!).single().title)
            store.signOut()
            store.unlock(alice, registration())
            assertEquals("Retained after retirement", store.recovery(store.active.value!!).single().title)
        } finally { store.close(); clean(name) }
    }

    private fun clean(name: String) {
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.deleteRecursively() }
        context.deleteDatabase("$name-anonymous.db")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry("bundo.$name.installation")
        keyStore.deleteEntry("bundo.$name.active")
    }
}
