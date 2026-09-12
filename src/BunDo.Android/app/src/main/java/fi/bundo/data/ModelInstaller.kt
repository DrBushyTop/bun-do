package fi.bundo.data

import android.annotation.SuppressLint
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class ModelFile(val name: String, val bytes: Long, val sha256: String, val install: Boolean = true)
data class ModelManifest(
    val id: String,
    val url: String,
    val archiveBytes: Long,
    val sha256: String,
    val prefix: String,
    val files: List<ModelFile>,
) {
    // Free space already excludes the installed model. Never delete it to make room.
    val requiredFreeBytes: Long get() = (archiveBytes + files.filter { it.install }.sumOf { it.bytes }) * 5 / 4 + 64 * 1024 * 1024
}

class SpeechStorageFull : IOException()

/** One installed generation and an atomic pointer. A partial download is never a model. */
class ModelInstaller(private val root: File, val manifest: ModelManifest) {
    init { root.mkdirs() }

    private fun pointedDirectory(): File? {
        val pointer = File(root, "active")
        if (!pointer.isFile) return null
        val name = pointer.readText()
        if (!name.matches(Regex("model-[a-f0-9-]{36}"))) return null
        return File(root, name)
    }

    fun active(): File? {
        return pointedDirectory()?.takeIf {
            File(it, "manifest-id").takeIf(File::isFile)?.readText() == manifest.id &&
                manifest.files.filter { entry -> entry.install }.all { entry ->
                    File(it, entry.name).let { file -> file.isFile && file.length() == entry.bytes }
                }
        }
    }

    fun verifiedActive(checkCancelled: () -> Unit = {}): File? = active()?.takeIf { directory ->
        manifest.files.filter { it.install }.all {
            checkCancelled()
            digest(File(directory, it.name), checkCancelled) == it.sha256
        }
    }

    @SuppressLint("UsableSpace") // Reserve actual free bytes; do not evict caches to make installation fit.
    fun install(
        source: () -> InputStream,
        freeBytes: () -> Long = { root.usableSpace },
        checkCancelled: () -> Unit = {},
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File = locked {
        removeStaging()
        if (freeBytes() < manifest.requiredFreeBytes) throw SpeechStorageFull()
        val archive = File(root, "download.part")
        val stage = File(root, "staging").apply { mkdirs() }
        try {
            source().use { input ->
                FileOutputStream(archive).use { output ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    copyBounded(input, output, manifest.archiveBytes, hash, checkCancelled) { progress(it, manifest.archiveBytes) }
                    output.fd.sync()
                    check(hash.hex() == manifest.sha256) { "Model archive checksum mismatch" }
                }
            }
            progress(manifest.archiveBytes, manifest.archiveBytes)
            archive.inputStream().buffered().use { input ->
                TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                    val seen = mutableSetOf<String>()
                    while (true) {
                        checkCancelled()
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) {
                            check(entry.name == manifest.prefix || entry.name == manifest.prefix + "test_wavs/")
                            continue
                        }
                        check(entry.isFile && !entry.isSymbolicLink && !entry.isLink)
                        check(entry.name.startsWith(manifest.prefix))
                        val name = entry.name.removePrefix(manifest.prefix)
                        val expected = manifest.files.singleOrNull { it.name == name }
                            ?: error("Unexpected model archive entry")
                        check(seen.add(name) && entry.size == expected.bytes)
                        val hash = MessageDigest.getInstance("SHA-256")
                        if (expected.install) {
                            // Only flat, manifest-owned model names become files.
                            check(!name.contains('/') && name != "." && name != "..")
                            FileOutputStream(File(stage, name)).use { output ->
                                copyBounded(tar, output, expected.bytes, hash, checkCancelled)
                                output.fd.sync()
                            }
                        } else {
                            copyBounded(tar, null, expected.bytes, hash, checkCancelled)
                        }
                        check(hash.hex() == expected.sha256) { "Model file checksum mismatch" }
                    }
                    check(seen == manifest.files.map { it.name }.toSet()) { "Incomplete model archive" }
                }
            }
            checkCancelled()
            writeSynced(File(stage, "manifest-id"), manifest.id)
            val destination = File(root, "model-${UUID.randomUUID()}")
            check(stage.renameTo(destination))
            writeSynced(File(root, "active.part"), destination.name)
            Files.move(File(root, "active.part").toPath(), File(root, "active").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            destination
        } finally {
            removeStaging()
        }
    }

    /** Called only when no recorder/recognizer can hold a model from the prior process. */
    fun recover() = locked {
        removeStaging()
        val current = pointedDirectory()?.name
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("model-") && it.name != current }
            ?.forEach { it.deleteRecursively() }
    }

    private fun removeStaging() {
        File(root, "staging").deleteRecursively()
        File(root, "download.part").delete()
        File(root, "active.part").delete()
    }

    private fun <T> locked(block: () -> T): T =
        RandomAccessFile(File(root, "install.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }

    companion object {
        private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }

        private fun copyBounded(
            input: InputStream, output: FileOutputStream?, expected: Long, digest: MessageDigest,
            checkCancelled: () -> Unit, progress: (Long) -> Unit = {},
        ) {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= expected) { "Oversized model data" }
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
                progress(total)
            }
            check(total == expected) { "Truncated model data" }
        }

        fun digest(file: File, checkCancelled: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { copyBounded(it, null, file.length(), hash, checkCancelled) }
            return hash.hex()
        }

        private fun writeSynced(file: File, text: String) {
            FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() }
        }
    }
}
