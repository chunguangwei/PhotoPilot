package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.provider.Settings
import android.view.View
import dev.photopilot.scaffold.R

class BrandMarkView(context: Context) : View(context) {

    private val frame = Path().apply {
        moveTo(164f, 56f)
        lineTo(84f, 56f)
        quadTo(56f, 56f, 56f, 84f)
        lineTo(56f, 172f)
        quadTo(56f, 200f, 84f, 200f)
        lineTo(172f, 200f)
        quadTo(200f, 200f, 200f, 172f)
        lineTo(200f, 112f)
    }
    private val measure = PathMeasure(frame, false)
    private val revealed = Path()
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pp_text_primary)
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.pp_accent_brand)
    }
    private var fraction = 1f
    private var dotOpacity = 1f
    private var dotScale = 1f
    private var constructed = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        constructed = true
    }

    internal fun renderReveal(frameFraction: Float, opacity: Float, scale: Float) {
        fraction = frameFraction.coerceIn(0f, 1f)
        dotOpacity = opacity.coerceIn(0f, 1f)
        dotScale = scale
        invalidate()
    }

    fun finishReveal() {
        if (!constructed) return
        fraction = 1f
        dotOpacity = 1f
        dotScale = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (side <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - side) / 2f,
            paddingTop + (height - paddingTop - paddingBottom - side) / 2f)
        canvas.scale(side / 256f, side / 256f)
        if (fraction >= 1f) {
            canvas.drawPath(frame, framePaint)
        } else {
            revealed.rewind()
            measure.getSegment(0f, measure.length * fraction, revealed, true)
            canvas.drawPath(revealed, framePaint)
        }
        dotPaint.alpha = (255 * dotOpacity).toInt()
        canvas.drawCircle(200f, 56f, 16f * dotScale, dotPaint)
        canvas.restoreToCount(checkpoint)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) finishReveal()
    }

    override fun onDetachedFromWindow() {
        finishReveal()
        super.onDetachedFromWindow()
    }
}

internal fun reduceMotionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
