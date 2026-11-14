package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.WebDavSourceAdapter
import dev.photopilot.scaffold.data.net.WebDavClient
import dev.photopilot.scaffold.data.remote.RemoteFileCache
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteWebDavTest {
    @Test fun enumerationUsesOnlyPropfindAndChunkedGetRespectsBudget() = runBlocking {
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/photo.png</d:href><d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength><d:getetag>v1</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val server = RawFixtureServer { request ->
            methods.add(request.method)
            if (request.method == "PROPFIND") RawFixtureServer.RawResponse(207, "Multi-Status", body = xml.toByteArray())
            else RawFixtureServer.RawResponse(200, "OK", body = ByteArray(17), chunked = true)
        }
        val dir = Files.createTempDirectory("remote-dav").toFile()
        server.start()
        try {
            val adapter = WebDavSourceAdapter(WebDavClient("http://127.0.0.1:${server.port}"), "", "fixture")
            val page = adapter.enumerate("{\"directories\":[\"\"]}", null)
            assertEquals(1, page.items.size)
            assertEquals(listOf("PROPFIND"), methods.toList())
            val cache = RemoteFileCache(dir, 16, 0, { 1000 })
            for (reported in listOf(page.items.single().byteSize, null)) {
                assertThrows(java.io.IOException::class.java) { runBlocking { cache.acquire("fixture-$reported", reported, { true }) { adapter.openAsset(page.items.single().remoteKey) } } }
                assertEquals(0L, cache.bytesUsed())
            }
            assertEquals(listOf("PROPFIND", "GET", "GET"), methods.toList())
        } finally { server.stop(); dir.deleteRecursively() }
    }
}
