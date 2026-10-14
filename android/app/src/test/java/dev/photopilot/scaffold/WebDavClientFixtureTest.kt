package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.WebDavClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WebDavClientFixtureTest : DavFixtureServer() {

    private fun client(credentials: Pair<String, String> = "" to "", policy: NasTlsPolicy? = null) =
        WebDavClient(
            baseUrl = baseUrl(),
            username = credentials.first,
            password = credentials.second,
            tlsPolicy = policy,
        )

    @Test
    fun propfindParsesNestedListing() {
        directory(
            "",
            listOf(
                Entry("sub", isCollection = true),
                Entry("a.jpg", etag = "etag-a", size = 5L, lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"),
                Entry("b.JPG", size = 7L),
            ),
        )
        val entries = client().propfind("", depth = 1)
        val self = entries.single { it.href == "/photos/" }
        assertEquals(true, self.isCollection)
        val a = entries.single { it.href == "/photos/a.jpg" }
        assertEquals("etag-a", a.etag)
        assertEquals(5L, a.size)
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", a.lastModified)
        assertEquals(false, a.isCollection)
        val b = entries.single { it.href == "/photos/b.JPG" }
        assertNull(b.etag)
        assertEquals(7L, b.size)
        val sub = entries.single { it.href == "/photos/sub" }
        assertEquals(true, sub.isCollection)
    }

    @Test
    fun getStreamsBytesWithRevision() {
        val payload = "image-bytes".toByteArray()
        directory("", listOf(Entry("a.jpg", etag = "etag-a", size = payload.size.toLong(), bytes = payload)))
        val response = client().openGet("a.jpg")
        assertArrayEquals(payload, response.stream.readBytes())
        assertEquals(payload.size.toLong(), response.byteSize)
        assertEquals("etag-a", response.revision)
    }

    @Test
    fun errorMapping() {
        statusOverride("/photos/missing", 404)
        statusOverride("/photos/nope.jpg", 404)
        statusOverride("/photos/locked", 401)
        statusOverride("/photos/boom", 500)
        try {
            client().propfind("missing", 1)
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(WebDavClient.ERR_SRC_PATH_NOT_FOUND, e.code)
        }
        try {
            client().openGet("nope.jpg")
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(WebDavClient.ERR_SRC_ASSET_GONE, e.code)
        }
        try {
            client().propfind("locked", 1)
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(WebDavClient.ERR_SRC_AUTH_FAILED, e.code)
        }
        try {
            client().propfind("boom", 1)
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(WebDavClient.ERR_SRC_UNAVAILABLE, e.code)
        }
    }

    @Test
    fun chunkedResponsesDecode() {
        val payload = "chunked-payload-bytes".toByteArray()
        directory("", listOf(Entry("c.jpg", size = payload.size.toLong(), bytes = payload)))
        markChunked("/photos/c.jpg")
        markChunked("/photos/")
        val entries = client().propfind("", depth = 1)
        assertEquals(true, entries.any { it.href == "/photos/c.jpg" })
        val response = client().openGet("c.jpg")
        assertArrayEquals(payload, response.stream.readBytes())
        assertNull(response.byteSize)
    }

    @Test
    fun plaintextNeverCarriesCredentials() {
        directory("", listOf(Entry("a.jpg", size = 2L, bytes = "aa".toByteArray())))
        try {
            client(credentials = "user" to "pass").openGet("a.jpg")
            fail("expected DavException")
        } catch (e: WebDavClient.DavException) {
            assertEquals(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED, e.code)
        }
        assertNull(lastAuthorization)
        val anonymous = client()
        anonymous.openGet("a.jpg").stream.use { it.readBytes() }
        assertNull(lastAuthorization)
    }
}
