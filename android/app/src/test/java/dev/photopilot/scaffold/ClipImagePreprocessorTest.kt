package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.ClipImagePreprocessor
import org.junit.Assert.*
import org.junit.Test

class ClipImagePreprocessorTest {
    @Test fun bicubicConstantImageHasExpectedRgbNormalizationAndNchwPacking() {
        val pixels = ClipImagePreprocessor.pixelValues(64, 32, IntArray(64 * 32) { 0xFF804020.toInt() })
        assertEquals(3 * 224 * 224, pixels.size)
        val expected = floatArrayOf((128f / 255 - .48145466f) / .26862954f, (64f / 255 - .4578275f) / .26130258f, (32f / 255 - .40821073f) / .27577711f)
        for (channel in 0..2) assertTrue(pixels.sliceArray(channel * 224 * 224 until (channel + 1) * 224 * 224).all { kotlin.math.abs(it - expected[channel]) < 1e-6 })
    }
    @Test fun wideImageUsesCenterCropAndExtremeAspectRatiosStayBounded() {
        val pixels = IntArray(800 * 400) { index -> if (index % 800 in 200..599) 0xFF00FF00.toInt() else 0xFFFF0000.toInt() }
        val result = ClipImagePreprocessor.pixelValues(800, 400, pixels)
        assertEquals((1f - .4578275f) / .26130258f, result[224 * 224 + 112 * 224 + 112], 1e-6f)
        assertEquals(3 * 224 * 224, ClipImagePreprocessor.pixelValues(1024, 1, IntArray(1024) { 0xFF000000.toInt() }).size)
    }
}
