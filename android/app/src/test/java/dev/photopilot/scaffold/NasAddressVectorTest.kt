package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasAddress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NasAddressVectorTest {

    private val doc = Vectors.load("nas-address.json")
    private val defaultPorts = doc.getJSONObject("default_ports")

    @Test
    fun allAddressCasesMatch() {
        val cases = doc.getJSONArray("cases")
        assertEquals(31, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            if (c.has("parse_input")) {
                val parsed = NasAddress.parseHostInput(c.getString("parse_input"))
                val expected = c.getJSONObject("expected")
                if (!expected.getBoolean("ok")) {
                    assertNull(id, parsed)
                } else {
                    assertNotNull(id, parsed)
                    assertEquals(id, expected.getString("host"), parsed?.host)
                    if (expected.isNull("port")) {
                        assertNull(id, parsed?.port)
                    } else {
                        assertEquals(id, expected.getInt("port"), parsed?.port)
                    }
                }
            }
            if (c.has("classify_host")) {
                val host = c.getString("classify_host")
                assertEquals(
                    id,
                    c.getString("expected_kind"),
                    NasAddress.classifyHost(host).wire,
                )
                assertEquals(
                    id,
                    c.getBoolean("expected_private"),
                    NasAddress.isPrivateEligible(host),
                )
            }
            if (c.has("fingerprint_input")) {
                val input = c.getJSONObject("fingerprint_input")
                assertEquals(
                    id,
                    c.getString("expected_fingerprint"),
                    NasAddress.sourceFingerprint(
                        protocol = input.getString("protocol"),
                        host = input.getString("host"),
                        port = input.getInt("port"),
                        path = input.getString("path"),
                    ),
                )
            }
        }
    }

    @Test
    fun displayNameKeepsBareHostOnDefaultPort() {
        assertEquals("nas.local", NasAddress.displayName("webdav", "nas.local", 443))
        assertEquals("nas.local", NasAddress.displayName("webdav_http", "nas.local", 80))
        assertEquals("nas.local", NasAddress.displayName("smb", "nas.local", 445))
        assertEquals("nas.local:8443", NasAddress.displayName("webdav", "nas.local", 8443))
    }

    @Test
    fun defaultPortsMatchVector() {
        val keys = defaultPorts.keys()
        while (keys.hasNext()) {
            val protocol = keys.next()
            assertEquals(
                protocol,
                defaultPorts.getInt(protocol),
                NasAddress.defaultPort(protocol),
            )
        }
    }
}
