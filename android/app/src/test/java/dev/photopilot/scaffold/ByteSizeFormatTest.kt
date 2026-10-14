package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ByteSizeFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteSizeFormatTest {

    @Test
    fun belowOneMbRendersKbCeiling() {
        assertEquals(ByteSizeFormat.Unit.KB, ByteSizeFormat.unitOf(0L))
        assertEquals(1L, ByteSizeFormat.valueOf(0L))
        assertEquals(1L, ByteSizeFormat.valueOf(500L))
        assertEquals(2L, ByteSizeFormat.valueOf(1_500L))
        assertEquals(1_024L, ByteSizeFormat.valueOf(1_048_575L))
    }

    @Test
    fun mbRangeUsesWholeMegabytes() {
        assertEquals(ByteSizeFormat.Unit.MB, ByteSizeFormat.unitOf(1_048_576L))
        assertEquals(1L, ByteSizeFormat.valueOf(1_048_576L))
        assertEquals(1_023L, ByteSizeFormat.valueOf(1_073_741_823L))
    }

    @Test
    fun gbBoundary() {
        assertEquals(ByteSizeFormat.Unit.GB, ByteSizeFormat.unitOf(1_073_741_824L))
        assertEquals(1L, ByteSizeFormat.valueOf(1_073_741_824L))
        assertEquals(2L, ByteSizeFormat.valueOf(2L * 1_073_741_824L))
    }
}
