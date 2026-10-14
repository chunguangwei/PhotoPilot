package dev.photopilot.scaffold.data.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object SimpleHttpsGet {

    data class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000

    fun get(urlText: String): Response {
        val url = URL(urlText)
        val plain = url.protocol == "http"
        val socket = if (plain) {
            java.net.Socket()
        } else {
            SSLSocketFactory.getDefault().createSocket()
        }
        try {
            val defaultPort = if (plain) 80 else 443
            socket.connect(
                InetSocketAddress(url.host, url.port.takeIf { it >= 0 } ?: defaultPort),
                CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = READ_TIMEOUT_MS
            if (socket is SSLSocket) {
                socket.startHandshake()
            }
            val target = if (url.path.isNullOrEmpty()) "/${url.query?.let { "?$it" } ?: ""}" else url.path + (url.query?.let { "?$it" } ?: "")
            val request = buildString {
                append("GET $target HTTP/1.1\r\n")
                append("Host: ${url.host}\r\n")
                append("Connection: close\r\n")
                append("Accept: application/json\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input) ?: throw IOException("no status line")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status")
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
            val body = ByteArrayOutputStream()
            val stream: InputStream = if (chunked) {
                BufferedInputStream(
                    ChunkedInputStream(input, onClose = { runCatching { socket.close() } }),
                )
            } else {
                input
            }
            val buffer = ByteArray(16 * 1024)
            var read = 0L
            while (length == null || read < length) {
                val want = if (length == null) buffer.size else minOf(buffer.size.toLong(), length - read).toInt()
                if (want <= 0) break
                val n = stream.read(buffer, 0, want)
                if (n < 0) break
                body.write(buffer, 0, n)
                read += n
            }
            return Response(status, headers, body.toByteArray())
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString(Charsets.ISO_8859_1.name())
    }
}
