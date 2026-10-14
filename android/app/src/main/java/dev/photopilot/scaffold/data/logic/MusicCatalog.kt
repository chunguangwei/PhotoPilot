package dev.photopilot.scaffold.data.logic

import android.content.Context
import dev.photopilot.scaffold.R
import java.io.File

object MusicCatalog {

    data class Track(
        val id: String,
        val rawRes: Int,
        val nameRes: Int,
    )

    data class Selection(
        val enabled: Boolean,
        val source: String,
        val trackId: String?,
        val localPath: String?,
        val localTitle: String?,
    )

    val TRACKS = listOf(
        Track("birthday_01", R.raw.music_birthday_01, R.string.music_track_birthday),
        Track("wedding_01", R.raw.music_wedding_01, R.string.music_track_wedding),
        Track("spring_festival_01", R.raw.music_spring_festival_01, R.string.music_track_spring_festival),
        Track("christmas_01", R.raw.music_christmas_01, R.string.music_track_christmas),
        Track("qixi_01", R.raw.music_qixi_01, R.string.music_track_qixi),
        Track("valentine_01", R.raw.music_valentine_01, R.string.music_track_valentine),
        Track("festive_01", R.raw.music_festive_01, R.string.music_track_festive),
        Track("classical_01", R.raw.music_classical_01, R.string.music_track_classical),
        Track("travel_01", R.raw.music_travel_01, R.string.music_track_travel),
        Track("coffee_01", R.raw.music_coffee_01, R.string.music_track_coffee),
        Track("lounge_01", R.raw.music_lounge_01, R.string.music_track_lounge),
        Track("morning_01", R.raw.music_morning_01, R.string.music_track_morning),
        Track("night_01", R.raw.music_night_01, R.string.music_track_night),
        Track("nature_01", R.raw.music_nature_01, R.string.music_track_nature),
    )

    fun track(id: String?): Track? = TRACKS.firstOrNull { it.id == id }

    fun selectionOf(settings: FramePlaybackSettings): Selection = Selection(
        enabled = settings.musicEnabled,
        source = settings.musicSource,
        trackId = settings.musicTrackId,
        localPath = settings.musicLocalPath,
        localTitle = settings.musicLocalTitle,
    )

    fun musicDir(context: Context): File = File(context.filesDir, "music")

    fun localFile(context: Context, localPath: String?): File? {
        val name = localPath ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(musicDir(context), name)
    }

    fun displayName(context: Context, selection: Selection): String {
        if (!selection.enabled) return context.getString(R.string.playback_music_off)
        if (selection.source == FramePlaybackSettings.MUSIC_SOURCE_LOCAL) {
            val file = localFile(context, selection.localPath)
            if (file == null || !file.isFile) {
                return context.getString(R.string.playback_music_missing)
            }
            return selection.localTitle ?: selection.localPath
                ?: context.getString(R.string.playback_music_missing)
        }
        val track = track(selection.trackId)
            ?: return context.getString(R.string.playback_music_off)
        return context.getString(track.nameRes)
    }
}
