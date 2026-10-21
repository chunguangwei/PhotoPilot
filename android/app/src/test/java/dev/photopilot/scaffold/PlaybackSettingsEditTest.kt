package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.PlaybackSettingsEdit
import dev.photopilot.scaffold.data.repo.FrameManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsEditTest {

    private fun draft(
        holdSeconds: Int = 15,
        transition: String = "slide",
        restEnabled: Boolean = false,
        restStart: Int? = null,
        restEnd: Int? = null,
    ) = PlaybackSettingsEdit.Draft(
        holdSeconds = holdSeconds,
        transition = transition,
        transitionSeconds = 1.2,
        fitMode = FramePlaybackSettings.FIT_COLOR,
        clockEnabled = false,
        clockPosition = FramePlaybackSettings.POSITION_TOP_TRAILING,
        clockHour24 = true,
        dateEnabled = false,
        keepAwakeWhenPaused = false,
        restEnabled = restEnabled,
        restStartMinute = restStart,
        restEndMinute = restEnd,
        brightness = 0.4,
        qualityLevel = FramePlaybackSettings.QUALITY_LOW,
    )

    @Test
    fun savedBrightnessRestoresEveryWholePercent() {
        for (percent in 0..100) {
            val parsed = FramePlaybackSettings.parse("{\"brightness\":${percent / 100.0}}")
            assertEquals("saved $percent percent", percent, PlaybackSettingsEdit.brightnessPercent(parsed.brightness))
        }
    }

    @Test
    fun mergePreservesUnknownTopAndNestedFields() {
        val original = "{\"holdSeconds\":10,\"futureField\":42," +
            "\"clock\":{\"enabled\":true,\"futureFlag\":\"keep\"},\"weather\":{\"enabled\":true}}"
        val merged = JsonMini.parseObject(PlaybackSettingsEdit.merge(original, draft()))
        assertEquals(42.0, merged["futureField"])
        val clock = merged["clock"] as Map<*, *>
        assertEquals("keep", clock["futureFlag"])
        assertEquals(false, clock["enabled"])
        assertEquals(FramePlaybackSettings.POSITION_TOP_TRAILING, clock["position"])
        val weather = merged["weather"] as Map<*, *>
        assertEquals(true, weather["enabled"])
    }

    @Test
    fun mergePreservesUnknownRestFieldsAndWritesDisplay() {
        val original = "{\"rest\":{\"enabled\":false,\"futureRestKey\":\"keep\",\"display\":\"dim\"}}"
        val value = draft().copy(restDisplay = FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER)
        val merged = JsonMini.parseObject(PlaybackSettingsEdit.merge(original, value))
        val rest = merged["rest"] as Map<*, *>
        assertEquals("keep", rest["futureRestKey"])
        assertEquals(FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER, rest["display"])
        val reparsed = FramePlaybackSettings.parse(PlaybackSettingsEdit.merge(original, value))
        assertEquals(FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER, reparsed.restDisplay)
    }

    @Test
    fun mergeDefaultsRestDisplayToClock() {
        val merged = JsonMini.parseObject(PlaybackSettingsEdit.merge(null, draft()))
        val rest = merged["rest"] as Map<*, *>
        assertEquals(FramePlaybackSettings.REST_DISPLAY_CLOCK, rest["display"])
    }

    @Test
    fun mergeRoundTripsThroughParser() {
        val value = draft(restEnabled = true, restStart = 120, restEnd = 300)
        val parsed = FramePlaybackSettings.parse(
            PlaybackSettingsEdit.merge(FrameManager.DEFAULT_SETTINGS_JSON, value),
        )
        assertEquals(15, parsed.holdSeconds)
        assertEquals("slide", parsed.transition)
        assertEquals(1.2, parsed.transitionSeconds, 1e-9)
        assertEquals(FramePlaybackSettings.FIT_COLOR, parsed.fitMode)
        assertEquals(true, parsed.clockHour24)
        assertEquals(FramePlaybackSettings.POSITION_TOP_TRAILING, parsed.clockPosition)
        assertEquals(120, parsed.restStartMinute)
        assertEquals(300, parsed.restEndMinute)
        assertEquals(0.4f, parsed.brightness)
        assertEquals(FramePlaybackSettings.QUALITY_LOW, parsed.qualityLevel)
    }

    @Test
    fun validateBoundaries() {
        assertEquals(
            PlaybackSettingsEdit.Error.HOLD_OUT_OF_RANGE,
            PlaybackSettingsEdit.validate(draft(holdSeconds = 2)),
        )
        assertEquals(
            PlaybackSettingsEdit.Error.HOLD_OUT_OF_RANGE,
            PlaybackSettingsEdit.validate(draft(holdSeconds = 3601)),
        )
        assertNull(PlaybackSettingsEdit.validate(draft(holdSeconds = 3)))
        assertNull(PlaybackSettingsEdit.validate(draft(holdSeconds = 3600)))
    }

    @Test
    fun restWindowStartEqualsEndIsInvalidOnlyWhenEnabled() {
        assertEquals(
            PlaybackSettingsEdit.Error.REST_WINDOW_INVALID,
            PlaybackSettingsEdit.validate(draft(restEnabled = true, restStart = 60, restEnd = 60)),
        )
        assertNull(PlaybackSettingsEdit.validate(draft(restEnabled = false, restStart = 60, restEnd = 60)))
        assertEquals(
            PlaybackSettingsEdit.Error.REST_WINDOW_INVALID,
            PlaybackSettingsEdit.validate(draft(restEnabled = true, restStart = null, restEnd = 60)),
        )
    }

    @Test
    fun noneTransitionSkipsSecondsValidation() {
        val value = draft(transition = "none").copy(transitionSeconds = 99.0)
        assertNull(PlaybackSettingsEdit.validate(value))
        assertEquals(
            PlaybackSettingsEdit.Error.TRANSITION_SECONDS_INVALID,
            PlaybackSettingsEdit.validate(draft().copy(transitionSeconds = 99.0)),
        )
    }
}
