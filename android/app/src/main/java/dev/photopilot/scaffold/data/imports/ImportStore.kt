package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.logic.ContentHash
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

interface ImportStore {

    data class Staged(
        val tempRelPath: String,
        val bytesWritten: Long,
        val contentHash: String,
        val head64kHash: String?,
    )

    fun stage(tempRelPath: String, source: InputStream, expectedBytes: Long?): Staged

    fun commitStaged(tempRelPath: String, targetRelPath: String)

    fun discardStaged(tempRelPath: String)

    fun deleteOriginal(relPath: String)

    fun existsOriginal(relPath: String): Boolean
}

class AssetTruncatedException : IOException("staged byte count mismatch")

class LocalImportStore(
    private val originalsDir: File,
    private val tmpDir: File,
) : ImportStore {

    init {
        originalsDir.mkdirs()
        tmpDir.mkdirs()
    }

    override fun stage(tempRelPath: String, source: InputStream, expectedBytes: Long?): ImportStore.Staged {
        val target = File(tmpDir, tempRelPath)
        target.parentFile?.mkdirs()
        val full = MessageDigest.getInstance("SHA-256")
        val head = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var headRemaining = ContentHash.HEAD64K_SIZE
        target.outputStream().use { out ->
            val buffer = ByteArray(16 * 1024)
            source.use { src ->
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    full.update(buffer, 0, read)
                    if (headRemaining > 0) {
                        val take = minOf(read, headRemaining)
                        head.update(buffer, 0, take)
                        headRemaining -= take
                    }
                    written += read.toLong()
                }
            }
            out.fd.sync()
        }
        if (expectedBytes != null && expectedBytes != written) {
            throw AssetTruncatedException()
        }
        val headHash = if (written > 0L) ContentHash.PREFIX + ContentHash.toHex(head.digest()) else null
        return ImportStore.Staged(
            tempRelPath = tempRelPath,
            bytesWritten = written,
            contentHash = ContentHash.PREFIX + ContentHash.toHex(full.digest()),
            head64kHash = headHash,
        )
    }

    override fun commitStaged(tempRelPath: String, targetRelPath: String) {
        val from = File(tmpDir, tempRelPath)
        val to = File(originalsDir, targetRelPath)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    override fun discardStaged(tempRelPath: String) {
        File(tmpDir, tempRelPath).delete()
    }

    override fun deleteOriginal(relPath: String) {
        File(originalsDir, relPath).delete()
    }

    override fun existsOriginal(relPath: String): Boolean = File(originalsDir, relPath).isFile
}
