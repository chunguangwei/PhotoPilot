package dev.photopilot.scaffold.data.aiframes

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** CLIP's Pillow-compatible bicubic resize, center crop and RGB normalization. */
object ClipImagePreprocessor {
    private const val SIZE = 224
    private val mean = floatArrayOf(.48145466f, .4578275f, .40821073f)
    private val deviation = floatArrayOf(.26862954f, .26130258f, .27577711f)
    private data class Coefficients(val first: Int, val weights: DoubleArray)

    fun pixelValues(width: Int, height: Int, argb: IntArray): FloatArray {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height)
        val resizedWidth = if (width <= height) SIZE else (width.toLong() * SIZE / height).toInt()
        val resizedHeight = if (height <= width) SIZE else (height.toLong() * SIZE / width).toInt()
        val horizontal = coefficients(width, resizedWidth, (resizedWidth - SIZE) / 2)
        val vertical = coefficients(height, resizedHeight, (resizedHeight - SIZE) / 2)
        // Keep the cropped columns only, including for very wide or tall source images.
        val intermediate = IntArray(height * SIZE * 3)
        for (y in 0 until height) {
            for (x in 0 until SIZE) {
                val filter = horizontal[x]
                for (channel in 0..2) {
                    var value = 0.0
                    for (tap in filter.weights.indices) {
                        val color = argb[y * width + filter.first + tap]
                        value += (color shr (16 - channel * 8) and 255) * filter.weights[tap]
                    }
                    intermediate[(y * SIZE + x) * 3 + channel] = byteValue(value)
                }
            }
        }
        val output = FloatArray(3 * SIZE * SIZE)
        for (y in 0 until SIZE) {
            val filter = vertical[y]
            for (x in 0 until SIZE) {
                for (channel in 0..2) {
                    var value = 0.0
                    for (tap in filter.weights.indices) value += intermediate[((filter.first + tap) * SIZE + x) * 3 + channel] * filter.weights[tap]
                    output[channel * SIZE * SIZE + y * SIZE + x] = (byteValue(value) / 255f - mean[channel]) / deviation[channel]
                }
            }
        }
        return output
    }

    private fun coefficients(inputSize: Int, outputSize: Int, cropOffset: Int): List<Coefficients> {
        val scale = inputSize.toDouble() / outputSize
        val filterScale = max(1.0, scale)
        val support = 2 * filterScale
        return (0 until SIZE).map { pixel ->
            val center = (cropOffset + pixel + .5) * scale
            val first = (center - support + .5).toInt().coerceAtLeast(0)
            val end = (center + support + .5).toInt().coerceAtMost(inputSize)
            val weights = DoubleArray(end - first) { tap -> cubic((first + tap - center + .5) / filterScale) }
            val total = weights.sum()
            require(total > 0)
            for (tap in weights.indices) weights[tap] /= total
            Coefficients(first, weights)
        }
    }

    private fun cubic(distance: Double): Double {
        val x = abs(distance)
        return when {
            x < 1 -> (1.5 * x - 2.5) * x * x + 1
            x < 2 -> ((-.5 * x + 2.5) * x - 4) * x + 2
            else -> 0.0
        }
    }

    private fun byteValue(value: Double): Int = floor(value + .5).toInt().coerceIn(0, 255)
}
