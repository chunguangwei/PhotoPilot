package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.PlaybackSettingsEdit
import dev.photopilot.scaffold.data.repo.FrameManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaybackSettingsTest {

    private fun draft(
        musicEnabled: Boolean = false,
        musicSource: String = FramePlaybackSettings.MUSIC_SOURCE_BUILTIN,
        musicTrackId: String? = null,
        musicLocalPath: String? = null,
        musicLocalTitle: String? = null,
    ) = PlaybackSettingsEdit.Draft(
        holdSeconds = 10,
        transition = "crossfade",
        transitionSeconds = 0.8,
        fitMode = FramePlaybackSettings.FIT_BLUR,
        clockEnabled = true,
        clockPosition = FramePlaybackSettings.POSITION_BOTTOM_LEADING,
        clockHour24 = null,
        dateEnabled = true,
        keepAwakeWhenPaused = true,
        restEnabled = false,
        restStartMinute = null,
        restEndMinute = null,
        brightness = null,
        qualityLevel = FramePlaybackSettings.QUALITY_AUTO,
        musicEnabled = musicEnabled,
        musicSource = musicSource,
        musicTrackId = musicTrackId,
        musicLocalPath = musicLocalPath,
        musicLocalTitle = musicLocalTitle,
    )

    @Test
    fun defaultJsonContainsMusicDefaults() {
        val root = JsonMini.parseObject(FrameManager.DEFAULT_SETTINGS_JSON)
        val music = root["music"] as Map<*, *>
        assertEquals(false, music["enabled"])
        assertEquals("builtin", music["source"])
        assertNull(music["trackId"])
        assertNull(music["localPath"])
        assertNull(music["localTitle"])
        val settings = FramePlaybackSettings.parse(FrameManager.DEFAULT_SETTINGS_JSON)
        assertFalse(settings.musicEnabled)
        assertEquals(FramePlaybackSettings.MUSIC_SOURCE_BUILTIN, settings.musicSource)
        assertNull(settings.musicTrackId)
        assertNull(settings.musicLocalPath)
        assertNull(settings.musicLocalTitle)
    }

    @Test
    fun missingMusicKeyFallsBackToDefaults() {
        val settings = FramePlaybackSettings.parse("{\"holdSeconds\":15}")
        assertFalse(settings.musicEnabled)
        assertEquals(FramePlaybackSettings.MUSIC_SOURCE_BUILTIN, settings.musicSource)
        assertNull(settings.musicTrackId)
        assertNull(settings.musicLocalPath)
        assertNull(settings.musicLocalTitle)
    }

    @Test
    fun extraMusicKeysParseAndSurviveMerge() {
        val original = "{\"music\":{\"enabled\":true,\"source\":\"builtin\"," +
            "\"trackId\":\"travel_01\",\"futureKey\":\"keep\"}}"
        val settings = FramePlaybackSettings.parse(original)
        assertEquals(true, settings.musicEnabled)
        assertEquals("travel_01", settings.musicTrackId)
        val merged = JsonMini.parseObject(
            PlaybackSettingsEdit.merge(
                original,
                draft(musicEnabled = true, musicTrackId = "travel_01"),
            ),
        )
        val music = merged["music"] as Map<*, *>
        assertEquals("keep", music["futureKey"])
        assertEquals(true, music["enabled"])
        assertEquals("travel_01", music["trackId"])
    }

    @Test
    fun mergeRoundTripsBuiltinSelection() {
        val value = draft(musicEnabled = true, musicTrackId = "coffee_01")
        val parsed = FramePlaybackSettings.parse(
            PlaybackSettingsEdit.merge(FrameManager.DEFAULT_SETTINGS_JSON, value),
        )
        assertEquals(true, parsed.musicEnabled)
        assertEquals(FramePlaybackSettings.MUSIC_SOURCE_BUILTIN, parsed.musicSource)
        assertEquals("coffee_01", parsed.musicTrackId)
        assertNull(parsed.musicLocalPath)
        assertNull(parsed.musicLocalTitle)
    }

    @Test
    fun mergeRoundTripsLocalSelection() {
        val value = draft(
            musicEnabled = true,
            musicSource = FramePlaybackSettings.MUSIC_SOURCE_LOCAL,
            musicLocalPath = "abc-123.mp3",
            musicLocalTitle = "my song.mp3",
        )
        val parsed = FramePlaybackSettings.parse(
            PlaybackSettingsEdit.merge(FrameManager.DEFAULT_SETTINGS_JSON, value),
        )
        assertEquals(true, parsed.musicEnabled)
        assertEquals(FramePlaybackSettings.MUSIC_SOURCE_LOCAL, parsed.musicSource)
        assertNull(parsed.musicTrackId)
        assertEquals("abc-123.mp3", parsed.musicLocalPath)
        assertEquals("my song.mp3", parsed.musicLocalTitle)
    }

    @Test
    fun musicSurvivesEditThatDoesNotTouchIt() {
        val original = "{\"holdSeconds\":10," +
            "\"music\":{\"enabled\":true,\"source\":\"local\",\"trackId\":null," +
            "\"localPath\":\"abc-123.mp3\",\"localTitle\":\"my song.mp3\"}}"
        val before = FramePlaybackSettings.parse(original)
        val value = draft(
            musicEnabled = before.musicEnabled,
            musicSource = before.musicSource,
            musicTrackId = before.musicTrackId,
            musicLocalPath = before.musicLocalPath,
            musicLocalTitle = before.musicLocalTitle,
        )
        val merged = FramePlaybackSettings.parse(PlaybackSettingsEdit.merge(original, value))
        assertEquals(before.musicEnabled, merged.musicEnabled)
        assertEquals(before.musicSource, merged.musicSource)
        assertEquals(before.musicTrackId, merged.musicTrackId)
        assertEquals(before.musicLocalPath, merged.musicLocalPath)
        assertEquals(before.musicLocalTitle, merged.musicLocalTitle)
    }
}
