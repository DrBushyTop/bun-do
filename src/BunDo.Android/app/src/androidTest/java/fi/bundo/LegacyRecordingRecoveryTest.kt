package fi.bundo

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.bundo.data.*
import fi.bundo.identity.ValidatedIdentity
import fi.bundo.speech.VoiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LegacyRecordingRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InboxDatabase::class.java)

    private fun oldInstallation(name: String, expired: Boolean = false): Pair<String, ByteArray> {
        val root = File(context.noBackupFilesDir, name).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val created = now - if (expired) RecordingStore.RETAIN_MILLIS + 1000 else 1000
        migrations.createDatabase(File(root, InboxDatabase.FILE_NAME).path, 3).use {
            it.execSQL("INSERT INTO voice_recordings VALUES (?, ?, ?, 'TRANSCRIBING', '')", arrayOf<Any>(id, created, created + RecordingStore.RETAIN_MILLIS))
        }
        val pcm = ByteArray(640) { (it % 128).toByte() }
        File(root, "anonymous-audio").mkdirs()
        File(root, "anonymous-audio/$id.pcm").writeBytes(pcm)
        return id to pcm
    }

    @Test fun missingProofKeepsAnonymousRecordingDiscoverableWithoutImportingIt() = runBlocking {
        val name = "legacy-test-${UUID.randomUUID()}"
        val (id, pcm) = oldInstallation(name)
        val store = AccountStore(context, name)
        try {
            assertTrue(store.unexpectedFiles)
            val data = store.active.value!!
            assertTrue(data.database.recordings().all().isEmpty())
            val preview = store.legacyAudio.preview(data)
            assertEquals(id, preview.recordings.single().recording.id)
            assertTrue(preview.recordings.single().readable)
            val source = context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("$name-unexpected-") }
            assertArrayEquals(pcm, File(source, "anonymous-audio/$id.pcm").readBytes())
        } finally { store.close(); clean(name) }
    }

    @Test fun exportProducesPlayableWaveAndKeepsSourceEvenWhenDestinationFails() = fixture { store, id, pcm ->
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val output = ByteArrayOutputStream()
        store.legacyAudio.export(data, item.source, output)
        val wave = output.toByteArray()
        assertEquals("RIFF", String(wave, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wave, 8, 4, Charsets.US_ASCII))
        assertArrayEquals(pcm, wave.copyOfRange(44, wave.size))
        val failing = object : OutputStream() {
            override fun write(value: Int) { throw java.io.IOException("Destination unavailable") }
        }
        assertTrue(runCatching { store.legacyAudio.export(data, item.source, failing) }.exceptionOrNull() is java.io.IOException)
        assertEquals(id, store.legacyAudio.preview(data).recordings.single().recording.id)
        val retry = ByteArrayOutputStream()
        store.legacyAudio.export(data, item.source, retry)
        assertArrayEquals(wave, retry.toByteArray())
    }

    @Test fun explicitRecoveryCopiesEncryptedAudioAndRetryCreatesOnlyALocalTask() = fixture { store, _, pcm ->
        store.unlock(ValidatedIdentity("urn:legacy-test", "alice"), UUID.randomUUID().toString())
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val id = store.legacyAudio.recover(data, item.source)
        assertTrue(store.legacyAudio.preview(data).recordings.isEmpty())
        assertArrayEquals(pcm, data.recordings.readAudio(id))
        assertFalse(pcm.contentEquals(data.recordings.audio(id).readBytes()))
        assertEquals(item.recording.expiresAt, data.database.recordings().get(id)!!.expiresAt)
        assertTrue(data.database.inbox().intents().isEmpty())
        val root = File(context.cacheDir, "legacy-model-${UUID.randomUUID()}").apply { mkdirs() }
        val model = File(root, "model-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(model, "test").apply { writeText("test") }
        val manifest = ModelManifest("fixture", "https://example.invalid", 1, "", "",
            listOf(ModelFile("test", 4, ModelInstaller.digest(file))))
        File(model, "manifest-id").writeText("fixture")
        File(root, "active").writeText(model.name)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var controller: VoiceController
        try {
            instrumentation.runOnMainSync {
                controller = VoiceController(context, data.recordings, ModelInstaller(root, manifest)) { _, audio ->
                    assertArrayEquals(pcm, audio)
                    "Vie paperit kierrätykseen"
                }
            }
            await { controller.state.value.loaded }
            instrumentation.runOnMainSync { controller.retry(id) }
            await { controller.state.value.savedTaskId != null }
            assertEquals("Vie paperit kierrätykseen", data.database.inbox().task("voice-$id")!!.title)
            assertEquals(1, data.database.inbox().intents().size)
            assertTrue(data.database.recordings().all().isEmpty())
            assertFalse(data.recordings.audio(id).exists())
            assertNull(data.selectedHousehold.value)
        } finally {
            instrumentation.runOnMainSync { controller.close() }
            root.deleteRecursively()
        }
    }

    @Test fun sourceRetainedAfterCopyInterruptionCanBeRetriedWithoutDuplicateRecordingOrTask() = fixture { store, _, pcm ->
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val id = UUID.nameUUIDFromBytes("legacy-recording/${item.source}".toByteArray()).toString()
        // The copy persisted, but the process died before deleting the anonymous source.
        data.recordings.importLegacy(id, item.recording.createdAt, item.recording.expiresAt, pcm)
        assertEquals(id, store.legacyAudio.recover(data, item.source))
        assertEquals(1, data.database.recordings().all().size)
        data.recordings.commit(id, "One recovered task")
        data.recordings.importLegacy(id, item.recording.createdAt, item.recording.expiresAt, pcm)
        assertTrue(data.database.recordings().all().isEmpty())
        assertEquals(1, data.database.inbox().intents().size)
    }

    @Test fun fullTargetAndRevokedGenerationCannotConsumeOriginal() = fixture { store, _, pcm ->
        val old = store.active.value!!
        val item = store.legacyAudio.preview(old).recordings.single()
        repeat(RecordingStore.MAX_RECORDINGS) { old.recordings.begin() }
        assertTrue(runCatching { store.legacyAudio.recover(old, item.source) }.exceptionOrNull() is RecordingStorageFull)
        val output = ByteArrayOutputStream()
        store.legacyAudio.export(old, item.source, output)
        assertArrayEquals(pcm, output.toByteArray().drop(44).toByteArray())
        store.unlock(ValidatedIdentity("urn:legacy-test", "bob"), UUID.randomUUID().toString())
        assertTrue(runCatching { store.legacyAudio.recover(old, item.source) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { store.legacyAudio.delete(old, item.source) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { store.legacyAudio.export(old, item.source, ByteArrayOutputStream()) }.exceptionOrNull() is CancellationException)
        assertEquals(1, store.legacyAudio.preview(store.active.value!!).recordings.size)
        assertTrue(store.active.value!!.database.recordings().all().isEmpty())
    }

    @Test fun revocationDuringExportStopsWritingWithoutDeletingSource() = fixture { store, _, _ ->
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                data.lease.revoke()
            }
        }
        assertTrue(runCatching { store.legacyAudio.export(data, item.source, output) }.exceptionOrNull() is CancellationException)
        store.signOut()
        assertEquals(1, store.legacyAudio.preview(store.active.value!!).recordings.size)
    }

    @Test fun deleteHandlesMissingAudioAndDoesNotTouchOtherQuarantinedFiles() = fixture { store, id, _ ->
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val root = File(context.noBackupFilesDir, item.source.substringBefore('/'))
        val unknown = File(root, "encrypted-account/audio/$id.pcm").apply { parentFile!!.mkdirs(); writeText("private") }
        File(root, "anonymous-audio/$id.pcm").delete()
        assertFalse(store.legacyAudio.preview(data).recordings.single().readable)
        store.legacyAudio.delete(data, item.source)
        assertTrue(store.legacyAudio.preview(data).recordings.isEmpty())
        assertEquals("private", unknown.readText())
    }

    @Test fun expiryDeletesAudioAndRetainsOneNoticeAcrossRestartUntilAcknowledged() = runBlocking {
        val name = "legacy-test-${UUID.randomUUID()}"
        val (id, _) = oldInstallation(name, expired = true)
        var store = AccountStore(context, name)
        try {
            val preview = store.legacyAudio.preview(store.active.value!!)
            assertTrue(preview.recordings.isEmpty())
            assertEquals(1, preview.expiredCount)
            assertFalse(sourceDirectory(name).resolve("anonymous-audio/$id.pcm").exists())
            store.close()
            store = AccountStore(context, name)
            assertEquals(1, store.legacyAudio.preview(store.active.value!!).expiredCount)
            store.legacyAudio.acknowledgeExpiry(store.active.value!!)
            assertEquals(0, store.legacyAudio.preview(store.active.value!!).expiredCount)
        } finally { store.close(); clean(name) }
    }

    @Test fun unknownDatabaseAndSymlinkedAudioAreNeverRecoveredOrExpired() = fixture { store, id, _ ->
        val data = store.active.value!!
        val item = store.legacyAudio.preview(data).recordings.single()
        val root = File(context.noBackupFilesDir, item.source.substringBefore('/'))
        val source = File(root, "anonymous-audio/$id.pcm")
        val privateAudio = File(root, "private.pcm").apply { writeText("private") }
        source.delete()
        java.nio.file.Files.createSymbolicLink(source.toPath(), privateAudio.toPath())
        assertTrue(store.legacyAudio.preview(data, Long.MAX_VALUE).recordings.isEmpty())
        assertEquals("private", privateAudio.readText())
        val db = File(root, InboxDatabase.FILE_NAME)
        db.writeText("Unknown encrypted account database")
        assertTrue(store.legacyAudio.preview(data).recordings.isEmpty())
        assertEquals("Unknown encrypted account database", db.readText())
    }

    private fun fixture(action: suspend (AccountStore, String, ByteArray) -> Unit) = runBlocking {
        val name = "legacy-test-${UUID.randomUUID()}"
        val (id, pcm) = oldInstallation(name)
        val store = AccountStore(context, name)
        try { action(store, id, pcm) } finally { store.close(); clean(name) }
    }

    private fun sourceDirectory(name: String) =
        context.noBackupFilesDir.listFiles()!!.single { it.name.startsWith("$name-unexpected-") }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Voice controller did not finish" }
            Thread.sleep(20)
        }
    }

    private fun clean(name: String) {
        context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.deleteRecursively() }
        context.deleteDatabase("$name-anonymous.db")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry("bundo.$name.installation"); keys.deleteEntry("bundo.$name.active")
        context.deleteSharedPreferences("$name-legacy-audio")
    }
}
