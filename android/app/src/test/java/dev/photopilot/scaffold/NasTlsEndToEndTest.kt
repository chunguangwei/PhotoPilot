package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.TofuPinStore
import dev.photopilot.scaffold.data.net.WebDavClient
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NasTlsEndToEndTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pins = mutableMapOf<String, TofuPinStore.Pin>()
    private lateinit var server: RawFixtureServer
    private val payload = "tls-bytes".toByteArray()

    private val port: Int
        get() = server.port

    @Before
    fun start() {
        val keystore = File(folder.root, "fixture.p12")
        generateSelfSigned(keystore)
        val sslContext = SSLContext.getInstance("TLS")
        val keyStore = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { keyStore.load(it, STORE_PASSWORD.toCharArray()) }
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManager.init(keyStore, STORE_PASSWORD.toCharArray())
        sslContext.init(keyManager.keyManagers, null, null)
        server = RawFixtureServer(sslContext = sslContext) { request ->
            RawFixtureServer.RawResponse(200, "OK", body = payload)
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop()
    }

    @Test
    fun tofuFlowUnknownThenPinnedThenChanged() {
        val policy = NasTlsPolicy(
            pinLookup = { pins[it] },
            cleartextHosts = { emptySet() },
        )
        val key = NasTlsPolicy.hostPortKey("127.0.0.1", port)

        val first = policy.connectionContext("127.0.0.1", port)
        val firstClient = WebDavClient(
            baseUrl = "https://127.0.0.1:$port",
            tlsPolicy = policy,
            tlsContext = first,
        )
        try {
            firstClient.openGet("a.jpg")
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(NasTlsPolicy.ERR_CERT_UNTRUSTED, e.code)
        }
        val fingerprint = first.lastError?.fingerprint
        assertNotNull(fingerprint)
        assertEquals(64, fingerprint?.length)
        assertTrue(first.lastError?.tofuEligible == true)

        pins[key] = TofuPinStore.Pin(fingerprint!!, 1L, 2L)
        val second = policy.connectionContext("127.0.0.1", port)
        val pinnedClient = WebDavClient(
            baseUrl = "https://127.0.0.1:$port",
            tlsPolicy = policy,
            tlsContext = second,
        )
        pinnedClient.openGet("a.jpg").stream.use {
            assertEquals(payload.size, it.readBytes().size)
        }
        assertNull(second.lastError)

        pins[key] = TofuPinStore.Pin("00".repeat(32), 1L, 2L)
        val third = policy.connectionContext("127.0.0.1", port)
        val changedClient = WebDavClient(
            baseUrl = "https://127.0.0.1:$port",
            tlsPolicy = policy,
            tlsContext = third,
        )
        try {
            changedClient.openGet("a.jpg")
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(NasTlsPolicy.ERR_FINGERPRINT_CHANGED, e.code)
        }
    }

    private fun generateSelfSigned(keystore: File) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        val process = ProcessBuilder(
            keytool.absolutePath,
            "-genkeypair",
            "-alias", "fixture",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "3650",
            "-storetype", "PKCS12",
            "-keystore", keystore.absolutePath,
            "-storepass", STORE_PASSWORD,
            "-keypass", STORE_PASSWORD,
            "-dname", "CN=localhost",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("keytool failed: $output", 0, process.waitFor())
    }

    private companion object {
        const val STORE_PASSWORD = "fixturepass"
    }
}
