package dev.photopilot.scaffold.ui.welcome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.util.concurrent.Executors

class DemoPlayerView(context: Context) : FrameLayout(context) {

    var onExit: (() -> Unit)? = null

    private val imageA: ImageView
    private val imageB: ImageView
    private var showingA = true
    private var slides: List<Bitmap> = emptyList()
    private var current = 0
    private var running = false
    private val executor = Executors.newSingleThreadExecutor()

    private val advanceRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            showSlide((current + 1) % slides.size.coerceAtLeast(1))
            postDelayed(this, HOLD_MS)
        }
    }

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_rest))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        imageA = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        imageB = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(imageA, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageB, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            alpha = CONTROLS_ALPHA
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_photo_control))
                cornerRadius = res.getDimension(R.dimen.pp_radius_modal)
            }
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            val padV = res.getDimensionPixelSize(R.dimen.pp_space_12)
            setPadding(padH, padV, padH, padV)
        }
        val badge = TextView(context).apply {
            text = res.getString(R.string.common_demo_badge)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_caption))
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_12)
            val padV = res.getDimensionPixelSize(R.dimen.pp_space_4)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_accent_brand))
                cornerRadius = res.getDimension(R.dimen.pp_radius_thumbnail)
            }
        }
        val notice = TextView(context).apply {
            text = res.getString(R.string.welcome_demo_notice)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            gravity = Gravity.CENTER
        }
        val exit = secondaryButton(context, R.string.welcome_demo_exit).apply {
            setTextColor(context.getColor(R.color.pp_text_on_photo))
            setOnClickListener { onExit?.invoke() }
        }
        bar.addView(badge, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bar.addView(
            notice,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        bar.addView(
            exit,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                marginStart = inset
                marginEnd = inset
                bottomMargin = inset
            },
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || slides.isNotEmpty()) return
        executor.execute {
            val bundled = loadBundledSlides(w, h)
            val generated = if (bundled.isNotEmpty()) {
                bundled
            } else {
                (0 until SLIDE_COUNT).map { index -> renderSlide(index, w, h) }
            }
            post {
                if (slides.isNotEmpty()) return@post
                slides = generated
                if (generated.isNotEmpty()) {
                    running = true
                    showSlide(0)
                    postDelayed(advanceRunnable, HOLD_MS)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(advanceRunnable)
        executor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun showSlide(index: Int) {
        if (slides.isEmpty()) return
        current = ((index % slides.size) + slides.size) % slides.size
        val incoming = if (showingA) imageB else imageA
        val outgoing = if (showingA) imageA else imageB
        incoming.setImageBitmap(slides[current])
        if (reduceMotionEnabled()) {
            incoming.animate().cancel()
            outgoing.animate().cancel()
            incoming.alpha = 1f
            outgoing.alpha = 0f
        } else {
            incoming.animate().alpha(1f).setDuration(FADE_MS).start()
            outgoing.animate().alpha(0f).setDuration(FADE_MS).start()
        }
        showingA = !showingA
    }

    private fun loadBundledSlides(width: Int, height: Int): List<Bitmap> = BUNDLED_SLIDES.mapNotNull { name ->
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open("demo/$name").use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds, width * 2, height * 2)
            }
            context.assets.open("demo/$name").use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun sampleSize(bounds: BitmapFactory.Options, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun renderSlide(index: Int, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val palette = slidePalette(index)
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    width.toFloat() * (0.4f + 0.15f * index),
                    height.toFloat(),
                    palette.first,
                    palette.second,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        val glowRadius = width * (0.28f + 0.05f * (index % 3))
        val glowX = width * (0.24f + 0.16f * (index % 4))
        val glowY = height * (0.28f + 0.12f * ((index + 1) % 4))
        canvas.drawCircle(
            glowX,
            glowY,
            glowRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    glowX,
                    glowY,
                    glowRadius,
                    palette.third,
                    android.graphics.Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawCircle(
            glowX,
            glowY,
            width * BRAND_DOT_RATIO,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = context.getColor(R.color.pp_accent_brand)
            },
        )
        return bitmap
    }

    private fun slidePalette(index: Int): Triple<Int, Int, Int> {
        val brand = context.getColor(R.color.pp_accent_brand)
        val action = context.getColor(R.color.pp_accent_action)
        val rest = context.getColor(R.color.pp_surface_rest)
        val canvasColor = context.getColor(R.color.pp_surface_canvas)
        val panel = context.getColor(R.color.pp_surface_panel)
        return when (index % SLIDE_COUNT) {
            0 -> Triple(rest, action, brand)
            1 -> Triple(action, rest, canvasColor)
            2 -> Triple(rest, brand, action)
            3 -> Triple(brand, rest, panel)
            else -> Triple(rest, canvasColor, brand)
        }
    }

    private fun reduceMotionEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private companion object {
        const val SLIDE_COUNT = 5
        private val BUNDLED_SLIDES = listOf(
            "demo_lake.jpg", "demo_sunset.jpg", "demo_forest.jpg", "demo_city.jpg",
            "demo_flowers.jpg", "demo_dog.jpg", "demo_coffee.jpg", "demo_snow.jpg",
        )
        const val HOLD_MS = 3_000L
        const val FADE_MS = 800L
        const val CONTROLS_ALPHA = 0.94f
        const val BRAND_DOT_RATIO = 0.045f
    }
}
