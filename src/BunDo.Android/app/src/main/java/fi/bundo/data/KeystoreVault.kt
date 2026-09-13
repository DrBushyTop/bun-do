package fi.bundo.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small metadata only. The account's random database key is wrapped, never stored raw. */
internal class KeystoreVault(private val alias: String) {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun exists(): Boolean = store().containsAlias(alias)
    fun create() {
        if (exists()) return
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun destroy() { store().deleteEntry(alias) }
    private fun key() = checkNotNull(store().getKey(alias, null)) as SecretKey
    fun read(file: File, context: String): ByteArray {
        val bytes = AtomicFile(file).readFully()
        require(bytes.size in 28..65536)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
    fun write(file: File, context: String, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(cipher.iv)
            output.write(cipher.doFinal(bytes))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
