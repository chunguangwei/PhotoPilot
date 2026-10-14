package dev.photopilot.scaffold.data.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import dev.photopilot.scaffold.data.logic.RenderGeometry
import java.io.File

object BitmapRenderer {

    fun decodeToTarget(file: File, targetW: Int, targetH: Int, exifOrientation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return applyOrientation(raw, exifOrientation)
    }

    fun edgeColor(bitmap: Bitmap): String? {
        val w = maxOf(1, bitmap.width / 8)
        val h = maxOf(1, bitmap.height / 8)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val asLong = LongArray(pixels.size) { pixels[it].toLong() and 0xFFFF_FFFFL }
        return runCatching { RenderGeometry.edgeColor(asLong, w, h) }.getOrNull()
    }

    fun blurBackground(source: Bitmap): Bitmap {
        val (w, h) = RenderGeometry.blurBackgroundSize(source.width.toLong(), source.height.toLong())
        val small = Bitmap.createScaledBitmap(source, w.toInt(), h.toInt(), true)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val blurred = BoxBlur.blur(pixels, small.width, small.height, radius = 8, passes = 3)
        return Bitmap.createBitmap(blurred, small.width, small.height, Bitmap.Config.ARGB_8888)
    }

    internal fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= targetW && h / 2 >= targetH) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

object BoxBlur {

    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
        var current = pixels.copyOf()
        repeat(passes) {
            current = horizontalPass(current, width, height, radius)
            current = verticalPass(current, width, height, radius)
        }
        return current
    }

    private fun horizontalPass(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val output = IntArray(input.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            val rowOffset = y * width
            for (x in -radius..radius) {
                val pixel = input[rowOffset + x.coerceIn(0, width - 1)]
                sumA += (pixel ushr 24) and 0xFF
                sumR += (pixel ushr 16) and 0xFF
                sumG += (pixel ushr 8) and 0xFF
                sumB += pixel and 0xFF
            }
            for (x in 0 until width) {
                output[rowOffset + x] =
                    ((sumA / window).toInt() shl 24) or
                    ((sumR / window).toInt() shl 16) or
                    ((sumG / window).toInt() shl 8) or
                    (sumB / window).toInt()
                val addIndex = (x + radius + 1).coerceAtMost(width - 1)
                val removeIndex = (x - radius).coerceAtLeast(0)
                val addPixel = input[rowOffset + addIndex]
                val removePixel = input[rowOffset + removeIndex]
                sumA += ((addPixel ushr 24) and 0xFF) - ((removePixel ushr 24) and 0xFF)
                sumR += ((addPixel ushr 16) and 0xFF) - ((removePixel ushr 16) and 0xFF)
                sumG += ((addPixel ushr 8) and 0xFF) - ((removePixel ushr 8) and 0xFF)
                sumB += (addPixel and 0xFF) - (removePixel and 0xFF)
            }
        }
        return output
    }

    private fun verticalPass(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val output = IntArray(input.size)
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sumA = 0L
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            for (y in -radius..radius) {
                val pixel = input[y.coerceIn(0, height - 1) * width + x]
                sumA += (pixel ushr 24) and 0xFF
                sumR += (pixel ushr 16) and 0xFF
                sumG += (pixel ushr 8) and 0xFF
                sumB += pixel and 0xFF
            }
            for (y in 0 until height) {
                output[y * width + x] =
                    ((sumA / window).toInt() shl 24) or
                    ((sumR / window).toInt() shl 16) or
                    ((sumG / window).toInt() shl 8) or
                    (sumB / window).toInt()
                val addIndex = (y + radius + 1).coerceAtMost(height - 1)
                val removeIndex = (y - radius).coerceAtLeast(0)
                val addPixel = input[addIndex * width + x]
                val removePixel = input[removeIndex * width + x]
                sumA += ((addPixel ushr 24) and 0xFF) - ((removePixel ushr 24) and 0xFF)
                sumR += ((addPixel ushr 16) and 0xFF) - ((removePixel ushr 16) and 0xFF)
                sumG += ((addPixel ushr 8) and 0xFF) - ((removePixel ushr 8) and 0xFF)
                sumB += (addPixel and 0xFF) - (removePixel and 0xFF)
            }
        }
        return output
    }
}
