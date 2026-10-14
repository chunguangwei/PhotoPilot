package dev.photopilot.scaffold.data.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal class ChunkedInputStream(
    private val source: BufferedInputStream,
    private val onClose: () -> Unit,
) : InputStream() {

    private var remainingInChunk = 0
    private var finished = false
    private val single = ByteArray(1)

    override fun read(): Int {
        val n = read(single, 0, 1)
        return if (n < 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        while (!finished && remainingInChunk <= 0) {
            val sizeLine = readLine(source) ?: run {
                finished = true
                return -1
            }
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: 0
            if (size == 0) {
                while (true) {
                    val trailer = readLine(source) ?: break
                    if (trailer.isEmpty()) break
                }
                finished = true
                return -1
            }
            remainingInChunk = size
        }
        if (finished) return -1
        val n = source.read(buffer, offset, minOf(count, remainingInChunk))
        if (n < 0) {
            finished = true
            return -1
        }
        remainingInChunk -= n
        if (remainingInChunk == 0) {
            readLine(source)
        }
        return n
    }

    override fun close() {
        finished = true
        runCatching { onClose() }
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
