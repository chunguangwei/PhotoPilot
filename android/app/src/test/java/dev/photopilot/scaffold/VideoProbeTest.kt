package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.VideoProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProbeTest {

    @Test
    fun rotationMapsToExifOrientation() {
        assertEquals(1L, VideoProbe.rotationToExif(0L))
        assertEquals(6L, VideoProbe.rotationToExif(90L))
        assertEquals(3L, VideoProbe.rotationToExif(180L))
        assertEquals(8L, VideoProbe.rotationToExif(270L))
        assertEquals(1L, VideoProbe.rotationToExif(45L))
        assertEquals(1L, VideoProbe.rotationToExif(-90L))
    }

    @Test
    fun extensionSetsFollowFrozenScope() {
        assertTrue(VideoProbe.isVideoExtension("mp4"))
        assertTrue(VideoProbe.isVideoExtension("MOV"))
        assertFalse(VideoProbe.isVideoExtension("avi"))
        assertFalse(VideoProbe.isVideoExtension("mkv"))
        assertTrue(VideoProbe.isRawExtension("dng"))
        assertTrue(VideoProbe.isRawExtension("CR2"))
        assertFalse(VideoProbe.isRawExtension("jpg"))
    }

    @Test
    fun durationLimitAtTenMinutes() {
        assertFalse(VideoProbe.isOverLimit(600_000L))
        assertTrue(VideoProbe.isOverLimit(600_001L))
        assertFalse(VideoProbe.isOverLimit(null))
    }

    @Test
    fun containerDateParsesOrNulls() {
        val parsed = VideoProbe.parseContainerDate("20230101T120000.000Z")
        assertEquals(1_672_574_400_000L, parsed)
        assertNull(VideoProbe.parseContainerDate(null))
        assertNull(VideoProbe.parseContainerDate("not-a-date"))
    }
}
