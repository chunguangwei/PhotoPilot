package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.ClipProjection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClipProjectionTest {
    @Test fun readsSafetensorsHeaderAndAppliesRowMajorMatrix() {
        val file = File(System.getenv("AI_CLIP_TEST_DIR") ?: "/private/tmp/photopilot-ai-models/multilingual-clip", "projection.safetensors")
        org.junit.Assume.assumeTrue("Provide pinned CLIP projection", file.isFile)
        val bytes = file.readBytes()
        val projection = ClipProjection(bytes)
        val headerLength = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
        val data = ByteBuffer.wrap(bytes, 8 + headerLength, bytes.size - 8 - headerLength).slice().order(ByteOrder.LITTLE_ENDIAN)
        val expected = FloatArray(512) { row -> data.getFloat(row * 768 * 4) }
        val actual = projection.apply(FloatArray(768) { if (it == 0) 1f else 0f })
        assertArrayEquals(expected, actual, 0f)
        assertThrows(IllegalArgumentException::class.java) { projection.apply(FloatArray(512)) }
        assertThrows(IllegalArgumentException::class.java) { ClipProjection(bytes.copyOf(16)) }
        val malformed = bytes.copyOf()
        val header = JSONObject(String(bytes, 8, headerLength)).apply { getJSONObject("linear.weight").put("dtype", "I32") }.toString().padEnd(headerLength, ' ').toByteArray()
        header.copyInto(malformed, 8)
        assertThrows(IllegalArgumentException::class.java) { ClipProjection(malformed) }
    }
}
