package dev.photopilot.scaffold.data.net

import dev.photopilot.scaffold.data.logic.ContentHash
import dev.photopilot.scaffold.data.logic.NasAddress
import java.net.Socket
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class NasTlsPolicy(
    private val pinLookup: (String) -> TofuPinStore.Pin?,
    private val cleartextHosts: () -> Set<String>,
    private val privateEligible: (String) -> Boolean = NasAddress::isPrivateEligible,
    private val systemTrustManager: X509TrustManager = defaultSystemTrustManager(),
) {

    data class TlsError(
        val code: String,
        val fingerprint: String?,
        val issuer: String?,
        val tofuEligible: Boolean,
        val validUntilMs: Long? = null,
    )

    class UnknownTofuException(
        val fingerprint: String,
        val issuer: String,
        cause: Throwable,
    ) : SSLException("certificate not pinned", cause)

    class FingerprintChangedException(
        val fingerprint: String,
        cause: Throwable,
    ) : SSLException("fingerprint changed", cause)

    fun cleartextError(host: String, port: Int): String? =
        if (privateEligible(host) && hostPortKey(host, port) in cleartextHosts()) {
            null
        } else {
            ERR_CLEARTEXT_BLOCKED
        }

    fun connectionContext(host: String, port: Int): ConnectionContext =
        ConnectionContext(host, port)

    fun classify(error: Throwable, host: String, port: Int): TlsError {
        val causes = generateSequence(error) { it.cause }
        causes.filterIsInstance<UnknownTofuException>().firstOrNull()?.let {
            return TlsError(ERR_CERT_UNTRUSTED, it.fingerprint, it.issuer, tofuEligible = true)
        }
        causes.filterIsInstance<FingerprintChangedException>().firstOrNull()?.let {
            return TlsError(ERR_FINGERPRINT_CHANGED, it.fingerprint, null, tofuEligible = true)
        }
        causes.firstOrNull {
            it is CertificateExpiredException || it is CertificateNotYetValidException
        }?.let {
            return TlsError(ERR_CERT_UNTRUSTED, null, null, tofuEligible = false)
        }
        if (error is SSLPeerUnverifiedException || causes.any { it is SSLPeerUnverifiedException }) {
            return TlsError(ERR_HOSTNAME_MISMATCH, null, null, tofuEligible = true)
        }
        return TlsError(ERR_PROTOCOL_MISMATCH, null, null, tofuEligible = false)
    }

    fun fingerprintOf(cert: X509Certificate): String =
        ContentHash.toHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded),
        ).lowercase()

    inner class ConnectionContext(
        private val host: String,
        private val port: Int,
    ) {

        @Volatile
        var lastError: TlsError? = null
            private set

        val socketFactory: SSLSocketFactory by lazy {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(TofuTrustManager(host, port, this)), null)
            context.socketFactory
        }

        val hostnameVerifier: HostnameVerifier by lazy {
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            HostnameVerifier { hostname, session ->
                if (defaultVerifier.verify(hostname, session)) {
                    true
                } else {
                    val fingerprint = sessionLeafFingerprint(session)
                    val pin = pinLookup(hostPortKey(host, port))
                    if (fingerprint != null && pin?.fingerprint == fingerprint) {
                        true
                    } else {
                        lastError = TlsError(
                            ERR_HOSTNAME_MISMATCH,
                            fingerprint,
                            sessionLeafIssuer(session),
                            tofuEligible = true,
                        )
                        false
                    }
                }
            }
        }

        fun noteError(error: TlsError) {
            lastError = error
        }
    }

    inner class TofuTrustManager(
        private val host: String,
        private val port: Int,
        private val context: ConnectionContext,
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            require(!chain.isNullOrEmpty()) { "empty chain" }
            val leaf = chain[0]
            val fingerprint = fingerprintOf(leaf)
            val issuer = leaf.issuerX500Principal?.name
            val validUntil = runCatching { leaf.notAfter.time }.getOrNull()
            try {
                chain.forEach { it.checkValidity() }
            } catch (e: CertificateException) {
                context.noteError(
                    TlsError(ERR_CERT_UNTRUSTED, fingerprint, issuer, tofuEligible = false, validUntilMs = validUntil),
                )
                throw e
            }
            try {
                systemTrustManager.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                val pin = pinLookup(hostPortKey(host, port))
                when {
                    pin == null -> {
                        context.noteError(
                            TlsError(ERR_CERT_UNTRUSTED, fingerprint, issuer, tofuEligible = true, validUntilMs = validUntil),
                        )
                        throw UnknownTofuException(fingerprint, issuer.orEmpty(), e)
                    }
                    pin.fingerprint == fingerprint -> return
                    else -> {
                        context.noteError(
                            TlsError(ERR_FINGERPRINT_CHANGED, fingerprint, issuer, tofuEligible = true, validUntilMs = validUntil),
                        )
                        throw FingerprintChangedException(fingerprint, e)
                    }
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            systemTrustManager.acceptedIssuers
    }

    companion object {
        const val ERR_CERT_UNTRUSTED = "SOURCE_CERT_UNTRUSTED"
        const val ERR_HOSTNAME_MISMATCH = "SOURCE_CERT_HOSTNAME_MISMATCH"
        const val ERR_FINGERPRINT_CHANGED = "TLS_FINGERPRINT_CHANGED"
        const val ERR_CLEARTEXT_BLOCKED = "TLS_CLEARTEXT_BLOCKED"
        const val ERR_PROTOCOL_MISMATCH = "SRC_PROTOCOL_MISMATCH"

        fun hostPortKey(host: String, port: Int): String = "${host.lowercase()}:$port"

        fun defaultSystemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private fun sessionLeafFingerprint(session: SSLSession): String? =
            runCatching {
                val certs: Array<Certificate> = session.peerCertificates
                val leaf = certs.firstOrNull() as? X509Certificate ?: return@runCatching null
                ContentHash.toHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(leaf.encoded),
                ).lowercase()
            }.getOrNull()

        private fun sessionLeafIssuer(session: SSLSession): String? =
            runCatching {
                val certs: Array<Certificate> = session.peerCertificates
                (certs.firstOrNull() as? X509Certificate)?.issuerX500Principal?.name
            }.getOrNull()
    }
}
