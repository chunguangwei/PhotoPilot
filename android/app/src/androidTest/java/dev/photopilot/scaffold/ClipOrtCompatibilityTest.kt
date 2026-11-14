package dev.photopilot.scaffold

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.junit.Assert.*
import org.junit.Test

class ClipOrtCompatibilityTest {
    @Test fun pinnedTextInt8AndVisionQ4RunOnAndroidOrt1232() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "ai-validation/multilingual-clip")
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(File(folder, "text_model_int8.onnx").absolutePath, options).use { text ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(101, 169, 38171, 10108, 169, 41163, 102)), longArrayOf(1, 7)).use { input ->
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(7) { 1 }), longArrayOf(1, 7)).use { mask ->
                        text.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val hidden = result.get("last_hidden_state").get().value as Array<Array<FloatArray>>
                            assertEquals(7, hidden[0].size)
                            assertEquals(768, hidden[0][0].size)
                            assertTrue(hidden[0].all { vector -> vector.all { it.isFinite() } })
                        }
                    }
                }
            }
            environment.createSession(File(folder, "vision_model_q4.onnx").absolutePath, options).use { vision ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(3 * 224 * 224)), longArrayOf(1, 3, 224, 224)).use { image ->
                    vision.run(mapOf("pixel_values" to image)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val vector = (result.get("image_embeds").get().value as Array<FloatArray>)[0]
                        assertEquals(512, vector.size)
                        assertTrue(vector.all { it.isFinite() })
                        assertTrue(vector.any { it != 0f })
                    }
                }
            }
        }
    }
}
