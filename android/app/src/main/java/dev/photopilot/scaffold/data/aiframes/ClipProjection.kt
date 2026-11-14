package dev.photopilot.scaffold.data.aiframes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

class ClipProjection(bytes: ByteArray) {
    private val weights: FloatArray

    init {
        require(bytes.size >= 8) { "projection_format" }
        val headerLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
        require(headerLength in 1..1_048_576 && headerLength <= bytes.size - 8L) { "projection_format" }
        val header = JSONObject(String(bytes, 8, headerLength.toInt(), Charsets.UTF_8))
        val tensor = header.getJSONObject("linear.weight")
        require(tensor.getString("dtype") == "F32") { "projection_dtype" }
        val shape = tensor.getJSONArray("shape")
        require(shape.length() == 2 && shape.getInt(0) == 512 && shape.getInt(1) == 768) { "projection_shape" }
        val offsets = tensor.getJSONArray("data_offsets")
        require(offsets.length() == 2) { "projection_offsets" }
        val start = offsets.getLong(0)
        val end = offsets.getLong(1)
        val dataStart = 8 + headerLength.toInt()
        require(start >= 0 && end - start == 512L * 768 * 4 && end <= bytes.size - dataStart) { "projection_offsets" }
        val data = ByteBuffer.wrap(bytes, dataStart + start.toInt(), (end - start).toInt()).slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        weights = FloatArray(512 * 768)
        data.get(weights)
        require(weights.all { it.isFinite() }) { "projection_weights" }
    }

    fun apply(vector: FloatArray): FloatArray {
        require(vector.size == 768 && vector.all { it.isFinite() })
        return FloatArray(512) { row ->
            var sum = 0f
            val offset = row * 768
            for (column in vector.indices) sum += weights[offset + column] * vector[column]
            sum
        }
    }
}
