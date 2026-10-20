package dev.photopilot.scaffold.ui.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.logic.ClockFormat
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.MusicCatalog
import dev.photopilot.scaffold.data.logic.PlaybackController
import dev.photopilot.scaffold.data.logic.PlaybackTiming
import dev.photopilot.scaffold.data.logic.RenderGeometry
import dev.photopilot.scaffold.data.logic.VideoProbe
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerView(
    context: Context,
    private val frame: FrameEntity,
) : FrameLayout(context), PlayerEngine.Listener {

    var engine: PlayerEngine? = null
    var onExit: (() -> Unit)? = null
    var onEmptyChoose: ((FrameEntity) -> Unit)? = null
    var onEmptyDownloads: (() -> Unit)? = null
    var onPlaybackSettings: ((FrameEntity) -> Unit)? = null
    var weatherPlaceProvider: (() -> String?)? = null
    var weatherTextProvider: (() -> String?)? = null
    var weatherA11yProvider: (() -> String?)? = null

    private var settings = FramePlaybackSettings.parse(frame.settingsJson)

    private val backgroundImage: ImageView
    private val foregroundA: ImageView
    private val foregroundB: ImageView
    private lateinit var videoView: android.widget.VideoView
    private var videoTruncateRunnable: Runnable? = null
    private var videoTruncateDeadlineMs = 0L
    private var videoTruncateRemainingMs = 0L
    private var videoTruncatePaused = false
    private var showingA = true
    private val scrim: View
    private val noticeLine: TextView
    private val singlePhotoLine: TextView
    private val clockLine: TextView
    private val dateLine: TextView
    private lateinit var restingClock: dev.photopilot.scaffold.ui.widget.FlipClockView
    private lateinit var restingDate: TextView
    private lateinit var restingWeather: TextView
    private lateinit var weatherLine: TextView
    private lateinit var weatherPlaceLine: TextView
    private lateinit var restingWeatherPlace: TextView
    private val overlayColumn: LinearLayout
    private val controlsBar: HorizontalScrollView
    private val pauseButton: ImageButton
    private val emptyOverlay: LinearLayout
    private val restingOverlay: LinearLayout
    private val safeOverlay: LinearLayout
    private val failedOverlay: LinearLayout
    private var currentAssetIndex = 0
    private var currentQueueSize = 0
    private var brightnessApplied = false
    private var originalBrightness: Float? = null
    private var controlsTouchActive = false
    private val musicPlayer = MusicPlayer(context)
    private var musicStarted: MusicCatalog.Selection? = null
    private var lastRandomTransition: String? = null

    private fun resolveTransition(): String {
        if (settings.transition != PlaybackTiming.TRANSITION_RANDOM) return settings.transition
        val candidates = PlaybackTiming.randomTransitionPool.filter { it != lastRandomTransition }
        val picked = candidates.randomOrNull() ?: PlaybackTiming.TRANSITION_CROSSFADE
        lastRandomTransition = picked
        return picked
    }
    private var musicFailedSelection: MusicCatalog.Selection? = null
    private var musicWasPlayingInBackground = false
    private var appBackgrounded = false

    private val hideControlsRunnable = Runnable {
        if (controlsBar.hasFocus() || hasAccessibilityFocus(controlsBar) || controlsTouchActive) {
            scheduleHideControls()
        } else {
            controlsBar.visibility = View.GONE
        }
    }

    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            postDelayed(this, nextClockTickDelayMs())
        }
    }

    private fun restartClockTick() {
        removeCallbacks(clockTickRunnable)
        updateClock()
        postDelayed(clockTickRunnable, nextClockTickDelayMs())
    }

    private fun nextClockTickDelayMs(): Long {
        if (!restClockModeActive()) return CLOCK_TICK_MS
        val remainder = System.currentTimeMillis() % SECOND_TICK_MS
        val delay = SECOND_TICK_MS - remainder
        return if (delay < SECOND_TICK_MIN_DELAY_MS) delay + SECOND_TICK_MS else delay
    }

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_rest))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        backgroundImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        foregroundA = ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        foregroundB = ImageView(context).apply {
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scrim = View(context).apply {
            setBackgroundColor(context.getColor(R.color.pp_surface_rest))
            alpha = SCRIM_ALPHA
            visibility = View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(backgroundImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoView = android.widget.VideoView(context).apply {
            visibility = View.GONE
        }
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foregroundA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(foregroundB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        noticeLine = overlayText(R.dimen.pp_text_meta).apply { visibility = View.GONE }
        singlePhotoLine = overlayText(R.dimen.pp_text_meta).apply {
            text = res.getString(R.string.player_single_photo_notice)
            visibility = View.GONE
        }
        addView(
            noticeLine,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addView(
            singlePhotoLine,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_48)
            },
        )

        val overlayColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        clockLine = overlayText(R.dimen.pp_text_clock).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = if (settings.clockEnabled) View.VISIBLE else View.GONE
        }
        dateLine = overlayText(R.dimen.pp_text_meta).apply {
            visibility = if (settings.dateEnabled) View.VISIBLE else View.GONE
        }
        overlayColumn.addView(clockLine, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        overlayColumn.addView(dateLine, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        weatherPlaceLine = overlayText(R.dimen.pp_text_meta).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            visibility = View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        overlayColumn.addView(weatherPlaceLine, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        weatherLine = overlayText(R.dimen.pp_text_meta).apply {
            visibility = View.GONE
        }
        overlayColumn.addView(weatherLine, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(overlayColumn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        this.overlayColumn = overlayColumn
        applyClockPosition()

        controlsBar = object : HorizontalScrollView(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        controlsTouchActive = true
                        this@PlayerView.removeCallbacks(hideControlsRunnable)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        controlsTouchActive = false
                        scheduleHideControls()
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            alpha = CONTROLS_ALPHA
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_photo_control))
                cornerRadius = res.getDimension(R.dimen.pp_radius_modal)
            }
            clipToOutline = true
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            contentDescription = res.getString(R.string.a11y_player_controls)
            visibility = View.GONE
            setOnClickListener { scheduleHideControls() }
        }
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            setPadding(padH, 0, padH, 0)
        }
        controlsRow.addView(controlButton(R.drawable.ic_player_previous_24, R.string.player_previous) { engine?.userPrev() })
        pauseButton = controlButton(R.drawable.ic_player_pause_24, R.string.player_pause) { togglePause() }
        controlsRow.addView(pauseButton)
        controlsRow.addView(controlButton(R.drawable.ic_player_next_24, R.string.player_next) { engine?.userNext() })
        controlsRow.addView(controlButton(R.drawable.ic_player_more_24, R.string.player_more) { anchor -> showMoreMenu(anchor) })
        controlsRow.addView(controlButton(R.drawable.ic_player_exit_24, R.string.player_exit) { onExit?.invoke() })
        controlsBar.addView(controlsRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            controlsBar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = inset
            },
        )

        emptyOverlay = fullOverlay()
        restingOverlay = fullOverlay()
        safeOverlay = fullOverlay()
        failedOverlay = fullOverlay()
        listOf(emptyOverlay, restingOverlay, safeOverlay, failedOverlay).forEach { overlay ->
            addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        buildEmptyOverlay()
        buildRestingOverlay()
        buildSafeOverlay()
        buildFailedOverlay()

        setOnClickListener {
            if (restingOverlay.visibility == View.VISIBLE) {
                engine?.onRestTap()
            } else {
                toggleControls()
            }
        }
        updateClock()
        postDelayed(clockTickRunnable, nextClockTickDelayMs())
    }

    override fun onStateChanged(state: String) {
        if (state == PlaybackController.RESTING || state == PlaybackController.EMPTY) {
            safeOverlay.visibility = View.GONE
        }
        emptyOverlay.visibility = if (state == PlaybackController.EMPTY) View.VISIBLE else View.GONE
        restingOverlay.visibility = if (state == PlaybackController.RESTING) View.VISIBLE else View.GONE
        if (state == PlaybackController.RESTING) {
            applyRestDisplayMode()
            restartClockTick()
        }
        failedOverlay.visibility = if (state == PlaybackController.FAILED) View.VISIBLE else View.GONE
        val isPlaying = state != PlaybackController.PAUSED
        pauseButton.setImageResource(if (isPlaying) R.drawable.ic_player_pause_24 else R.drawable.ic_player_play_24)
        pauseButton.contentDescription = resources.getString(
            if (isPlaying) R.string.player_pause else R.string.player_play,
        )
        pauseButton.tooltipText = pauseButton.contentDescription
        if ((state == PlaybackController.PAUSED || state == PlaybackController.RESTING) &&
            videoView.isPlaying
        ) {
            videoView.pause()
            pauseVideoTruncateTimer()
        } else if (state == PlaybackController.HOLDING &&
            videoView.visibility == View.VISIBLE &&
            !videoView.isPlaying
        ) {
            resumeVideoTruncateTimer()
            videoView.start()
        }
        val interactive = state == PlaybackController.HOLDING ||
            state == PlaybackController.PAUSED ||
            state == PlaybackController.TRANSITIONING
        if (!interactive) {
            controlsBar.visibility = View.GONE
        }
        syncMusic(state)
    }

    private fun syncMusic(state: String) {
        if (appBackgrounded) return
        val selection = MusicCatalog.selectionOf(settings)
        if (musicStarted != selection) {
            musicPlayer.stop()
            musicStarted = null
        }
        val started = musicStarted
        if (started != null && !musicPlayer.isLoaded()) {
            musicPlayer.stop()
            musicFailedSelection = started
            musicStarted = null
        }
        if (musicStarted == null) {
            val playable = state == PlaybackController.HOLDING || state == PlaybackController.TRANSITIONING
            if (!selection.enabled || !playable || musicFailedSelection == selection) return
            if (musicPlayer.start(selection)) {
                musicStarted = selection
            } else {
                musicFailedSelection = selection
                return
            }
        }
        when (state) {
            PlaybackController.HOLDING, PlaybackController.TRANSITIONING -> musicPlayer.resume()
            else -> musicPlayer.pause()
        }
    }

    fun onAppBackground() {
        appBackgrounded = true
        musicWasPlayingInBackground = musicPlayer.isPlaying()
        musicPlayer.stop()
        musicStarted = null
    }

    fun onAppForeground() {
        appBackgrounded = false
        if (musicWasPlayingInBackground) {
            musicWasPlayingInBackground = false
            engine?.currentState()?.let { syncMusic(it) }
        }
    }

    override fun onShowVideo(
        asset: AssetEntity,
        path: String,
        blurred: Bitmap?,
        edgeColor: Int?,
    ) {
        stopVideo()
        foregroundA.setImageDrawable(null)
        foregroundA.alpha = 0f
        foregroundB.setImageDrawable(null)
        foregroundB.alpha = 0f
        applyVideoBackground(blurred, edgeColor)
        videoView.setVideoPath(path)
        videoView.alpha = 0f
        videoView.visibility = View.VISIBLE
        videoView.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.setVolume(0f, 0f)
            videoView.animate().alpha(1f).setDuration(VIDEO_FADE_MS).start()
            videoView.start()
            if (VideoProbe.isOverLimit(asset.durationMs)) {
                val runnable = Runnable { engine?.onVideoCompleted() }
                videoTruncateRunnable = runnable
                videoTruncatePaused = false
                videoTruncateRemainingMs = VideoProbe.VIDEO_MAX_DURATION_MS
                videoTruncateDeadlineMs = System.currentTimeMillis() + VideoProbe.VIDEO_MAX_DURATION_MS
                videoView.postDelayed(runnable, VideoProbe.VIDEO_MAX_DURATION_MS)
            }
        }
        videoView.setOnCompletionListener { engine?.onVideoCompleted() }
        videoView.setOnErrorListener { _, _, _ ->
            engine?.onVideoError()
            true
        }
    }

    private fun applyVideoBackground(blurred: Bitmap?, edgeColor: Int?) {
        when (settings.fitMode) {
            FramePlaybackSettings.FIT_BLUR -> {
                if (blurred != null) {
                    backgroundImage.setImageBitmap(blurred)
                    scrim.visibility = View.VISIBLE
                }
            }
            FramePlaybackSettings.FIT_COLOR -> {
                backgroundImage.setImageDrawable(
                    ColorDrawable(edgeColor ?: context.getColor(R.color.pp_surface_rest)),
                )
                scrim.visibility = View.GONE
            }
        }
    }

    private fun stopVideo() {
        videoTruncateRunnable?.let(videoView::removeCallbacks)
        videoTruncateRunnable = null
        videoTruncatePaused = false
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        videoView.alpha = 1f
    }

    private fun pauseVideoTruncateTimer() {
        val runnable = videoTruncateRunnable ?: return
        if (videoTruncatePaused) return
        videoTruncatePaused = true
        videoView.removeCallbacks(runnable)
        videoTruncateRemainingMs =
            (videoTruncateDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun resumeVideoTruncateTimer() {
        val runnable = videoTruncateRunnable ?: return
        if (!videoTruncatePaused) return
        videoTruncatePaused = false
        videoTruncateDeadlineMs = System.currentTimeMillis() + videoTruncateRemainingMs
        videoView.postDelayed(runnable, videoTruncateRemainingMs)
    }

    override fun onShowAsset(
        asset: AssetEntity,
        decoded: PlayerEngine.DecodedAsset,
        transitionMs: Long,
        direction: Int,
        position: Int,
    ) {
        stopVideo()
        safeOverlay.visibility = View.GONE
        currentAssetIndex = position
        applyFitBackground(decoded)
        val incoming = if (showingA) foregroundB else foregroundA
        val outgoing = if (showingA) foregroundA else foregroundB
        incoming.scaleType = adaptiveFitScaleType(decoded.bitmap)
        incoming.setImageBitmap(decoded.bitmap)
        incoming.contentDescription = resources.getString(
            R.string.a11y_current_photo, currentAssetIndex, currentQueueSize.coerceAtLeast(1),
        )
        val transition = resolveTransition()
        val distance = width.coerceAtLeast(1).toFloat()
        if (transitionMs <= 0L) {
            incoming.animate().cancel()
            outgoing.animate().cancel()
            incoming.alpha = 1f
            incoming.translationX = 0f
            incoming.scaleX = 1f
            incoming.scaleY = 1f
            outgoing.alpha = 0f
            outgoing.translationX = 0f
            outgoing.scaleX = 1f
            outgoing.scaleY = 1f
            showingA = !showingA
            engine?.transitionFinished()
        } else if (transition == PlaybackTiming.TRANSITION_SLIDE && direction != 0) {
            incoming.bringToFront()
            raiseOverlays()
            incoming.alpha = 1f
            incoming.translationX = direction * distance
            incoming.animate().translationX(0f).setDuration(transitionMs).start()
            outgoing.animate().translationX(-direction * distance).setDuration(transitionMs)
                .withEndAction {
                    outgoing.alpha = 0f
                    outgoing.translationX = 0f
                    showingA = !showingA
                    engine?.transitionFinished()
                }.start()
        } else if (transition == PlaybackTiming.TRANSITION_WIPE && direction != 0) {
            incoming.bringToFront()
            raiseOverlays()
            incoming.alpha = 1f
            incoming.translationX = direction * distance
            incoming.animate().translationX(0f).setDuration(transitionMs)
                .withEndAction {
                    outgoing.alpha = 0f
                    showingA = !showingA
                    engine?.transitionFinished()
                }.start()
        } else if (transition == PlaybackTiming.TRANSITION_ZOOM) {
            incoming.bringToFront()
            raiseOverlays()
            incoming.alpha = 0f
            incoming.scaleX = 1.08f
            incoming.scaleY = 1.08f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(transitionMs)
                .withEndAction {
                    outgoing.alpha = 0f
                    showingA = !showingA
                    engine?.transitionFinished()
                }.start()
            outgoing.animate().alpha(0f).setDuration(transitionMs).start()
        } else if (transition == PlaybackTiming.TRANSITION_RIPPLE) {
            incoming.bringToFront()
            incoming.alpha = 0f
            raiseOverlays()
            incoming.scaleX = 0.9f
            incoming.scaleY = 0.9f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(transitionMs)
                .withEndAction {
                    outgoing.alpha = 0f
                    showingA = !showingA
                    engine?.transitionFinished()
                }.start()
            outgoing.animate().alpha(0f).setDuration(transitionMs).start()
        } else {
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(transitionMs).start()
            outgoing.animate().alpha(0f).setDuration(transitionMs).withEndAction {
                showingA = !showingA
                engine?.transitionFinished()
            }.start()
        }
        scheduleHideControls()
    }

    private fun raiseOverlays() {
        noticeLine.bringToFront()
        singlePhotoLine.bringToFront()
        overlayColumn.bringToFront()
        controlsBar.bringToFront()
        emptyOverlay.bringToFront()
        restingOverlay.bringToFront()
        safeOverlay.bringToFront()
        failedOverlay.bringToFront()
    }

    override fun onClearTextures() {
        stopVideo()
        foregroundA.setImageDrawable(null)
        foregroundB.setImageDrawable(null)
        backgroundImage.setImageDrawable(null)
    }

    override fun onShowSafeImage() {
        safeOverlay.visibility = if (engine?.currentState() in setOf(PlaybackController.RESTING, PlaybackController.EMPTY)) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    override fun onQueueInfo(size: Int) {
        currentQueueSize = size
        singlePhotoLine.visibility = if (size == 1) View.VISIBLE else View.GONE
    }

    override fun onDecodeSkipped() {
        noticeLine.text = resources.getString(R.string.player_decode_failed_notice)
        noticeLine.visibility = View.VISIBLE
    }

    fun showNotice(text: String) {
        noticeLine.text = text
        noticeLine.visibility = View.VISIBLE
    }

    override fun onKeepScreenOn(keep: Boolean) {
        keepScreenOn = keep
    }

    private var hostRoot: dev.photopilot.scaffold.ui.AppRootView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hostRoot = parent as? dev.photopilot.scaffold.ui.AppRootView
        hostRoot?.fullBleedOverlay = true
        setImmersive(true)
        restartClockTick()
    }

    override fun onDetachedFromWindow() {
        hostRoot?.fullBleedOverlay = false
        hostRoot = null
        setImmersive(false)
        musicPlayer.release()
        musicStarted = null
        restoreBrightness()
        removeCallbacks(clockTickRunnable)
        removeCallbacks(hideControlsRunnable)
        super.onDetachedFromWindow()
    }

    private fun setImmersive(immersive: Boolean) {
        val window = (context as? android.app.Activity)?.window ?: return
        if (immersive) {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (immersive) {
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(android.view.WindowInsets.Type.systemBars())
            } else {
                controller.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (immersive) {
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    fun applyBrightnessIfNeeded(activity: android.app.Activity) {
        val level = settings.brightness
        if (level == null) {
            restoreBrightness()
            return
        }
        val attrs = activity.window.attributes
        if (!brightnessApplied) {
            originalBrightness = attrs.screenBrightness
            brightnessApplied = true
        }
        attrs.screenBrightness = level.coerceIn(0f, 1f)
        activity.window.attributes = attrs
    }

    private fun restoreBrightness() {
        val original = originalBrightness ?: return
        val activity = context as? android.app.Activity ?: return
        val attrs = activity.window.attributes
        attrs.screenBrightness = original
        activity.window.attributes = attrs
        brightnessApplied = false
        originalBrightness = null
    }

    private fun applyFitBackground(decoded: PlayerEngine.DecodedAsset) {
        when {
            settings.fitMode == FramePlaybackSettings.FIT_BLUR ||
                fillCropFallsBack(decoded.bitmap) -> {
                if (decoded.blurred != null) {
                    backgroundImage.setImageBitmap(decoded.blurred)
                    scrim.visibility = View.VISIBLE
                } else {
                    backgroundImage.setImageDrawable(null)
                    scrim.visibility = View.GONE
                }
            }
            settings.fitMode == FramePlaybackSettings.FIT_COLOR -> {
                backgroundImage.setImageDrawable(
                    ColorDrawable(solidBackgroundColor(decoded)),
                )
                scrim.visibility = View.GONE
            }
            else -> {
                backgroundImage.setImageDrawable(null)
                scrim.visibility = View.GONE
            }
        }
    }

    private fun adaptiveFitScaleType(bitmap: android.graphics.Bitmap): ImageView.ScaleType {
        if (settings.fitMode == FramePlaybackSettings.FILL_CROP) {
            return if (RenderGeometry.fillCropKeepsEnough(
                    bitmap.width.toLong(), bitmap.height.toLong(),
                    width.toLong(), height.toLong(),
                )
            ) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
        }
        if (settings.fitMode != FramePlaybackSettings.FIT_BLUR) {
            return ImageView.ScaleType.FIT_CENTER
        }
        return if (RenderGeometry.narrowBarCrop(
                bitmap.width.toLong(), bitmap.height.toLong(),
                width.toLong(), height.toLong(),
            )
        ) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun fillCropFallsBack(bitmap: android.graphics.Bitmap): Boolean =
        settings.fitMode == FramePlaybackSettings.FILL_CROP && !RenderGeometry.fillCropKeepsEnough(
            bitmap.width.toLong(), bitmap.height.toLong(),
            width.toLong(), height.toLong(),
        )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 || oldh == 0) return
        if (settings.fitMode != FramePlaybackSettings.FIT_BLUR &&
            settings.fitMode != FramePlaybackSettings.FILL_CROP
        ) return
        listOf(foregroundA, foregroundB).forEach { view ->
            val bitmap = (view.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bitmap != null) {
                view.scaleType = adaptiveFitScaleType(bitmap)
            }
        }
    }

    private fun solidBackgroundColor(decoded: PlayerEngine.DecodedAsset): Int =
        when (settings.solidColor) {
            FramePlaybackSettings.SOLID_BLACK -> context.getColor(R.color.pp_surface_rest)
            FramePlaybackSettings.SOLID_WARM_WHITE -> context.getColor(R.color.pp_surface_canvas)
            else -> decoded.edgeColor ?: context.getColor(R.color.pp_surface_rest)
        }

    private fun togglePause() {
        val engine = engine ?: return
        if (engine.currentState() == PlaybackController.PAUSED) {
            engine.userResume()
        } else {
            engine.userPause()
        }
    }

    private fun toggleControls() {
        if (controlsBar.visibility == View.VISIBLE) {
            controlsBar.visibility = View.GONE
        } else {
            controlsBar.visibility = View.VISIBLE
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        removeCallbacks(hideControlsRunnable)
        if (controlsBar.visibility == View.VISIBLE) {
            postDelayed(hideControlsRunnable, CONTROLS_HIDE_MS)
        }
    }

    private fun showMoreMenu(anchor: View) {
        val menu = PopupMenu(context, anchor)
        menu.menu.add(0, MENU_REMOVE_CURRENT, 0, resources.getString(R.string.player_remove_current))
        menu.menu.add(0, MENU_SETTINGS, 1, resources.getString(R.string.frames_playback_settings))
        menu.menu.add(0, MENU_EXIT, 2, resources.getString(R.string.player_exit))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REMOVE_CURRENT -> engine?.removeCurrent()
                MENU_SETTINGS -> onPlaybackSettings?.invoke(frame)
                MENU_EXIT -> onExit?.invoke()
            }
            true
        }
        menu.show()
    }

    fun reloadSettings(freshFrame: FrameEntity) {
        settings = FramePlaybackSettings.parse(freshFrame.settingsJson)
        musicFailedSelection = null
        clockLine.visibility = if (settings.clockEnabled) View.VISIBLE else View.GONE
        dateLine.visibility = if (settings.dateEnabled) View.VISIBLE else View.GONE
        restingDate.visibility = if (settings.dateEnabled) View.VISIBLE else View.GONE
        (context as? android.app.Activity)?.let { applyBrightnessIfNeeded(it) }
        applyClockPosition()
        if (restingOverlay.visibility == View.VISIBLE) {
            applyRestDisplayMode()
        }
        updateClock()
        engine?.currentState()?.let { syncMusic(it) }
    }

    private fun applyClockPosition() {
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val controlsClearance = res.getDimensionPixelSize(R.dimen.pp_space_48) + inset
        val lp = overlayColumn.layoutParams as LayoutParams
        when (settings.clockPosition) {
            FramePlaybackSettings.POSITION_TOP_LEADING -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.marginStart = inset
                lp.marginEnd = 0
                lp.topMargin = inset
                lp.bottomMargin = 0
            }
            FramePlaybackSettings.POSITION_TOP_TRAILING -> {
                lp.gravity = Gravity.TOP or Gravity.END
                lp.marginStart = 0
                lp.marginEnd = inset
                lp.topMargin = inset
                lp.bottomMargin = 0
            }
            FramePlaybackSettings.POSITION_TOP_CENTER -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.marginStart = 0
                lp.marginEnd = 0
                lp.topMargin = inset
                lp.bottomMargin = 0
            }
            FramePlaybackSettings.POSITION_BOTTOM_CENTER -> {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.marginStart = 0
                lp.marginEnd = 0
                lp.topMargin = 0
                lp.bottomMargin = controlsClearance
            }
            FramePlaybackSettings.POSITION_BOTTOM_TRAILING -> {
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.marginStart = 0
                lp.marginEnd = inset
                lp.topMargin = 0
                lp.bottomMargin = controlsClearance
            }
            else -> {
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.marginStart = inset
                lp.marginEnd = 0
                lp.topMargin = 0
                lp.bottomMargin = controlsClearance
            }
        }
        lp.marginStart = inset
        lp.marginEnd = inset
        overlayColumn.layoutParams = lp
    }

    private fun updateClock() {
        val now = Date()
        val hour24 = settings.clockHour24 ?: DateFormat.is24HourFormat(context)
        val pattern = ClockFormat.pattern(hour24, DateFormat.is24HourFormat(context))
        val timeText = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        clockLine.text = timeText
        clockLine.contentDescription = resources.getString(R.string.a11y_clock_overlay, timeText)
        val dateText = ClockFormat.dateWithWeekday(
            DateFormat.getDateFormat(context).format(now),
            now,
            resources.configuration.locales[0] ?: Locale.getDefault(),
            java.util.TimeZone.getDefault(),
        )
        dateLine.text = dateText
        restingDate.text = dateText
        val calendar = java.util.Calendar.getInstance()
        var hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        if (!hour24) {
            hour %= 12
            if (hour == 0) hour = 12
        }
        restingClock.setTime(
            String.format(Locale.US, "%02d", hour),
            String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.MINUTE)),
            String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.SECOND)),
        )
        refreshWeather()
    }

    fun refreshWeather() {
        val place = weatherPlaceProvider?.invoke()
        weatherPlaceLine.text = place
        weatherPlaceLine.visibility = if (place.isNullOrBlank()) View.GONE else View.VISIBLE
        restingWeatherPlace.text = place
        restingWeatherPlace.visibility = if (settings.restDisplay == FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER &&
            !place.isNullOrBlank()) View.VISIBLE else View.GONE
        val restWeatherText = if (settings.restDisplay == FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER) {
            weatherTextProvider?.invoke()
        } else {
            null
        }
        if (restWeatherText != null) {
            restingWeather.text = restWeatherText
            restingWeather.contentDescription = weatherA11yProvider?.invoke() ?: restWeatherText
            restingWeather.visibility = View.VISIBLE
        } else if (settings.restDisplay == FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER) {
            restingWeather.text = resources.getString(R.string.weather_disabled_notice)
            restingWeather.visibility = View.VISIBLE
        } else {
            restingWeather.visibility = View.GONE
        }
        val weatherText = weatherTextProvider?.invoke()
        if (weatherText != null) {
            weatherLine.text = weatherText
            weatherLine.contentDescription = weatherA11yProvider?.invoke() ?: weatherText
            weatherLine.visibility = View.VISIBLE
        } else {
            weatherLine.visibility = View.GONE
        }
    }

    private fun overlayText(sizeRes: Int): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            setShadowLayer(8f, 0f, 2f, 0x8C000000.toInt())
        }

    private fun controlButton(iconRes: Int, textRes: Int, action: (View) -> Unit): ImageButton =
        ImageButton(context).apply {
            val res = resources
            val target = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            val iconSize = res.getDimensionPixelSize(R.dimen.pp_space_24)
            val padding = (target - iconSize) / 2
            val iconColor = context.getColor(R.color.pp_text_on_photo)
            layoutParams = LinearLayout.LayoutParams(target, target)
            minimumWidth = target
            minimumHeight = target
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(iconColor)
            contentDescription = res.getString(textRes)
            tooltipText = contentDescription
            val focusBackground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(32, Color.red(iconColor), Color.green(iconColor), Color.blue(iconColor)))
                setStroke(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, res.displayMetrics).toInt().coerceAtLeast(1),
                    context.getColor(R.color.pp_accent_brand),
                )
            }
            val feedback = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focusBackground)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(64, Color.red(iconColor), Color.green(iconColor), Color.blue(iconColor))),
                feedback,
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                },
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                action(it)
                scheduleHideControls()
            }
        }

    private fun hasAccessibilityFocus(view: View): Boolean {
        if (view.isAccessibilityFocused) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasAccessibilityFocus(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun fullOverlay(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(context.getColor(R.color.pp_surface_rest))
            visibility = View.GONE
            val pad = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
            setPadding(pad, pad, pad, pad)
        }

    private fun overlayTitle(textRes: Int): TextView =
        overlayText(R.dimen.pp_text_heading).apply {
            text = resources.getString(textRes)
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }

    private fun overlayBody(textRes: Int): TextView =
        overlayText(R.dimen.pp_text_body).apply {
            text = resources.getString(textRes)
            gravity = Gravity.CENTER
        }

    private fun buildEmptyOverlay() {
        val res = resources
        emptyOverlay.addView(centered(overlayTitle(R.string.player_empty_title)))
        emptyOverlay.addView(centered(overlayBody(R.string.player_empty_body), R.dimen.pp_space_8))
        val choose = primaryButton(context, R.string.player_empty_action_choose).apply {
            setOnClickListener { onEmptyChoose?.invoke(frame) }
        }
        val downloads = secondaryButton(context, R.string.player_empty_action_downloads).apply {
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            setOnClickListener { onEmptyDownloads?.invoke() }
        }
        emptyOverlay.addView(centered(choose, R.dimen.pp_space_24))
        emptyOverlay.addView(centered(downloads, R.dimen.pp_space_12))
    }

    private fun buildRestingOverlay() {
        val res = resources
        restingClock = dev.photopilot.scaffold.ui.widget.FlipClockView(context)
        restingDate = overlayText(R.dimen.pp_text_heading).apply {
            gravity = Gravity.CENTER
            visibility = if (settings.dateEnabled) View.VISIBLE else View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        restingWeatherPlace = overlayText(R.dimen.pp_text_body).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            visibility = View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        restingWeather = overlayText(R.dimen.pp_text_body).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        restingOverlay.isClickable = true
        restingOverlay.isFocusable = true
        restingOverlay.contentDescription =
            "${res.getString(R.string.player_resting_title)}. ${res.getString(R.string.player_resting_resume)}"
        restingOverlay.setOnClickListener { engine?.onRestTap() }
        restingOverlay.addView(
            centered(restingClock),
        )
        restingOverlay.addView(
            centered(restingDate, R.dimen.pp_space_8),
        )
        restingOverlay.addView(centered(restingWeatherPlace, R.dimen.pp_space_8))
        restingOverlay.addView(
            centered(restingWeather, R.dimen.pp_space_8),
        )
    }

    private fun restClockModeActive(): Boolean =
        restingOverlay.visibility == View.VISIBLE &&
            settings.restDisplay != FramePlaybackSettings.REST_DISPLAY_DIM &&
            settings.restDisplay != FramePlaybackSettings.REST_DISPLAY_BLACK

    private fun applyRestDisplayMode() {
        when (settings.restDisplay) {
            FramePlaybackSettings.REST_DISPLAY_DIM -> {
                restingOverlay.setBackgroundColor(Color.argb(REST_DIM_ALPHA, 0, 0, 0))
                restingClock.visibility = View.GONE
                restingDate.visibility = View.GONE
                restingWeather.visibility = View.GONE
                restingWeatherPlace.visibility = View.GONE
            }
            FramePlaybackSettings.REST_DISPLAY_BLACK -> {
                restingOverlay.setBackgroundColor(context.getColor(R.color.pp_surface_rest))
                restingClock.visibility = View.GONE
                restingDate.visibility = View.GONE
                restingWeather.visibility = View.GONE
                restingWeatherPlace.visibility = View.GONE
            }
            else -> {
                restingOverlay.setBackgroundColor(context.getColor(R.color.pp_surface_rest))
                restingClock.visibility = View.VISIBLE
                restingDate.visibility = if (settings.dateEnabled) View.VISIBLE else View.GONE
            }
        }
        updateClock()
    }

    private fun buildSafeOverlay() {
        safeOverlay.setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        val message = TextView(context).apply {
            text = resources.getString(R.string.player_revoked_fallback)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
        }
        safeOverlay.addView(centered(message))
    }

    private fun buildFailedOverlay() {
        failedOverlay.addView(centered(overlayBody(R.string.player_decode_failed_notice)))
        val retry = primaryButton(context, R.string.common_retry).apply {
            setOnClickListener { engine?.userRetry() }
        }
        failedOverlay.addView(centered(retry, R.dimen.pp_space_24))
        val exit = secondaryButton(context, R.string.player_exit).apply {
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            setOnClickListener { onExit?.invoke() }
        }
        failedOverlay.addView(centered(exit, R.dimen.pp_space_12))
    }

    private fun centered(view: View, marginRes: Int? = null): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(
            view,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (marginRes != null) {
            lp.topMargin = resources.getDimensionPixelSize(marginRes)
        }
        row.layoutParams = lp
        return row
    }

    private companion object {
        const val MENU_REMOVE_CURRENT = 1
        const val MENU_EXIT = 2
        const val MENU_SETTINGS = 3
        const val CONTROLS_HIDE_MS = 4_000L
        const val CLOCK_TICK_MS = 30_000L
        const val SECOND_TICK_MS = 1_000L
        const val SECOND_TICK_MIN_DELAY_MS = 50L
        const val CONTROLS_ALPHA = 0.94f
        const val SCRIM_ALPHA = 0.35f
        const val REST_DIM_ALPHA = 191
        const val VIDEO_FADE_MS = 800L
    }
}
