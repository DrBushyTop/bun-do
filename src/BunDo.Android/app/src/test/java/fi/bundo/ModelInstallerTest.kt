package fi.bundo

import fi.bundo.data.ModelFile
import fi.bundo.data.ModelInstaller
import fi.bundo.data.ModelManifest
import fi.bundo.data.SpeechStorageFull
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelInstallerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val data = "fake model, never passed to JNI".toByteArray()

    private fun archive(names: List<String> = listOf("model/encoder.onnx")): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            for (name in names) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun manifest(archive: ByteArray) = ModelManifest(
        "test-v1", "https://example.invalid/model", archive.size.toLong(), hash(archive), "model/",
        listOf(ModelFile("encoder.onnx", data.size.toLong(), hash(data))),
    )

    @Test fun activatesOnlyVerifiedFilesAndReopensWithoutNetwork() {
        val bytes = archive()
        val root = folder.newFolder()
        val installer = ModelInstaller(root, manifest(bytes))
        assertNull(installer.active())
        val active = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        assertArrayEquals(data, File(active, "encoder.onnx").readBytes())
        assertEquals(active, ModelInstaller(root, manifest(bytes)).verifiedActive())
        assertFalse(File(root, "download.part").exists())
        assertFalse(File(root, "staging").exists())
    }

    @Test fun insufficientStorageDoesNotOpenNetworkOrDeleteOldModel() {
        val bytes = archive()
        val installer = ModelInstaller(folder.newFolder(), manifest(bytes))
        val old = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        assertThrows(SpeechStorageFull::class.java) {
            installer.install({ error("Network must not open") }, { 0L })
        }
        assertEquals(old, installer.verifiedActive())
    }

    @Test fun corruptOrTruncatedReplacementLeavesActiveGenerationUnchanged() {
        val bytes = archive()
        val installer = ModelInstaller(folder.newFolder(), manifest(bytes))
        val old = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        for (replacement in listOf(bytes.copyOf(bytes.size - 10), bytes + byteArrayOf(1), bytes.reversedArray())) {
            assertTrue(runCatching {
                installer.install({ ByteArrayInputStream(replacement) }, { Long.MAX_VALUE })
            }.isFailure)
            assertEquals(old, installer.verifiedActive())
        }
    }

    @Test fun rejectsTraversalDuplicateUnexpectedAndMissingEntries() {
        for (names in listOf(
            listOf("model/../escape"), listOf("model/encoder.onnx", "model/encoder.onnx"),
            listOf("model/extra"), emptyList(),
        )) {
            val bytes = archive(names)
            val installer = ModelInstaller(folder.newFolder(), manifest(bytes))
            assertTrue(runCatching { installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE }) }.isFailure)
            assertNull(installer.active())
        }
    }

    @Test fun cancellationCleansStagingAndKeepsOldModel() {
        val bytes = archive()
        val root = folder.newFolder()
        val installer = ModelInstaller(root, manifest(bytes))
        val old = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        assertThrows(CancellationException::class.java) {
            installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE }, { throw CancellationException() })
        }
        assertEquals(old, installer.active())
        assertFalse(File(root, "staging").exists())
    }

    @Test fun processDeathRecoveryDiscardsOnlyStagingAndUnusedGeneration() {
        val bytes = archive()
        val root = folder.newFolder()
        val installer = ModelInstaller(root, manifest(bytes))
        val old = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        val active = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        assertTrue(old.exists()) // Never remove files an in-process recognizer could still own.
        File(root, "download.part").writeBytes(byteArrayOf(1))
        File(root, "staging").mkdir()
        File(root, "active.part").writeText("invalid")
        ModelInstaller(root, manifest(bytes)).recover()
        assertEquals(active, installer.verifiedActive())
        assertFalse(old.exists())
        assertFalse(File(root, "download.part").exists())
        assertFalse(File(root, "active.part").exists())
    }

    @Test fun detectsInstalledCorruptionEvenWhenSizeMatches() {
        val bytes = archive()
        val installer = ModelInstaller(folder.newFolder(), manifest(bytes))
        val active = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        File(active, "encoder.onnx").writeBytes(ByteArray(data.size))
        assertNull(installer.verifiedActive())
    }

    @Test fun firstInstallDeathBeforePointerDoesNotLeakAnUnreferencedModel() {
        val bytes = archive()
        val root = folder.newFolder()
        val installer = ModelInstaller(root, manifest(bytes))
        val orphan = installer.install({ ByteArrayInputStream(bytes) }, { Long.MAX_VALUE })
        File(root, "active").delete() // Death between generation rename and first pointer switch.
        installer.recover()
        assertFalse(orphan.exists())
        assertNotNull(installer.install({ ByteArrayInputStream(bytes) }, {
            if (orphan.exists()) 0L else Long.MAX_VALUE
        }))
    }
}
