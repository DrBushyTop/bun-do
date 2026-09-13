package fi.bundo.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independently authenticated PCM chunks preserve completed chunks after process death.
 * No plaintext account recording or decrypted temporary file is written to disk.
 */
internal object AccountAudio {
    fun output(file: File, key: ByteArray?, lease: DataLease): OutputStream {
        lease.check()
        val raw = FileOutputStream(file)
        val output = DataOutputStream(raw)
        return object : OutputStream() {
            private var index = 0
            private var total = 0L
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                lease.check()
                require(length in 0..8192 && total + length <= RecordingStore.MAX_AUDIO_BYTES)
                if (key == null) output.write(bytes, offset, length) else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                    cipher.updateAAD("${file.name}:$index".toByteArray())
                    val encrypted = cipher.doFinal(bytes, offset, length)
                    output.writeInt(encrypted.size)
                    output.write(cipher.iv)
                    output.write(encrypted)
                }
                total += length
                index++
                output.flush()
                raw.fd.sync()
            }
            override fun close() { output.close() }
        }
    }

    fun read(file: File, key: ByteArray?, lease: DataLease): ByteArray {
        lease.check()
        require(file.length() <= RecordingStore.MAX_AUDIO_BYTES * 2)
        if (key == null) return file.readBytes().also { require(it.size <= RecordingStore.MAX_AUDIO_BYTES) }
        val result = ByteArrayOutputStream()
        DataInputStream(file.inputStream()).use { input ->
            var index = 0
            while (true) {
                lease.check()
                val length = try { input.readInt() } catch (_: EOFException) { break }
                require(length in 16..8208)
                val iv = ByteArray(12)
                val bytes = ByteArray(length)
                try { input.readFully(iv); input.readFully(bytes) }
                catch (_: EOFException) { break } // Retain complete chunks of an interrupted recording.
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD("${file.name}:$index".toByteArray())
                result.write(cipher.doFinal(bytes))
                require(result.size() <= RecordingStore.MAX_AUDIO_BYTES)
                index++
            }
        }
        return result.toByteArray()
    }
}
