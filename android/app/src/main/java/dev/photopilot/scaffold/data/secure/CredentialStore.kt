package dev.photopilot.scaffold.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.photopilot.scaffold.data.logic.ContentHash
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialStore(context: Context) {

    private val dir = File(context.filesDir, "credentials")

    fun save(fingerprint: String, credentialJson: String) {
        dir.mkdirs()
        val file = fileFor(fingerprint)
        val envelope = CredentialEnvelope.encrypt(
            getOrCreateKey(),
            credentialJson.toByteArray(Charsets.UTF_8),
        )
        file.writeBytes(envelope)
    }

    fun load(fingerprint: String): String? {
        val file = fileFor(fingerprint)
        if (!file.isFile) return null
        val plaintext = runCatching {
            CredentialEnvelope.decrypt(getOrCreateKey(), file.readBytes())
        }.getOrNull()
        if (plaintext == null) {
            file.delete()
            return null
        }
        return plaintext.toString(Charsets.UTF_8)
    }

    fun delete(fingerprint: String) {
        fileFor(fingerprint).delete()
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    internal fun fileFor(fingerprint: String): File {
        val hex = ContentHash.toHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toByteArray(Charsets.UTF_8)),
        )
        return File(dir, "$hex.enc")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            val probe = runCatching {
                CredentialEnvelope.decrypt(
                    entry.secretKey,
                    CredentialEnvelope.encrypt(entry.secretKey, ByteArray(1)),
                )
            }.getOrNull()
            if (probe != null) {
                return entry.secretKey
            }
            keyStore.deleteEntry(KEY_ALIAS)
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "photopilot_credential_key"
        const val KEY_BITS = 256
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
