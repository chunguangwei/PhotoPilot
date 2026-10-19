package dev.photopilot.scaffold.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.photopilot.scaffold.R

class FlipClockView(context: Context) : LinearLayout(context) {

    private val hourCard = FlipCardView(context)
    private val minuteCard = FlipCardView(context)
    private val secondCard = FlipCardView(context)
    private var appliedScale = 1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val spacing = resources.getDimensionPixelSize(R.dimen.pp_space_8)
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
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val fullWidth = (hourCard.cardWidth + minuteCard.cardWidth + secondCard.cardWidth) +
            (childCount - 1) * resources.getDimensionPixelSize(R.dimen.pp_space_8) +
            paddingStart + paddingEnd
        val scale = if (maxWidth > 0 && fullWidth > maxWidth) {
            maxWidth.toFloat() / fullWidth.toFloat()
        } else {
            1f
        }
        if (scale != appliedScale) {
            appliedScale = scale
            for (index in 0 until childCount) {
                getChildAt(index).scaleX = scale
                getChildAt(index).scaleY = scale
            }
        }
        if (scale != 1f) {
            setMeasuredDimension(maxWidth, (measuredHeight * scale).toInt())
        }
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
        val cardWidth: Int
        private val cardHeight: Int
        private var currentText = ""
        private val topStatic: HalfView
        private val bottomStatic: HalfView
        private val topFlap: HalfView
        private val bottomFlap: HalfView
        private var flipInProgress = false
        private var pendingValue: String? = null
        private var flipToken = 0
        private var activeAnimator: ObjectAnimator? = null

        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val res = resources
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            cardWidth = (paint.measureText(SAMPLE) + padH * 2).toInt()
            cardHeight = ((paint.descent() - paint.ascent()) * HEIGHT_FACTOR).toInt()
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_photo_control))
                cornerRadius = res.getDimension(R.dimen.pp_radius_thumbnail)
            }
            bottomStatic = addHalf(top = false)
            topStatic = addHalf(top = true)
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
            bottomFlap = addHalf(top = false).apply { visibility = INVISIBLE }
            topFlap = addHalf(top = true).apply { visibility = INVISIBLE }
        }

        private fun addHalf(top: Boolean): HalfView {
            val half = HalfView(context, top)
            addView(half, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return half
        }

        fun setValue(value: String, animate: Boolean) {
            if (value == currentText && !flipInProgress) return
            if (!animate || currentText.isEmpty() || !isShown) {
                cancelFlip()
                currentText = value
                topStatic.value = value
                bottomStatic.value = value
                return
            }
            if (flipInProgress) {
                pendingValue = value
                return
            }
            startFlip(currentText, value)
        }

        private fun startFlip(old: String, new: String) {
            flipInProgress = true
            currentText = new
            val token = ++flipToken
            topStatic.value = old
            topStatic.shade = 0
            bottomStatic.value = old
            bottomStatic.shade = 0
            topFlap.value = old
            topFlap.shade = 0
            topFlap.rotationX = 0f
            topFlap.pivotY = height / 2f
            topFlap.visibility = VISIBLE
            val first = ObjectAnimator.ofFloat(topFlap, ROTATION_X, 0f, -FLIP_ANGLE).apply {
                duration = FLIP_TOP_MS
                interpolator = AccelerateInterpolator()
            }
            val firstShade = ValueAnimator.ofInt(0, SHADE_FALLEN).apply {
                duration = FLIP_TOP_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener { topFlap.shade = it.animatedValue as Int }
            }
            activeAnimator = first
            first.addListener(endListener(token) {
                topFlap.visibility = INVISIBLE
                topStatic.value = new
                topStatic.shade = SHADE_REVEALED
                bottomFlap.value = new
                bottomFlap.shade = SHADE_RAISED
                bottomFlap.rotationX = FLIP_ANGLE
                bottomFlap.pivotY = height / 2f
                bottomFlap.visibility = VISIBLE
                val second = ObjectAnimator.ofFloat(bottomFlap, ROTATION_X, FLIP_ANGLE, 0f).apply {
                    duration = FLIP_BOTTOM_MS
                    interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
                }
                val secondShade = ValueAnimator.ofInt(SHADE_RAISED, 0).apply {
                    duration = FLIP_BOTTOM_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { bottomFlap.shade = it.animatedValue as Int }
                }
                val revealShade = ValueAnimator.ofInt(SHADE_REVEALED, 0).apply {
                    duration = FLIP_BOTTOM_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { topStatic.shade = it.animatedValue as Int }
                }
                activeAnimator = second
                second.addListener(endListener(token) { finishFlip(new) })
                second.start()
                secondShade.start()
                revealShade.start()
            })
            first.start()
            firstShade.start()
        }

        private fun endListener(token: Int, action: () -> Unit): AnimatorListenerAdapter =
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token == flipToken) action()
                }
            }

        private fun finishFlip(value: String) {
            activeAnimator = null
            bottomFlap.visibility = INVISIBLE
            bottomStatic.value = value
            flipInProgress = false
            val pending = pendingValue
            pendingValue = null
            if (pending != null && pending != value) {
                setValue(pending, animate = true)
            }
        }

        private fun cancelFlip() {
            flipToken++
            activeAnimator?.cancel()
            activeAnimator = null
            flipInProgress = false
            pendingValue = null
            topFlap.visibility = INVISIBLE
            bottomFlap.visibility = INVISIBLE
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cardHeight, MeasureSpec.EXACTLY),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            topFlap.cameraDistance = h * CAMERA_DISTANCE_FACTOR
            bottomFlap.cameraDistance = h * CAMERA_DISTANCE_FACTOR
        }

        override fun onDetachedFromWindow() {
            cancelFlip()
            if (currentText.isNotEmpty()) {
                topStatic.value = currentText
                bottomStatic.value = currentText
            }
            super.onDetachedFromWindow()
        }

        private inner class HalfView(context: Context, private val top: Boolean) : View(context) {

            var value: String = ""
                set(text) {
                    field = text
                    invalidate()
                }

            var shade: Int = 0
                set(alpha) {
                    field = alpha
                    invalidate()
                }

            init {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            override fun onDraw(canvas: Canvas) {
                if (value.isEmpty()) return
                canvas.save()
                if (top) {
                    canvas.clipRect(0, 0, width, height / 2)
                } else {
                    canvas.clipRect(0, height / 2, width, height)
                }
                val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText(value, width / 2f, baseline, paint)
                if (shade > 0) {
                    canvas.drawColor(Color.argb(shade.coerceAtMost(255), 0, 0, 0))
                }
                canvas.restore()
            }
        }

        private companion object {
            const val HEIGHT_FACTOR = 1.5f
            const val FLIP_ANGLE = 90f
            const val FLIP_TOP_MS = 170L
            const val FLIP_BOTTOM_MS = 340L
            const val OVERSHOOT_TENSION = 0.9f
            const val SHADE_FALLEN = 110
            const val SHADE_RAISED = 130
            const val SHADE_REVEALED = 80
            const val CAMERA_DISTANCE_FACTOR = 8f
        }
    }
}
