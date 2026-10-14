package dev.photopilot.scaffold

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

class RawFixtureServer(
    sslContext: SSLContext? = null,
    private val handler: (RawRequest) -> RawResponse,
) {

    data class RawRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class RawResponse(
        val status: Int,
        val reason: String,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val chunked: Boolean = false,
    )

    private val socket: ServerSocket = if (sslContext != null) {
        sslContext.serverSocketFactory.createServerSocket()
    } else {
        ServerSocket()
    }
    private var running = true
    private var acceptThread: Thread? = null

    val port: Int
        get() = socket.localPort

    fun start() {
        socket.bind(InetSocketAddress("127.0.0.1", 0))
        acceptThread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { serve(client) }.start()
            }
        }
        acceptThread?.start()
    }

    fun stop() {
        running = false
        socket.close()
        acceptThread?.join(2_000)
    }

    private fun serve(client: Socket) {
        client.use {
            val input = BufferedInputStream(client.getInputStream())
            val request = readRequest(input) ?: return
            val response = handler(request)
            val output = client.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
                if (response.chunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: ${response.body.size}\r\n")
                }
                append("Connection: close\r\n")
                for ((key, value) in response.headers) {
                    append("$key: $value\r\n")
                }
                append("\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (response.chunked) {
                var offset = 0
                while (offset < response.body.size) {
                    val size = minOf(5, response.body.size - offset)
                    output.write("$size\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write(response.body, offset, size)
                    output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    offset += size
                }
                output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            } else {
                output.write(response.body)
            }
            output.flush()
        }
    }

    private fun readRequest(input: BufferedInputStream): RawRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        val length = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return RawRequest(parts[0], parts[1], headers, body)
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
}
