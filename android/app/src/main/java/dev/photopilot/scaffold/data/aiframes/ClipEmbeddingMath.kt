package dev.photopilot.scaffold.data.aiframes

import kotlin.math.sqrt

object ClipEmbeddingMath {
    fun meanPool(hidden: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        require(hidden.isNotEmpty() && hidden.size == attentionMask.size)
        require(hidden.all { it.size == 768 && it.all(Float::isFinite) })
        require(attentionMask.all { it == 0L || it == 1L })
        val count = attentionMask.count { it == 1L }
        require(count > 0)
        val mean = FloatArray(768)
        for (token in hidden.indices) {
            if (attentionMask[token] == 1L) {
                for (index in mean.indices) mean[index] += hidden[token][index]
            }
        }
        for (index in mean.indices) mean[index] /= count
        return mean
    }

    fun normalize(vector: FloatArray): FloatArray {
        require(vector.isNotEmpty() && vector.all(Float::isFinite))
        val norm = sqrt(vector.sumOf { it.toDouble() * it })
        require(norm.isFinite() && norm > 0)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
}
