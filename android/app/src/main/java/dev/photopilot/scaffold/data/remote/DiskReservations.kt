package dev.photopilot.scaffold.data.remote

import java.io.Closeable
import java.io.IOException

class DiskReservations {
    private val lock = Any()
    private val claims = mutableSetOf<Reservation>()

    fun reserve(expectedBytes: Long, freeSpace: () -> Long, minimumFreeBytes: Long = 67108864): Reservation = synchronized(lock) {
        val reservation = Reservation(expectedBytes.coerceAtLeast(0), freeSpace, minimumFreeBytes)
        checkSpace(reservation, 0)
        claims.add(reservation)
        reservation
    }

    private fun checkSpace(current: Reservation, nextWriteBytes: Int) {
        var otherBytes = 0L
        var minimumFree = current.minimumFreeBytes
        for (claim in claims) {
            minimumFree = maxOf(minimumFree, claim.minimumFreeBytes)
            if (claim === current) continue
            if (claim.remainingBytes > Long.MAX_VALUE - otherBytes) throw IOException("REMOTE_LOW_SPACE")
            otherBytes += claim.remainingBytes
        }
        val available = current.freeSpace().coerceAtLeast(0)
        val required = maxOf(current.remainingBytes, nextWriteBytes.toLong())
        if (otherBytes > available || required > available - otherBytes || available - otherBytes - required < minimumFree) {
            throw IOException("REMOTE_LOW_SPACE")
        }
    }

    inner class Reservation internal constructor(
        private val expectedBytes: Long,
        internal val freeSpace: () -> Long,
        internal val minimumFreeBytes: Long,
    ) : Closeable {
        private var writtenBytes = 0L
        private var closed = false
        internal val remainingBytes: Long get() = (expectedBytes - writtenBytes).coerceAtLeast(0)

        fun write(byteCount: Int, writeBytes: () -> Unit) = synchronized(lock) {
            if (closed) throw IOException("REMOTE_WRITE_CANCELLED")
            require(byteCount >= 0)
            checkSpace(this, byteCount)
            writeBytes()
            writtenBytes += byteCount
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                claims.remove(this)
            }
            Unit
        }
    }

    companion object {
        val shared = DiskReservations()
    }
}
