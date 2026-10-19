package dev.photopilot.scaffold.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.photopilot.scaffold.R

class FlipClockView(context: Context) : LinearLayout(context) {

    private val hourCard = FlipCardView(context)
    private val minuteCard = FlipCardView(context)
    private val secondCard = FlipCardView(context)
    private val spacing = resources.getDimensionPixelSize(R.dimen.pp_space_8)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        listOf(hourCard, minuteCard, secondCard).forEachIndexed { index, card ->
            addView(
                card,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = spacing
                },
            )
        }
    }

    fun setTime(hours: String, minutes: String, seconds: String, animate: Boolean = true) {
        hourCard.setValue(hours, animate)
        minuteCard.setValue(minutes, animate)
        secondCard.setValue(seconds, animate)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val naturalWidth = hourCard.cardWidth + minuteCard.cardWidth + secondCard.cardWidth
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - 2 * spacing)
            .coerceAtLeast(3)
        val scale = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            1f
        } else {
            (available.toFloat() / naturalWidth).coerceAtMost(1f)
        }
        for (index in 0 until childCount) {
            (getChildAt(index) as FlipCardView).sizeScale = scale
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        const val SAMPLE = "00"
    }

    class FlipCardView(context: Context) : FrameLayout(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.pp_text_on_photo)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = resources.getDimension(R.dimen.pp_text_clock)
            textAlign = Paint.Align.CENTER
        }
        private val surfaceColor = context.getColor(R.color.pp_surface_photo_control)
        private val naturalTextSize = paint.textSize
        val cardWidth: Int
        private val cardHeight: Int
        var sizeScale = 1f
        private var currentText = ""
        private val topStatic: HalfView
        private val bottomStatic: HalfView
        private val bottomFlap: HalfView
        private val topFlap: HalfView
        private var flipToken = 0
        private var activeAnimator: Animator? = null

        init {
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val res = resources
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            cardWidth = (paint.measureText(SAMPLE) + padH * 2).toInt()
            cardHeight = ((paint.descent() - paint.ascent()) * HEIGHT_FACTOR).toInt()
            background = GradientDrawable().apply {
                setColor(surfaceColor)
                cornerRadius = res.getDimension(R.dimen.pp_radius_thumbnail)
            }
            bottomStatic = addHalf(top = false)
            topStatic = addHalf(top = true)
            bottomFlap = addHalf(top = false).apply { visibility = INVISIBLE }
            topFlap = addHalf(top = true).apply { visibility = INVISIBLE }
            val divider = View(context).apply {
                setBackgroundColor(context.getColor(R.color.pp_surface_rest))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(
                divider,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, res.displayMetrics)
                        .toInt().coerceAtLeast(1),
                    Gravity.CENTER,
                ),
            )
        }

        private fun addHalf(top: Boolean): HalfView {
            val half = HalfView(context, top)
            addView(half, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return half
        }

        fun setValue(value: String, animate: Boolean) {
            if (value == currentText) return
            val old = currentText
            cancelFlip()
            currentText = value
            if (!animate || old.isEmpty() || !isShown || width == 0 ||
                windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()
            ) {
                settle()
                return
            }
            startFlip(old, value)
        }

        private fun startFlip(old: String, new: String) {
            val token = ++flipToken
            topStatic.value = old
            bottomStatic.value = new
            bottomFlap.value = old
            topFlap.value = new
            bottomFlap.visibility = VISIBLE
            topFlap.visibility = INVISIBLE
            bottomFlap.pivotY = height / 2f
            topFlap.pivotY = height / 2f
            bottomFlap.rotationX = 0f
            topFlap.rotationX = -90f
            val durationScale = Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            )
            val flip = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (FLIP_MS / durationScale.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val t = animator.animatedFraction
                    val progress = t * t * (3f - 2f * t)
                    bottomFlap.rotationX = (180f * progress).coerceAtMost(90f)
                    topFlap.rotationX = (180f * (progress - 1f)).coerceAtLeast(-90f)
                    bottomFlap.shade = MAX_SHADE * (2f * progress).coerceAtMost(1f)
                    topFlap.shade = MAX_SHADE * (2f * (1f - progress)).coerceAtMost(1f)
                    bottomFlap.visibility = if (progress < 0.5f) VISIBLE else INVISIBLE
                    topFlap.visibility = if (progress >= 0.5f) VISIBLE else INVISIBLE
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (token == flipToken) {
                            activeAnimator = null
                            settle()
                        }
                    }
                })
            }
            activeAnimator = flip
            flip.start()
        }

        private fun settle() {
            topStatic.value = currentText
            bottomStatic.value = currentText
            bottomFlap.visibility = INVISIBLE
            topFlap.visibility = INVISIBLE
            bottomFlap.rotationX = 0f
            topFlap.rotationX = 0f
            bottomFlap.shade = 0f
            topFlap.shade = 0f
        }

        private fun cancelFlip() {
            flipToken++
            activeAnimator?.cancel()
            activeAnimator = null
            settle()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec((cardWidth * sizeScale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((cardHeight * sizeScale).toInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cancelFlip()
            paint.textSize = naturalTextSize * sizeScale
            bottomFlap.cameraDistance = h * CAMERA_DISTANCE_FACTOR
            topFlap.cameraDistance = h * CAMERA_DISTANCE_FACTOR
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != VISIBLE && activeAnimator != null) cancelFlip()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility != VISIBLE && activeAnimator != null) cancelFlip()
        }

        override fun onDetachedFromWindow() {
            cancelFlip()
            super.onDetachedFromWindow()
        }

        private inner class HalfView(context: Context, private val top: Boolean) : View(context) {

            private val surfacePaint = Paint().apply { color = surfaceColor }
            private val shadePaint = Paint().apply { color = android.graphics.Color.BLACK }
            private var baseline = 0f
            var value: String = ""
                set(text) {
                    if (field == text) return
                    field = text
                    invalidate()
                }

            var shade = 0f
                set(alpha) {
                    if (field == alpha) return
                    field = alpha
                    invalidate()
                }

            init {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                baseline = h / 2f - (paint.descent() + paint.ascent()) / 2f
            }

            override fun onDraw(canvas: Canvas) {
                canvas.save()
                if (top) {
                    canvas.clipRect(0f, 0f, width.toFloat(), height / 2f)
                } else {
                    canvas.clipRect(0f, height / 2f, width.toFloat(), height.toFloat())
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), surfacePaint)
                canvas.drawText(value, width / 2f, baseline, paint)
                if (shade > 0f) {
                    shadePaint.alpha = (255 * shade).toInt()
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
                }
                canvas.restore()
            }
        }

        private companion object {
            const val HEIGHT_FACTOR = 1.5f
            const val FLIP_MS = 920L
            const val MAX_SHADE = 0.3f
            const val CAMERA_DISTANCE_FACTOR = 8f
        }
    }
}
