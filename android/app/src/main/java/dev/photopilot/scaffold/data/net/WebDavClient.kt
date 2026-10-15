package dev.photopilot.scaffold.data.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class WebDavClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val tlsPolicy: NasTlsPolicy? = null,
    private val tlsContext: NasTlsPolicy.ConnectionContext? = null,
) {

    data class DavEntry(
        val href: String,
        val etag: String?,
        val size: Long?,
        val lastModified: String?,
        val isCollection: Boolean,
    )

    data class GetResponse(
        val stream: InputStream,
        val byteSize: Long?,
        val revision: String?,
    )

    class DavException(val code: String, cause: Throwable? = null) : IOException("dav: $code", cause)

    fun propfind(path: String, depth: Int): List<DavEntry> {
        val channel = openChannel(
            "PROPFIND",
            path,
            headers = mapOf(
                "Depth" to depth.toString(),
                "Content-Type" to "application/xml; charset=utf-8",
            ),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        )
        if (channel.status != HTTP_MULTISTATUS) {
            channel.closeQuietly()
            throw DavException(statusToCode(channel.status, pathLevel = true))
        }
        val body = channel.input.readBounded(channel.contentLength)
        channel.closeQuietly()
        return body.inputStream().use { parseMultistatus(it) }
    }

    fun openGet(path: String): GetResponse {
        val channel = openChannel("GET", path, emptyMap(), null)
        if (channel.status != HTTP_OK) {
            channel.closeQuietly()
            throw DavException(statusToCode(channel.status, pathLevel = false))
        }
        return GetResponse(
            stream = channel.input,
            byteSize = channel.contentLength,
            revision = channel.headers.entries
                .firstOrNull { it.key.equals("ETag", ignoreCase = true) }
                ?.value?.trim('"'),
        )
    }

    private class Channel(
        val socket: Socket,
        val status: Int,
        val headers: Map<String, String>,
        val input: InputStream,
        val contentLength: Long?,
    ) {
        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private fun openChannel(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Channel {
        val url = URL(baseUrl.trimEnd('/') + "/" + encodePath(path).removePrefix("/"))
        val host = url.host
        val port = if (url.port >= 0) url.port else url.defaultPort
        if (url.protocol == "http") {
            if (tlsPolicy != null) {
                tlsPolicy.cleartextError(host, port)?.let { throw DavException(it) }
            } else if (username.isNotEmpty() || password.isNotEmpty()) {
                throw DavException(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED)
            }
        }
        val socket = try {
            connect(url.protocol == "https", host, port)
        } catch (e: SSLException) {
            throw sslFailure(e, host, port)
        } catch (e: IOException) {
            throw DavException(ERR_SRC_UNAVAILABLE, e)
        }
        val request = buildString {
            val target = if (url.path.isEmpty()) "/" else encodePath(decodeHref(url.path))
            append("$method $target HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Connection: close\r\n")
            for ((key, value) in headers) {
                append("$key: $value\r\n")
            }
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val token = Base64.getEncoder()
                    .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                append("Authorization: Basic $token\r\n")
            }
            if (body != null) {
                append("Content-Length: ${body.size}\r\n")
            }
            append("\r\n")
        }
        try {
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            if (body != null) {
                socket.getOutputStream().write(body)
            }
            socket.getOutputStream().flush()
            return readChannel(socket)
        } catch (e: SSLException) {
            socket.close()
            throw sslFailure(e, host, port)
        } catch (e: IOException) {
            socket.close()
            throw DavException(ERR_SRC_UNAVAILABLE, e)
        }
    }

    private fun connect(https: Boolean, host: String, port: Int): Socket {
        val socket = if (https) {
            val factory = tlsContext?.socketFactory
                ?: SSLSocketFactory.getDefault() as SSLSocketFactory
            factory.createSocket()
        } else {
            Socket()
        }
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        if (socket is SSLSocket) {
            socket.startHandshake()
            val verifier = tlsContext?.hostnameVerifier
            if (verifier != null && !verifier.verify(host, socket.session)) {
                socket.close()
                throw SSLPeerUnverifiedException("hostname verification failed")
            }
        }
        return socket
    }

    private fun sslFailure(error: SSLException, host: String, port: Int): DavException {
        val classified = tlsContext?.lastError
            ?: tlsContext?.let { context ->
                tlsPolicy?.classify(error, host, port)?.also { context.noteError(it) }
            }
        return DavException(classified?.code ?: ERR_SRC_UNAVAILABLE, error)
    }

    private fun readChannel(socket: Socket): Channel {
        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = readLine(input) ?: throw DavException(ERR_SRC_UNAVAILABLE)
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: throw DavException(ERR_SRC_UNAVAILABLE)
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        val length = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toLongOrNull()
        val chunked = headers.entries
            .firstOrNull { it.key.equals("Transfer-Encoding", ignoreCase = true) }
            ?.value?.equals("chunked", ignoreCase = true) == true
        val raw: InputStream = if (chunked) {
            ChunkedInputStream(input, onClose = { runCatching { socket.close() } })
        } else {
            object : FilterInputStream(input) {
                var remaining = length ?: Long.MAX_VALUE
                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val value = super.read()
                    if (value >= 0) remaining--
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (remaining <= 0) return -1
                    val n = super.read(buffer, offset, minOf(count.toLong(), remaining).toInt())
                    if (n > 0) remaining -= n
                    return n
                }
                override fun close() {
                    runCatching { socket.close() }
                    super.close()
                }
            }
        }
        return Channel(socket, status, headers, raw, if (chunked) null else length)
    }

    private fun InputStream.readBounded(length: Long?): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var read = 0L
        while (length == null || read < length) {
            val want = if (length == null) {
                buffer.size
            } else {
                minOf(buffer.size.toLong(), length - read).toInt()
            }
            if (want <= 0) break
            val n = read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            read += n
        }
        return out.toByteArray()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }

    private fun statusToCode(status: Int, pathLevel: Boolean): String = when (status) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> ERR_SRC_AUTH_FAILED
        HTTP_NOT_FOUND -> if (pathLevel) ERR_SRC_PATH_NOT_FOUND else ERR_SRC_ASSET_GONE
        else -> ERR_SRC_UNAVAILABLE
    }

    private fun parseMultistatus(input: InputStream): List<DavEntry> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(input)
        val entries = mutableListOf<DavEntry>()
        val responses = document.getElementsByTagNameNS(DAV_NS, "response")
        for (i in 0 until responses.length) {
            val response = responses.item(i)
            val href = firstText(response, "href") ?: continue
            val collections = (response as org.w3c.dom.Element)
                .getElementsByTagNameNS(DAV_NS, "collection")
            entries += DavEntry(
                href = href,
                etag = firstText(response, "getetag")?.trim()?.trim('"'),
                size = firstText(response, "getcontentlength")?.trim()?.toLongOrNull(),
                lastModified = firstText(response, "getlastmodified")?.trim(),
                isCollection = collections.length > 0,
            )
        }
        return entries
    }

    private fun firstText(node: org.w3c.dom.Node, localName: String): String? {
        val matches = (node as org.w3c.dom.Element).getElementsByTagNameNS(DAV_NS, localName)
        if (matches.length == 0) return null
        return matches.item(0)?.textContent
    }

    companion object {
        const val HTTP_MULTISTATUS = 207
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val DAV_NS = "DAV:"

        const val ERR_SRC_AUTH_FAILED = "SRC_AUTH_FAILED"
        const val ERR_SRC_PATH_NOT_FOUND = "SRC_PATH_NOT_FOUND"
        const val ERR_SRC_ASSET_GONE = "SRC_ASSET_GONE"
        const val ERR_SRC_UNAVAILABLE = "SRC_UNAVAILABLE"

        const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<propfind xmlns=\"DAV:\"><prop><getetag/><getcontentlength/>" +
                "<getlastmodified/><resourcetype/></prop></propfind>"

        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { pctEncode(it) }

        fun decodeHref(href: String): String = pctDecode(href)

        private fun pctEncode(segment: String): String {
            val out = StringBuilder()
            for (byte in segment.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '.' || ch == '_' || ch == '~'
                ) {
                    out.append(ch)
                } else {
                    out.append('%')
                    out.append("%02X".format(c))
                }
            }
            return out.toString()
        }

        private fun pctDecode(text: String): String {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                if (text[i] == '%' && i + 2 < text.length) {
                    val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(text[i].code)
                i++
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
