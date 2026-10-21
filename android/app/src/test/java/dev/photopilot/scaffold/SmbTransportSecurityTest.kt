package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.SmbClient
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SmbTransportSecurityTest {
    @Test
    fun publicPlainSessionIsBlocked() {
        assertPlainSessionBlocked("203.0.113.10")
    }

    @Test
    fun privatePlainSessionRequiresHostOptIn() {
        assertPlainSessionBlocked("192.168.1.10")
    }

    @Test
    fun privatePlainSessionUsesOnlyMatchingHostPortOptIn() {
        var allowed = setOf("192.168.1.10:1445")
        val policy = NasTlsPolicy(pinLookup = { null }, cleartextHosts = { allowed })
        val client = negotiatedClient("192.168.1.10", 1445, false, policy)
        assertEquals(false, client.security().encrypted)
        allowed = setOf("192.168.1.10:445", "192.168.1.11:1445")
        assertBlocked { client.security() }
    }

    @Test
    fun publicPlainSessionCannotUseSavedOptIn() {
        val policy = NasTlsPolicy(
            pinLookup = { null },
            cleartextHosts = { setOf("203.0.113.10:445") },
        )
        assertBlocked { negotiatedClient("203.0.113.10", 445, false, policy).security() }
    }

    @Test
    fun publicEncryptedSessionNeedsNoPlaintextOptIn() {
        val policy = NasTlsPolicy(pinLookup = { null }, cleartextHosts = { emptySet() })
        assertEquals(true, negotiatedClient("203.0.113.10", 445, true, policy).security().encrypted)
    }

    private fun negotiatedClient(host: String, port: Int, encrypted: Boolean, policy: NasTlsPolicy) =
        SmbClient(host, port, "fixture", "", "", tlsPolicy = policy).apply {
            dialectProbe = { if (encrypted) "SMB_3_1_1" else "SMB_2_1" }
            encryptionProbe = { encrypted }
            signingProbe = { true }
        }

    private fun assertBlocked(operation: () -> Unit) {
        try {
            operation()
            fail("unencrypted session requires private host opt-in")
        } catch (failure: SmbClient.SmbException) {
            assertEquals(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED, failure.code)
        }
    }

    @Test
    fun handshakeFailureDoesNotRetryWithoutEncryption() {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger()
            val worker = thread(isDaemon = true) {
                try {
                    while (!server.isClosed) {
                        server.accept().use { accepted.incrementAndGet() }
                    }
                } catch (_: java.io.IOException) {
                }
            }
            val client = SmbClient("127.0.0.1", server.localPort, "fixture", "", "")
            try {
                client.security()
                fail("closed transport must fail negotiation")
            } catch (_: SmbClient.SmbException) {
                assertEquals(1, accepted.get())
            } finally {
                client.close()
                server.close()
                worker.join(1000)
            }
        }
    }

    private fun assertPlainSessionBlocked(host: String) {
        val client = SmbClient(host, 445, "fixture", "", "").apply {
            dialectProbe = { "SMB_2_1" }
            encryptionProbe = { false }
            signingProbe = { true }
        }
        try {
            client.security()
            fail("unencrypted session requires private host opt-in")
        } catch (failure: SmbClient.SmbException) {
            assertEquals(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED, failure.code)
        }
    }
}
