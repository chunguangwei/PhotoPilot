package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.WebDavSourceAdapter
import dev.photopilot.scaffold.data.net.WebDavClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavSourceAdapterFixtureTest : DavFixtureServer() {

    private val fixtureTime = "Wed, 21 Oct 2015 07:28:00 GMT"

    private fun adapter(pageSize: Int, recursive: Boolean = true) = WebDavSourceAdapter(
        client = WebDavClient(baseUrl = baseUrl()),
        rootPath = "photos",
        sourceFingerprint = "webdav://127.0.0.1:$port:445/fixture",
        pageSize = pageSize,
        recursive = recursive,
    )

    private fun fixtureTree() {
        directory(
            "",
            listOf(
                Entry("sub", isCollection = true),
                Entry("empty", isCollection = true),
                Entry("a.jpg", etag = "etag-a", size = 5L, lastModified = fixtureTime, bytes = "AAAAA".toByteArray()),
                Entry("b.png", size = 7L, lastModified = fixtureTime, bytes = "BBBBBBB".toByteArray()),
                Entry("d.txt", size = 1L, bytes = "x".toByteArray()),
            ),
        )
        directory("sub", listOf(Entry("c.jpg", etag = "etag-c", size = 3L, bytes = "CCC".toByteArray())))
        directory("empty", emptyList())
    }

    @Test
    fun enumeratePagesRecursivelyWithKeysetCursor() = runBlocking {
        fixtureTree()
        val first = adapter(pageSize = 2).enumerate("{\"directories\":[\"\"]}", null)
        assertEquals(listOf("a.jpg", "b.png"), first.items.map { it.remoteKey })
        assertEquals("etag-a", first.items[0].revision)
        val expectedMs = SimpleDateFormat(WebDavSourceAdapter.HTTP_DATE_PATTERN, Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(fixtureTime)!!.time
        assertEquals("$expectedMs:7", first.items[1].revision)
        assertNull(first.totalKnown)
        assertNotNull(first.nextCursor)

        val second = adapter(pageSize = 2).enumerate("{\"directories\":[\"\"]}", first.nextCursor)
        assertEquals(listOf("sub/c.jpg"), second.items.map { it.remoteKey })
        assertNull(second.nextCursor)
        Unit
    }

    @Test
    fun openAssetStreamsOriginalBytes() = runBlocking {
        fixtureTree()
        val bytes = adapter(2).openAsset("sub/c.jpg")
        assertArrayEquals("CCC".toByteArray(), bytes.readBytes())
        Unit
    }

    @Test
    fun nonMediaExtensionsAreFiltered() = runBlocking {
        fixtureTree()
        val first = adapter(10).enumerate("{\"directories\":[\"\"]}", null)
        assertTrue(first.items.none { it.remoteKey == "d.txt" })
        Unit
    }

    @Test
    fun nonRecursiveEnumeratesOnlyTopLevelFiles() = runBlocking {
        fixtureTree()
        val first = adapter(10, recursive = false).enumerate("{\"directories\":[\"\"]}", null)
        assertEquals(listOf("a.jpg", "b.png"), first.items.map { it.remoteKey })
        assertNull(first.nextCursor)
        Unit
    }
}
