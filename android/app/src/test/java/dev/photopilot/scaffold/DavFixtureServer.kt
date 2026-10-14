package dev.photopilot.scaffold

import org.junit.After
import org.junit.Before

open class DavFixtureServer {

    data class Entry(
        val name: String,
        val isCollection: Boolean = false,
        val etag: String? = null,
        val size: Long? = null,
        val lastModified: String? = null,
        val bytes: ByteArray? = null,
    )

    private val directories = mutableMapOf<String, List<Entry>>()
    private val statusOverrides = mutableMapOf<String, Int>()
    private val chunkedPaths = mutableSetOf<String>()
    var lastAuthorization: String? = null
        private set

    protected lateinit var server: RawFixtureServer

    val port: Int
        get() = server.port

    fun directory(path: String, entries: List<Entry>) {
        directories[path] = entries
    }

    fun statusOverride(path: String, status: Int) {
        statusOverrides[path] = status
    }

    fun markChunked(path: String) {
        chunkedPaths += path
    }

    @Before
    fun start() {
        server = RawFixtureServer { request -> handle(request) }
        server.start()
    }

    @After
    fun stop() {
        server.stop()
    }

    fun baseUrl(): String = "http://127.0.0.1:$port/photos"

    private fun handle(request: RawFixtureServer.RawRequest): RawFixtureServer.RawResponse {
        lastAuthorization = request.headers.entries
            .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
            ?.value
        val path = request.path
        val overrideStatus = statusOverrides[path]
        if (overrideStatus != null) {
            return RawFixtureServer.RawResponse(overrideStatus, "Override")
        }
        return when (request.method) {
            "PROPFIND" -> handlePropfind(path)
            "GET" -> handleGet(path)
            else -> RawFixtureServer.RawResponse(405, "Method Not Allowed")
        }
    }

    private fun handlePropfind(path: String): RawFixtureServer.RawResponse {
        val normalized = path.removePrefix("/photos").removePrefix("/")
        val entries = directories[normalized]
            ?: return RawFixtureServer.RawResponse(404, "Not Found")
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<multistatus xmlns=\"DAV:\">")
            append("<response><href>/photos/$normalized</href>")
            append("<propstat><prop><resourcetype><collection/></resourcetype></prop></propstat></response>")
            for (entry in entries) {
                val slash = if (normalized.isEmpty()) "" else "/"
                val href = "/photos/$normalized$slash${entry.name}"
                append("<response><href>$href</href><propstat><prop>")
                entry.etag?.let { append("<getetag>\"$it\"</getetag>") }
                entry.size?.let { append("<getcontentlength>$it</getcontentlength>") }
                entry.lastModified?.let { append("<getlastmodified>$it</getlastmodified>") }
                if (entry.isCollection) {
                    append("<resourcetype><collection/></resourcetype>")
                } else {
                    append("<resourcetype/>")
                }
                append("</prop></propstat></response>")
            }
            append("</multistatus>")
        }.toByteArray(Charsets.UTF_8)
        return RawFixtureServer.RawResponse(
            207,
            "Multi-Status",
            headers = mapOf("Content-Type" to "application/xml; charset=utf-8"),
            body = body,
            chunked = path in chunkedPaths,
        )
    }

    private fun handleGet(path: String): RawFixtureServer.RawResponse {
        val normalized = path.removePrefix("/photos").removePrefix("/")
        val parent = normalized.substringBeforeLast('/', "")
        val name = normalized.substringAfterLast('/')
        val entry = directories[parent]?.firstOrNull { it.name == name && !it.isCollection }
        val bytes = entry?.bytes ?: return RawFixtureServer.RawResponse(404, "Not Found")
        val headers = mutableMapOf<String, String>()
        entry.etag?.let { headers["ETag"] = "\"$it\"" }
        return RawFixtureServer.RawResponse(200, "OK", headers = headers, body = bytes, chunked = path in chunkedPaths)
    }
}
