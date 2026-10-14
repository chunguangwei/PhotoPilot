package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.secure.CredentialEnvelope
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CredentialEnvelopeTest {

    private fun newKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundtripRestoresPlaintext() {
        val key = newKey()
        val payload = "{\"host\":\"nas.local\",\"user\":\"u\",\"pass\":\"p\"}".toByteArray()
        val envelope = CredentialEnvelope.encrypt(key, payload)
        assertEquals(payload.size + CredentialEnvelope.IV_BYTES + 16, envelope.size)
        assertArrayEquals(payload, CredentialEnvelope.decrypt(key, envelope))
    }

    @Test
    fun emptyPlaintextRoundtrips() {
        val key = newKey()
        val envelope = CredentialEnvelope.encrypt(key, ByteArray(0))
        assertArrayEquals(ByteArray(0), CredentialEnvelope.decrypt(key, envelope))
    }

    @Test
    fun tamperedCiphertextFails() {
        val key = newKey()
        val envelope = CredentialEnvelope.encrypt(key, "secret".toByteArray())
        envelope[envelope.size - 1] = (envelope.last().toInt() xor 0x01).toByte()
        assertNull(CredentialEnvelope.decrypt(key, envelope))
    }

    @Test
    fun wrongKeyFails() {
        val envelope = CredentialEnvelope.encrypt(newKey(), "secret".toByteArray())
        assertNull(CredentialEnvelope.decrypt(newKey(), envelope))
    }

    @Test
    fun truncatedEnvelopeFails() {
        val key = newKey()
        val envelope = CredentialEnvelope.encrypt(key, "secret".toByteArray())
        assertNull(CredentialEnvelope.decrypt(key, envelope.copyOfRange(0, CredentialEnvelope.IV_BYTES + 4)))
        assertNull(CredentialEnvelope.decrypt(key, ByteArray(0)))
    }

    @Test
    fun ivIsRandomizedPerEncryption() {
        val key = newKey()
        val a = CredentialEnvelope.encrypt(key, "same".toByteArray())
        val b = CredentialEnvelope.encrypt(key, "same".toByteArray())
        assertNotEquals(a.toList(), b.toList())
    }
}
