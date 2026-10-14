package dev.photopilot.scaffold.ui.launch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.BrandMarkView
import dev.photopilot.scaffold.ui.widget.reduceMotionEnabled
import dev.photopilot.scaffold.ui.widget.secondaryButton

@SuppressLint("ViewConstructor")
class LaunchView(
    context: Context,
    private val presentation: LaunchPresentationState,
) : FrameLayout(context) {

    var onReady: (() -> Unit)? = null
    private val timer = Handler(Looper.getMainLooper())
    private val tick = Runnable { render() }
    private val route = Runnable { routeIfActive() }
    private val reveal = Runnable {
        if (isPresentationActive() && !routed && presentation.claimReveal()) {
            revealOnce()
        }
    }
    private val mark = BrandMarkView(context)
    private val wordmark = TextView(context)
    private val tagline = TextView(context)
    private var motion: ValueAnimator? = null
    private var constructed = false
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val durationLabel = TextView(context)
    private val footer = LinearLayout(context)
    private val skip = secondaryButton(context, R.string.common_skip)
    private var initialized = false
    private var routed = false
    private var waitingSince: Long? = null
    private var reduceMotion = false

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val inset = resources.getDimensionPixelSize(R.dimen.pp_content_inset_compact)
        val space8 = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        val space24 = resources.getDimensionPixelSize(R.dimen.pp_space_24)
        val touchTarget = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val central = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, space24, inset, space24)
        }
        central.addView(mark, LinearLayout.LayoutParams(touchTarget * 3, touchTarget * 3))
        central.addView(wordmark.apply {
            setText(R.string.brand_name)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_title))
            setTextColor(context.getColor(R.color.pp_text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        central.addView(tagline.apply {
            setText(R.string.brand_tagline)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = space8
        })
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        central.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = space24
        })
        scroll.addView(central, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        footer.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, space8, inset, inset)
        }
        durationLabel.apply {
            setText(R.string.launch_brand_presentation)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        progress.apply {
            isIndeterminate = false
            max = 1000
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.pp_text_primary))
            progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.pp_text_secondary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        footer.addView(durationLabel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        footer.addView(progress, LinearLayout.LayoutParams(touchTarget * 3, space8).apply { topMargin = space8 })
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        skip.apply {
            minimumWidth = touchTarget
            setTextColor(context.getColor(R.color.pp_text_primary))
            val highlight = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, highlight, true)
            setBackgroundResource(highlight.resourceId)
            setOnClickListener {
                presentation.skip()
                finishMotion()
                render()
            }
        }
        addView(skip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = inset
            marginEnd = inset
        })
        val adjustInsets = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = scroll.layoutParams as LayoutParams
            val top = skip.bottom + space8
            val bottom = footer.height
            if (params.topMargin != top || params.bottomMargin != bottom) {
                params.topMargin = top
                params.bottomMargin = bottom
                scroll.layoutParams = params
            }
        }
        skip.addOnLayoutChangeListener(adjustInsets)
        footer.addOnLayoutChangeListener(adjustInsets)
        constructed = true
    }

    private fun revealOnce() {
        finishMotion()
        if (reduceMotion || !ValueAnimator.areAnimatorsEnabled()) return
        val elapsed = motionElapsedMillis()
        if (elapsed >= LaunchMotionTimeline.DURATION_MILLIS) return
        applyMotion(elapsed)
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LaunchMotionTimeline.DURATION_MILLIS - elapsed
            interpolator = LinearInterpolator()
            addUpdateListener {
                val current = motionElapsedMillis()
                if (!isPresentationActive() || routed || !ValueAnimator.areAnimatorsEnabled() ||
                    current >= LaunchMotionTimeline.DURATION_MILLIS) {
                    finishMotion()
                } else {
                    applyMotion(current)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishMotion()
                }
            })
        }
        motion?.start()
    }

    private fun motionElapsedMillis(): Long = LaunchMotionTimeline.elapsedFromRemaining(
        presentation.snapshot(LaunchPresentationState.Initialization.PENDING).remainingMillis,
    )

    private fun applyMotion(elapsedMillis: Long) {
        val frame = LaunchMotionTimeline.sample(elapsedMillis)
        mark.renderReveal(frame.frameFraction, frame.dotAlpha, frame.dotScale)
        wordmark.alpha = frame.wordmarkAlpha
        wordmark.translationY = frame.wordmarkOffsetDp * resources.displayMetrics.density
        tagline.alpha = frame.taglineAlpha
        tagline.translationY = frame.taglineOffsetDp * resources.displayMetrics.density
    }

    private fun finishMotion() {
        if (!constructed) return
        val previous = motion
        motion = null
        previous?.removeAllUpdateListeners()
        previous?.removeAllListeners()
        previous?.cancel()
        mark.finishReveal()
        for (label in arrayOf(wordmark, tagline)) {
            label.alpha = 1f
            label.translationX = 0f
            label.translationY = 0f
            label.scaleX = 1f
            label.scaleY = 1f
        }
    }

    fun initializationReady() {
        initialized = true
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduceMotion = reduceMotionEnabled(context)
        render()
        timer.post(reveal)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updatePresentationActivity()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        updatePresentationActivity()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updatePresentationActivity()
    }

    private fun isPresentationActive(): Boolean = LaunchPresentationState.isActive(
        attached = isAttachedToWindow,
        windowVisible = windowVisibility == VISIBLE,
        shown = isShown,
        windowFocused = hasWindowFocus(),
    )

    private fun updatePresentationActivity() {
        if (!constructed) return
        timer.removeCallbacks(tick)
        timer.removeCallbacks(reveal)
        timer.removeCallbacks(route)
        if (isPresentationActive()) {
            reduceMotion = reduceMotionEnabled(context)
            render()
            timer.post(reveal)
        } else {
            finishMotion()
        }
    }

    private fun routeIfActive() {
        if (routed) return
        val snapshot = presentation.snapshot(
            if (initialized) LaunchPresentationState.Initialization.READY else LaunchPresentationState.Initialization.PENDING,
            active = isPresentationActive(),
        )
        if (!snapshot.canRoute) return
        val callback = onReady ?: return
        routed = true
        timer.removeCallbacks(tick)
        timer.removeCallbacks(reveal)
        finishMotion()
        callback()
    }

    private fun render() {
        timer.removeCallbacks(tick)
        timer.removeCallbacks(route)
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown || routed) return
        val active = isPresentationActive()
        val snapshot = presentation.snapshot(
            if (initialized) LaunchPresentationState.Initialization.READY else LaunchPresentationState.Initialization.PENDING,
            active = active,
        )
        val branding = snapshot.phase == LaunchPresentationState.Phase.BRAND
        skip.visibility = if (branding) VISIBLE else INVISIBLE
        footer.visibility = if (branding) VISIBLE else INVISIBLE
        status.visibility = if (snapshot.phase == LaunchPresentationState.Phase.WAITING) VISIBLE else GONE
        when (snapshot.phase) {
            LaunchPresentationState.Phase.BRAND -> {
                if (!active || reduceMotion || motionElapsedMillis() >= LaunchMotionTimeline.DURATION_MILLIS) finishMotion()
                progress.visibility = if (reduceMotion) INVISIBLE else VISIBLE
                progress.progress = (snapshot.progress * progress.max).toInt()
                if (active) timer.postDelayed(tick, if (reduceMotion) snapshot.remainingMillis else minOf(50L, snapshot.remainingMillis))
            }
            LaunchPresentationState.Phase.WAITING -> {
                finishMotion()
                val now = SystemClock.elapsedRealtime()
                val since = waitingSince ?: now.also { waitingSince = it }
                val elapsed = now - since
                status.setText(if (elapsed >= 3_000L) R.string.launch_taking_longer else R.string.launch_preparing_library)
                if (active && elapsed < 3_000L) timer.postDelayed(tick, 3_000L - elapsed)
            }
            LaunchPresentationState.Phase.READY -> {
                finishMotion()
                if (snapshot.canRoute) timer.post(route)
            }
            LaunchPresentationState.Phase.FAILED -> finishMotion()
        }
    }

    override fun onDetachedFromWindow() {
        timer.removeCallbacks(tick)
        timer.removeCallbacks(reveal)
        timer.removeCallbacks(route)
        finishMotion()
        super.onDetachedFromWindow()
    }
}

