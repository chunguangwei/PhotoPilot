package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.ClipEmbeddingMath
import org.junit.Assert.*
import org.junit.Test

class ClipEmbeddingMathTest {
    @Test fun meanPoolingUsesAttentionMaskAndNormalizesNonzeroVectors() {
        val pooled = ClipEmbeddingMath.meanPool(arrayOf(FloatArray(768) { 1f }, FloatArray(768) { 3f }, FloatArray(768) { 100f }), longArrayOf(1, 1, 0))
        assertArrayEquals(FloatArray(768) { 2f }, pooled, 0f)
        val unit = ClipEmbeddingMath.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(.6f, .8f), unit, 1e-6f)
        assertThrows(IllegalArgumentException::class.java) { ClipEmbeddingMath.normalize(floatArrayOf(0f, 0f)) }
        assertThrows(IllegalArgumentException::class.java) { ClipEmbeddingMath.normalize(floatArrayOf(Float.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { ClipEmbeddingMath.meanPool(arrayOf(FloatArray(768)), longArrayOf(0)) }
    }
}
