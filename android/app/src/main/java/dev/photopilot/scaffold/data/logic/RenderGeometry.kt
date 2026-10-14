package dev.photopilot.scaffold.data.logic

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class FitRect(val x: Long, val y: Long, val width: Long, val height: Long)

object RenderGeometry {

    const val BLUR_BACKGROUND_LONG_EDGE = 256

    fun aspectFit(srcW: Long, srcH: Long, dstW: Long, dstH: Long): FitRect =
        scaleRect(srcW, srcH, dstW, dstH, min(dstW.toDouble() / srcW, dstH.toDouble() / srcH))

    fun aspectFill(srcW: Long, srcH: Long, dstW: Long, dstH: Long): FitRect =
        scaleRect(srcW, srcH, dstW, dstH, max(dstW.toDouble() / srcW, dstH.toDouble() / srcH))

    private fun scaleRect(srcW: Long, srcH: Long, dstW: Long, dstH: Long, scale: Double): FitRect {
        val w = (srcW * scale).roundToLong()
        val h = (srcH * scale).roundToLong()
        return FitRect(
            x = (dstW - w) / 2,
            y = (dstH - h) / 2,
            width = w,
            height = h,
        )
    }

    fun decodeTarget(viewW: Long, viewH: Long, scale: Long, cap: Long): Pair<Long, Long> {
        var w = viewW * scale
        var h = viewH * scale
        val longest = max(w, h)
        if (longest > cap) {
            w = floor(w.toDouble() * cap / longest).toLong()
            h = floor(h.toDouble() * cap / longest).toLong()
        }
        return w to h
    }

    fun blurBackgroundSize(srcW: Long, srcH: Long): Pair<Long, Long> {
        val longest = max(srcW, srcH)
        if (longest <= BLUR_BACKGROUND_LONG_EDGE) return srcW to srcH
        val scale = BLUR_BACKGROUND_LONG_EDGE.toDouble() / longest
        return max(1L, floor(srcW * scale).toLong()) to max(1L, floor(srcH * scale).toLong())
    }

    fun cacheKey(
        assetId: String,
        assetVersion: Long,
        viewportW: Long,
        viewportH: Long,
        orientation: String,
        fitMode: String,
        qualityLevel: String,
    ): String = "$assetId:$assetVersion:${viewportW}x$viewportH:$orientation:$fitMode:$qualityLevel"

    fun edgeColor(pixels: LongArray, width: Int, height: Int): String {
        require(pixels.size == width * height) { "pixel count mismatch" }
        var count = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val isEdge = y == 0 || y == height - 1 || x == 0 || x == width - 1
                if (!isEdge) continue
                val pixel = pixels[y * width + x]
                sumR += (pixel shr 16) and 0xFF
                sumG += (pixel shr 8) and 0xFF
                sumB += pixel and 0xFF
                count += 1
            }
        }
        require(count > 0) { "no edge pixels" }
        val r = (sumR.toDouble() / count).roundToLong()
        val g = (sumG.toDouble() / count).roundToLong()
        val b = (sumB.toDouble() / count).roundToLong()
        return java.lang.String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b)
    }
}