internal object LaunchMotionTimeline {
    const val DURATION_MILLIS = 5_600L

    data class Frame(
        val frameFraction: Float,
        val dotAlpha: Float,
        val dotScale: Float,
        val wordmarkAlpha: Float,
        val wordmarkOffsetDp: Float,
        val taglineAlpha: Float,
        val taglineOffsetDp: Float,
    )

    fun elapsedFromRemaining(remainingMillis: Long): Long =
        LaunchPresentationState.BRAND_DURATION_MILLIS -
            remainingMillis.coerceIn(0L, LaunchPresentationState.BRAND_DURATION_MILLIS)

    fun sample(elapsedMillis: Long): Frame {
        val elapsed = elapsedMillis.coerceIn(0L, DURATION_MILLIS)
        val dotReveal = progress(elapsed, 350L, 850L)
        val breath = if (elapsed in 1_200L until DURATION_MILLIS) {
            val cycle = ((elapsed - 1_200L) % 2_200L).toFloat() / 1_100f
            ease(if (cycle <= 1f) cycle else 2f - cycle)
        } else 0f
        val wordmark = progress(elapsed, 250L, 900L)
        val tagline = progress(elapsed, 450L, 1_100L)
        return Frame(
            frameFraction = progress(elapsed, 0L, 1_200L),
            dotAlpha = dotReveal * (1f - 0.18f * breath),
            dotScale = 0.7f + 0.3f * dotReveal + 0.08f * breath,
            wordmarkAlpha = wordmark,
            wordmarkOffsetDp = 8f * (1f - wordmark),
            taglineAlpha = tagline,
            taglineOffsetDp = 6f * (1f - tagline),
        )
    }

    private fun progress(elapsed: Long, start: Long, end: Long): Float =
        ease(((elapsed - start).toFloat() / (end - start)).coerceIn(0f, 1f))

    private fun ease(fraction: Float): Float = fraction * fraction * (3f - 2f * fraction)
}
