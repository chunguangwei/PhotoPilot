package dev.photopilot.scaffold.data.remote

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.*

class RemoteFileCache(
    private val directory: File,
    private val budget: Long = 134217728,
    private val reserve: Long = 67108864,
    private val freeSpace: () -> Long = { directory.usableSpace },
    private val disk: DiskReservations = DiskReservations.shared,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val held = mutableMapOf<String, Int>()
    private val retiredHeld = mutableSetOf<String>()
    private val reservations = mutableMapOf<String, Long>()
    private val streams = mutableMapOf<String, InputStream>()
    private val activeParts = mutableMapOf<String, File>()
    private val inflight = mutableMapOf<String, Pending>()
    private var generation = 0L
    private data class Pending(val result: Deferred<File>, var clients: Int, val token: String, val generation: Long)

    class Lease(val file: File, private val release: () -> Unit) : Closeable {
        private var closed = false
        override fun close() = synchronized(this) {
            if (!closed) { closed = true; release() }
        }
    }

    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun unreservedFiles(): List<File> = directory.listFiles().orEmpty().filter { it !in activeParts.values }
    fun bytesUsed(): Long = synchronized(lock) { unreservedFiles().sumOf { it.length() } + reservations.values.sum() }
    private fun filename(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun release(name: String) = synchronized(lock) {
        val count = held.getOrDefault(name, 1) - 1
        if (count > 0) held[name] = count
        else {
            held.remove(name)
            if (retiredHeld.remove(name)) File(directory, name).delete()
        }
    }

    fun readIfPresent(key: String): File? = File(directory, filename(key)).takeIf { it.isFile }

    fun storeBytes(key: String, data: ByteArray): Boolean = synchronized(lock) {
        val name = filename(key)
        val target = File(directory, name)
        try {
            if (target.isFile) {
                target.setLastModified(System.currentTimeMillis())
                return true
            }
            if (!directory.isDirectory && !directory.mkdirs()) return false
            room(name, "store:$name", data.size.toLong())
            target.writeBytes(data)
            reservations.remove("store:$name")
            true
        } catch (_: Throwable) {
            reservations.remove("store:$name")
            false
        }
    }

    suspend fun acquire(key: String, expectedSize: Long?, valid: () -> Boolean, open: () -> InputStream): Lease {
        val name = filename(key)
        val pending = synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("REMOTE_CACHE_WRITE")
            if (!valid()) throw IOException("REMOTE_REVOKED")
            if (name in retiredHeld) throw IOException("REMOTE_CACHE_BUSY")
            val installed = File(directory, name)
            if (installed.isFile) {
                installed.setLastModified(System.currentTimeMillis())
                held[name] = held.getOrDefault(name, 0) + 1
                return Lease(installed) { release(name) }
            }
            inflight[name]?.takeIf { it.generation == generation && !it.result.isCancelled }?.also { it.clients++ }
                ?: run {
                    val epoch = generation
                    val token = java.util.UUID.randomUUID().toString()
                    val result = scope.async(start = CoroutineStart.LAZY) { download(name, token, expectedSize, epoch, valid, open) }
                    Pending(result, 1, token, epoch).also { inflight[name] = it; result.start() }
                }
        }
        try {
            val file = pending.result.await()
            synchronized(lock) {
                if (pending.generation != generation || !valid() || !file.isFile) throw IOException("REMOTE_REVOKED")
                held[name] = held.getOrDefault(name, 0) + 1
            }
            return Lease(file) { release(name) }
        } finally {
            val abandoned = synchronized(lock) {
                pending.clients--
                if (pending.clients == 0 && inflight[name] === pending) {
                    inflight.remove(name)
                    if (!pending.result.isCompleted) {
                        pending.result.cancel()
                        streams[pending.token]
                    } else null
                } else null
            }
            abandoned?.let { input -> scope.launch { runCatching { input.close() } } }
        }
    }

    private fun room(name: String, token: String, bytes: Long) {
        if (bytes > budget) throw IOException("REMOTE_FILE_TOO_LARGE")
        val otherReservations = reservations.filterKeys { it != token }.values.sum()
        val files = unreservedFiles().sortedBy { it.lastModified() }
        var used = files.sumOf { it.length() }
        for (file in files) {
            if (used + otherReservations + bytes <= budget) break
            if (held.getOrDefault(file.name, 0) == 0 && !inflight.containsKey(file.name)) {
                val size = file.length()
                if (file.delete()) used -= size
            }
        }
        if (used + otherReservations + bytes > budget) throw IOException("REMOTE_CACHE_BUSY")
        reservations[token] = bytes
    }

    private suspend fun download(name: String, token: String, expectedSize: Long?, epoch: Long, valid: () -> Boolean, open: () -> InputStream): File {
        val partial = File(directory, "$name.$token.part")
        val installed = File(directory, name)
        var diskReservation: DiskReservations.Reservation? = null
        try {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                activeParts[token] = partial
                room(name, token, expectedSize?.coerceAtLeast(0) ?: 0)
            }
            val claim = disk.reserve(expectedSize ?: 0, freeSpace, reserve)
            diskReservation = claim
            currentCoroutineContext().ensureActive()
            open().use { input ->
                synchronized(lock) {
                    if (generation != epoch) throw IOException("REMOTE_REVOKED")
                    streams[token] = input
                }
                partial.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (!valid()) throw IOException("REMOTE_REVOKED")
                        val count = input.read(buffer)
                        if (count < 0) break
                        currentCoroutineContext().ensureActive()
                        synchronized(lock) {
                            if (generation != epoch) throw IOException("REMOTE_REVOKED")
                            room(name, token, maxOf(expectedSize ?: 0, written + count))
                        }
                        claim.write(count) { output.write(buffer, 0, count) }
                        written += count
                    }
                    output.fd.sync()
                }
            }
            synchronized(lock) {
                if (generation != epoch || !valid()) throw IOException("REMOTE_REVOKED")
                if (!partial.renameTo(installed)) throw IOException("REMOTE_CACHE_WRITE")
            }
            return installed
        } finally {
            diskReservation?.close()
            synchronized(lock) {
                partial.delete()
                activeParts.remove(token)
                reservations.remove(token)
                streams.remove(token)
            }
        }
    }

    fun clear(force: Boolean = false) {
        val inputs = synchronized(lock) {
            generation++
            inflight.values.forEach { it.result.cancel() }
            for (file in directory.listFiles().orEmpty()) {
                if (file in activeParts.values) continue
                if (held.getOrDefault(file.name, 0) > 0) {
                    if (force) retiredHeld.add(file.name)
                } else file.delete()
            }
            streams.values.toList()
        }
        inputs.forEach { input -> scope.launch { runCatching { input.close() } } }
    }
}
