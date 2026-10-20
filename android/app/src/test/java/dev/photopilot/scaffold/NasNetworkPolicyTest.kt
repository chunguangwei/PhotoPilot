package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.NasNetworkPolicy
import dev.photopilot.scaffold.data.imports.NasNetworkBlockedException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class NasNetworkPolicyTest {
    @Test fun disconnectedAlwaysBlocks() {
        assertFalse(NasNetworkPolicy.allows(false, true, false))
    }
    @Test fun cellularRequiresPreference() {
        assertFalse(NasNetworkPolicy.allows(true, false, true))
        assertTrue(NasNetworkPolicy.allows(true, false, false))
        assertTrue(NasNetworkPolicy.allows(true, true, true))
    }
    @Test fun switchingDuringReadDiscardsChunkAndClosesStream() {
        var allowed = true
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                return super.read(b, off, len).also { allowed = false }
            }
            override fun close() { closed = true; super.close() }
        }
        try {
            NasNetworkPolicy.guardedStream(input) { allowed }.use { it.read(ByteArray(3)) }
            fail("network switch must reject the downloaded chunk")
        } catch (_: NasNetworkBlockedException) {
            assertTrue(closed)
        }
    }
}
