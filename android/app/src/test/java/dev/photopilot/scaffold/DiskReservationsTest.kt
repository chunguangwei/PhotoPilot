package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.remote.DiskReservations
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DiskReservationsTest {
    @Test fun independentWritersCannotReserveTheSameFreeSpace() {
        val disk = DiskReservations()
        var free = 200L
        disk.reserve(100, { free }, 64).use { first ->
            assertThrows(IOException::class.java) { disk.reserve(100, { free }, 64) }
            first.write(50) { free -= 50 }
            assertThrows(IOException::class.java) { disk.reserve(80, { free }, 64) }
        }
        disk.reserve(80, { free }, 64).close()
    }
    @Test fun unknownAndUnderstatedLengthsAreCheckedBeforeEveryWrite() {
        for (reported in listOf(0L, 1L)) {
            val disk = DiskReservations()
            var written = false
            disk.reserve(reported, { 200 }, 64).use { claim ->
                assertThrows(IOException::class.java) { claim.write(137) { written = true } }
            }
            assertFalse(written)
        }
    }
    @Test fun otherKnownReservationsRemainProtectedDuringUnknownTransfer() {
        val disk = DiskReservations()
        disk.reserve(100, { 200 }, 64).use {
            disk.reserve(0, { 200 }, 64).use { unknown ->
                assertThrows(IOException::class.java) { unknown.write(50) { fail("write must not start") } }
            }
        }
    }
    @Test fun failedWriteAndCloseReleaseReservation() {
        val disk = DiskReservations()
        assertThrows(IOException::class.java) {
            disk.reserve(100, { 200 }, 64).use { claim -> claim.write(20) { throw IOException("fixture") } }
        }
        disk.reserve(100, { 200 }, 64).close()
    }
}
