package dev.photopilot.scaffold.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.MusicCatalog

class MusicPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var prepared = false
    private var wantPlaying = false
    private var focusPaused = false

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isPlaying()) {
                    focusPaused = true
                    runCatching { mediaPlayer?.pause() }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusPaused) {
                    focusPaused = false
                    if (wantPlaying) {
                        runCatching { mediaPlayer?.start() }
                    }
                }
            }
        }
    }

    fun isLoaded(): Boolean = mediaPlayer != null

    fun isPlaying(): Boolean = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)

    fun start(selection: MusicCatalog.Selection) {
        stop()
        val player = MediaPlayer()
        val ready = runCatching {
            if (selection.source == FramePlaybackSettings.MUSIC_SOURCE_LOCAL) {
                val file = MusicCatalog.localFile(appContext, selection.localPath)?.takeIf { it.isFile }
                    ?: throw IllegalArgumentException("missing local music")
                player.setDataSource(file.absolutePath)
            } else {
                val track = MusicCatalog.track(selection.trackId)
                    ?: throw IllegalArgumentException("unknown track")
                appContext.resources.openRawResourceFd(track.rawRes).use { fd ->
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.declaredLength)
                }
            }
            player.setAudioAttributes(audioAttributes)
            player.isLooping = true
            player.setOnPreparedListener { mp ->
                prepared = true
                if (wantPlaying && mp === mediaPlayer && requestFocus()) {
                    runCatching { mp.start() }
                }
            }
            player.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            wantPlaying = true
            player.prepareAsync()
        }.isSuccess
        if (!ready) {
            player.release()
            return
        }
        mediaPlayer = player
    }

    fun pause() {
        wantPlaying = false
        focusPaused = false
        if (isPlaying()) {
            runCatching { mediaPlayer?.pause() }
        }
    }

    fun resume() {
        wantPlaying = true
        if (prepared && !isPlaying() && requestFocus()) {
            runCatching { mediaPlayer?.start() }
        }
    }

    fun stop() {
        wantPlaying = false
        focusPaused = false
        prepared = false
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        abandonFocus()
    }

    fun release() {
        stop()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            focusRequest = request
            hasFocus = true
        }
        return granted
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        audioManager.abandonAudioFocusRequest(request)
        focusRequest = null
        hasFocus = false
    }
}
