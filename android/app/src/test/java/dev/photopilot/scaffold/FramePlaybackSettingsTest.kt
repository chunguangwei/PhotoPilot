package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.repo.FrameManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePlaybackSettingsTest {

    @Test
    fun parsesContractTemplate() {
        val settings = FramePlaybackSettings.parse(FrameManager.DEFAULT_SETTINGS_JSON)
        assertEquals(10, settings.holdSeconds)
        assertEquals("crossfade", settings.transition)
        assertEquals(0.8, settings.transitionSeconds, 1e-9)
        assertEquals(FramePlaybackSettings.FIT_BLUR, settings.fitMode)
        assertTrue(settings.clockEnabled)
        assertNull(settings.clockHour24)
        assertTrue(settings.dateEnabled)
        assertTrue(settings.keepAwakeWhenPaused)
        assertFalse(settings.restEnabled)
        assertNull(settings.restStartMinute)
        assertNull(settings.brightness)
    }

    @Test
    fun malformedJsonFallsBackToDefaults() {
        val settings = FramePlaybackSettings.parse("{not json")
        assertEquals(10, settings.holdSeconds)
        assertEquals("crossfade", settings.transition)
        val nullJson = FramePlaybackSettings.parse(null)
        assertEquals(FramePlaybackSettings.FIT_BLUR, nullJson.fitMode)
    }

    @Test
    fun restWindowMidnightCrossingFollowsTimingRules() {
        val settings = FramePlaybackSettings(
            restEnabled = true,
            restStartMinute = 23 * 60,
            restEndMinute = 6 * 60,
        )
        assertTrue(settings.restActiveAt(23 * 60 + 30, overrideActive = false))
        assertTrue(settings.restActiveAt(2 * 60, overrideActive = false))
        assertFalse(settings.restActiveAt(12 * 60, overrideActive = false))
        assertFalse(settings.restActiveAt(2 * 60, overrideActive = true))
    }

    @Test
    fun customValuesParseThrough() {
        val json = "{\"holdSeconds\":30,\"transition\":\"none\",\"transitionSeconds\":0.2," +
            "\"fitMode\":\"fill_crop\",\"keepAwakeWhenPaused\":false," +
            "\"clock\":{\"enabled\":false,\"hour24\":true},\"date\":{\"enabled\":false}," +
            "\"rest\":{\"enabled\":true,\"startMinute\":120,\"endMinute\":300}," +
            "\"brightness\":0.5}"
        val settings = FramePlaybackSettings.parse(json)
        assertEquals(30, settings.holdSeconds)
        assertEquals("none", settings.transition)
        assertEquals(FramePlaybackSettings.FILL_CROP, settings.fitMode)
        assertFalse(settings.clockEnabled)
        assertEquals(true, settings.clockHour24)
        assertFalse(settings.dateEnabled)
        assertTrue(settings.restEnabled)
        assertEquals(120, settings.restStartMinute)
        assertEquals(300, settings.restEndMinute)
        assertEquals(0.5f, settings.brightness)
    }
}
