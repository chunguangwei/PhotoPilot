package dev.photopilot.scaffold.data.secure

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CredentialEnvelope {

    const val IV_BYTES = 12
    const val TAG_BITS = 128
    const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun decrypt(key: SecretKey, envelope: ByteArray): ByteArray? {
        if (envelope.size < IV_BYTES + TAG_BITS / 8) return null
        val iv = envelope.copyOfRange(0, IV_BYTES)
        val ciphertext = envelope.copyOfRange(IV_BYTES, envelope.size)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }
}
