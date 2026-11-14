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
    private val disk: dev.photopilot.scaffold.data.remote.DiskReservations = dev.photopilot.scaffold.data.remote.DiskReservations.shared,
    private val freeSpace: () -> Long = { originalsDir.usableSpace },
    private val minimumFreeBytes: Long = 67108864,
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
        try {
            source.use { input ->
                if (target.exists() && !target.delete()) throw IOException("staging cleanup failed")
                disk.reserve(expectedBytes ?: 0, freeSpace, minimumFreeBytes).use { claim ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            claim.write(count) { output.write(buffer, 0, count) }
                            full.update(buffer, 0, count)
                            if (headRemaining > 0) {
                                val take = minOf(count, headRemaining)
                                head.update(buffer, 0, take)
                                headRemaining -= take
                            }
                            written += count
                        }
                        output.fd.sync()
                    }
                }
            }
            if (expectedBytes != null && expectedBytes != written) throw AssetTruncatedException()
            val headHash = if (written > 0) ContentHash.PREFIX + ContentHash.toHex(head.digest()) else null
            return ImportStore.Staged(tempRelPath, written, ContentHash.PREFIX + ContentHash.toHex(full.digest()), headHash)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    override fun commitStaged(tempRelPath: String, targetRelPath: String) {
        val from = File(tmpDir, tempRelPath)
        val to = File(originalsDir, targetRelPath)
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            val pending = File(to.parentFile, ".${to.name}.${java.util.UUID.randomUUID()}.part")
            try {
                disk.reserve(from.length(), { to.parentFile!!.usableSpace }, minimumFreeBytes).use { claim ->
                    from.inputStream().use { input -> pending.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            claim.write(count) { output.write(buffer, 0, count) }
                        }
                        output.fd.sync()
                    } }
                }
                if (!pending.renameTo(to)) throw IOException("original commit failed")
                from.delete()
            } finally { pending.delete() }
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
