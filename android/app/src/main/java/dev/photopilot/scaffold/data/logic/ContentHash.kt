package dev.photopilot.scaffold.data.logic

import java.io.InputStream
import java.security.MessageDigest

object ContentHash {

    const val PREFIX = "sha256:"
    const val HEAD64K_SIZE = 64 * 1024

    private const val HEX = "0123456789abcdef"

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return PREFIX + toHex(digest)
    }

    fun toHex(digest: ByteArray): String {
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            out.append(HEX[(b.toInt() shr 4) and 0x0f])
            out.append(HEX[b.toInt() and 0x0f])
        }
        return out.toString()
    }

    data class Streamed(
        val contentHash: String,
        val head64kHash: String?,
        val bytesWritten: Long,
    )

    fun stream(input: InputStream, headSize: Int = HEAD64K_SIZE): Streamed {
        val full = MessageDigest.getInstance("SHA-256")
        val head = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var headRemaining = headSize
        val buffer = ByteArray(16 * 1024)
        input.use { src ->
            while (true) {
                val read = src.read(buffer)
                if (read < 0) break
                full.update(buffer, 0, read)
                if (headRemaining > 0) {
                    val take = minOf(read, headRemaining)
                    head.update(buffer, 0, take)
                    headRemaining -= take
                }
                total += read.toLong()
            }
        }
        val headHash = if (total > 0L) PREFIX + toHex(head.digest()) else null
        return Streamed(PREFIX + toHex(full.digest()), headHash, total)
    }
}
