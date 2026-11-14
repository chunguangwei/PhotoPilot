package dev.photopilot.scaffold

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

class AIRealInferenceTest {
    @Test fun bothPinnedModelsEmbedRealImageAndBilingualPrompts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "ai-validation")
        val bitmap = requireNotNull(BitmapFactory.decodeFile(File(folder, "validation-cats.png").absolutePath))
        val prompts = listOf("a photo of a cat", "a photo of a beach", "a photo of a car", "猫的照片", "海滩的照片", "汽车的照片")
        val output = JSONObject()
        val adapters = listOf<Pair<String, () -> AIEmbedder>>(
            "multilingual-clip-vitb32-lite" to { MultilingualClipEmbedder(File(folder, "multilingual-clip/text_model_int8.onnx"), File(folder, "multilingual-clip/vision_model_q4.onnx"), File(folder, "multilingual-clip/projection.safetensors"), File(folder, "multilingual-clip/vocab.txt")) },
            "embeddinggemma2-vision" to { RemoteGemmaEmbedder(context, File(folder, "embeddinggemma-2-text-vision-440m.litertlm"), useGpu = InstrumentationRegistry.getArguments().getString("gemmaGpu") == "true") },
        )
        try {
            val requested = InstrumentationRegistry.getArguments().getString("model")
            for ((name, adapter) in adapters.filter { requested == null || it.first == requested }) {
                val start = System.currentTimeMillis()
                adapter().use { model ->
                    val image = model.image(bitmap)
                    assertEquals(if (name == "embeddinggemma2-vision") 768 else 512, image.size)
                    val scores = prompts.map { AIEmbedding.cosine(image, model.text(it)) }
                    assertTrue(scores.all { it.isFinite() })
                    assertEquals(0, scores.take(3).indices.maxBy { scores[it] })
                    assertEquals(3, (3..5).maxBy { scores[it] })
                    output.put(name, JSONObject().put("cosine", JSONArray(scores)).put("elapsedMs", System.currentTimeMillis() - start))
                    File(context.filesDir, "ai-real-inference.json").writeText(output.toString(2))
                    android.util.Log.i("PhotoPilotAIValidation", output.toString())
                }
            }
        } finally { bitmap.recycle() }
    }
}
