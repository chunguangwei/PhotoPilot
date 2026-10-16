package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ContentHash
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.TofuPinStore
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NasTlsPolicyTest {

    private val untrustedSystem = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("untrusted chain")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun fakeCertificate(bytes: ByteArray, expired: Boolean = false): X509Certificate =
        object : X509Certificate() {
            override fun getEncoded(): ByteArray = bytes
            override fun getIssuerX500Principal(): X500Principal = X500Principal("CN=Fixture CA")
            override fun getSubjectX500Principal(): X500Principal = X500Principal("CN=fixture")
            override fun checkValidity() {
                if (expired) throw CertificateExpiredException("expired")
            }
            override fun checkValidity(date: Date?) = checkValidity()
            override fun getVersion(): Int = 3
            override fun getSerialNumber() = java.math.BigInteger.ONE
            override fun getIssuerDN() = null
            override fun getSubjectDN() = null
            override fun getNotBefore() = Date(0)
            override fun getNotAfter() = Date(Long.MAX_VALUE)
            override fun getTBSCertificate() = bytes
            override fun getSignature() = bytes
            override fun getSigAlgName() = "SHA256withRSA"
            override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
            override fun getSigAlgParams(): ByteArray? = null
            override fun getIssuerUniqueID() = null
            override fun getSubjectUniqueID() = null
            override fun getKeyUsage() = null
            override fun getBasicConstraints() = -1
            override fun getPublicKey() = null
            override fun toString() = "fake"
            override fun verify(key: java.security.PublicKey?) = Unit
            override fun verify(key: java.security.PublicKey?, sigProvider: String?) = Unit
            override fun hasUnsupportedCriticalExtension() = false
            override fun getCriticalExtensionOIDs(): Set<String>? = null
            override fun getNonCriticalExtensionOIDs(): Set<String>? = null
            override fun getExtensionValue(oid: String?): ByteArray? = null
        }

    private fun policyWith(pins: Map<String, TofuPinStore.Pin>, cleartext: Set<String> = emptySet()) =
        NasTlsPolicy(
            pinLookup = { pins[it] },
            cleartextHosts = { cleartext },
            privateEligible = { host -> host == "10.0.0.9" },
            systemTrustManager = untrustedSystem,
        )

    private fun fp(bytes: ByteArray): String =
        ContentHash.toHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
        ).lowercase()

    @Test
    fun unknownCertificateCarriesFingerprintAndIssuer() {
        val cert = fakeCertificate("leaf-der-1".toByteArray())
        val policy = policyWith(emptyMap())
        val context = policy.connectionContext("nas.local", 445)
        val thrown = runCatching {
            policy.TofuTrustManager("nas.local", 445, context)
                .checkServerTrusted(arrayOf(cert), "RSA")
        }.exceptionOrNull()
        assertTrue(thrown is NasTlsPolicy.UnknownTofuException)
        val tofu = thrown as NasTlsPolicy.UnknownTofuException
        assertEquals(fp("leaf-der-1".toByteArray()), tofu.fingerprint)
        assertEquals("CN=Fixture CA", tofu.issuer)
        val classified = policy.classify(tofu, "nas.local", 445)
        assertEquals(NasTlsPolicy.ERR_CERT_UNTRUSTED, classified.code)
        assertTrue(classified.tofuEligible)
        assertEquals(fp("leaf-der-1".toByteArray()), classified.fingerprint)
        assertEquals("CN=Fixture CA", classified.issuer)
        assertEquals(fp("leaf-der-1".toByteArray()), context.lastError?.fingerprint)
    }

    @Test
    fun matchingPinAllowsConnection() {
        val cert = fakeCertificate("leaf-der-2".toByteArray())
        val pins = mapOf(
            "nas.local:445" to TofuPinStore.Pin(fp("leaf-der-2".toByteArray()), 1L, 2L),
        )
        val policy = policyWith(pins)
        val context = policy.connectionContext("nas.local", 445)
        policy.TofuTrustManager("nas.local", 445, context)
            .checkServerTrusted(arrayOf(cert), "RSA")
        assertNull(context.lastError)
    }

    @Test
    fun changedFingerprintRejectedAndNotOverwritten() {
        val cert = fakeCertificate("leaf-der-3".toByteArray())
        val pins = mapOf(
            "nas.local:445" to TofuPinStore.Pin("00".repeat(32), 1L, 2L),
        )
        val policy = policyWith(pins)
        val context = policy.connectionContext("nas.local", 445)
        val thrown = runCatching {
            policy.TofuTrustManager("nas.local", 445, context)
                .checkServerTrusted(arrayOf(cert), "RSA")
        }.exceptionOrNull()
        assertTrue(thrown is NasTlsPolicy.FingerprintChangedException)
        assertEquals(fp("leaf-der-3".toByteArray()), (thrown as NasTlsPolicy.FingerprintChangedException).fingerprint)
        assertEquals(NasTlsPolicy.ERR_FINGERPRINT_CHANGED, context.lastError?.code)
    }

    @Test
    fun expiredCertificateNeverTofuEligible() {
        val cert = fakeCertificate("leaf-der-4".toByteArray(), expired = true)
        val pins = mapOf(
            "nas.local:445" to TofuPinStore.Pin(fp("leaf-der-4".toByteArray()), 1L, 2L),
        )
        val policy = policyWith(pins)
        val context = policy.connectionContext("nas.local", 445)
        val thrown = runCatching {
            policy.TofuTrustManager("nas.local", 445, context)
                .checkServerTrusted(arrayOf(cert), "RSA")
        }.exceptionOrNull()
        assertTrue(thrown is CertificateExpiredException)
        assertEquals(NasTlsPolicy.ERR_CERT_UNTRUSTED, context.lastError?.code)
        assertFalse(context.lastError?.tofuEligible ?: true)
    }

    @Test
    fun genericHandshakeFailureIsProtocolMismatchNotCert() {
        val policy = policyWith(emptyMap())
        val classified = policy.classify(
            javax.net.ssl.SSLHandshakeException("no cipher suites in common"),
            "nas.local",
            443,
        )
        assertEquals(NasTlsPolicy.ERR_PROTOCOL_MISMATCH, classified.code)
        assertNull(classified.fingerprint)
        assertFalse(classified.tofuEligible)
    }

    @Test
    fun cleartextGateRequiresPrivateEligibleAndListed() {
        val host = "10.0.0.9"
        val key = NasTlsPolicy.hostPortKey(host, 80)
        assertEquals(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED, policyWith(emptyMap()).cleartextError(host, 80))
        assertNull(policyWith(emptyMap(), cleartext = setOf(key)).cleartextError(host, 80))
        assertEquals(
            NasTlsPolicy.ERR_CLEARTEXT_BLOCKED,
            policyWith(emptyMap(), cleartext = setOf(key)).cleartextError("8.8.8.8", 80),
        )
        assertEquals(
            NasTlsPolicy.ERR_CLEARTEXT_BLOCKED,
            policyWith(emptyMap(), cleartext = setOf("127.0.0.1:80")).cleartextError("127.0.0.1", 80),
        )
    }
}
