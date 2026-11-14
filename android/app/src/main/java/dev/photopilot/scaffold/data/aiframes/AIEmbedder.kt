package dev.photopilot.scaffold.data.aiframes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

interface AIEmbedder : AutoCloseable {
    fun text(text: String): FloatArray
    fun image(bitmap: Bitmap): FloatArray
}

class GemmaEmbedder(context: Context, model: File, useGpu: Boolean = false) : AIEmbedder {
    private val engine = EmbeddingEngine(EmbeddingEngineConfig(
        modelPath = model.absolutePath,
        backend = if (useGpu) Backend.GPU() else Backend.CPU(threadCount = 2),
        visionBackend = if (useGpu) Backend.GPU() else Backend.CPU(threadCount = 2),
        cacheDir = File(context.codeCacheDir, "ai-runtime/${model.parentFile?.name.orEmpty()}").apply { mkdirs() }.absolutePath,
    )).apply { initialize() }
    private val options = EmbeddingOptions(normalize = true)
    override fun text(text: String): FloatArray {
        require(text.codePointCount(0, text.length) <= 500) { "description_too_long" }
        return engine.computeEmbedding(listOf(InputData.Text(text)), options).embedding
    }
    override fun image(bitmap: Bitmap): FloatArray {
        val output = java.io.ByteArrayOutputStream()
        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        return engine.computeEmbedding(listOf(InputData.Image(output.toByteArray())), options).embedding
    }
    override fun close() = engine.close()
}

class SiglipEmbedder(model: File, tokenizerFile: File) : AIEmbedder {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        environment.createSession(model.absolutePath, options)
    }
    private val tokenizer = SentencePieceTokenizer(tokenizerFile.readBytes())
    override fun text(text: String): FloatArray = run(text, null).first
    override fun image(bitmap: Bitmap): FloatArray = run("", bitmap).second
    private fun run(text: String, bitmap: Bitmap?): Pair<FloatArray, FloatArray> {
        val tokens = tokenizer.encode(text, 64)
        val pixels = FloatArray(3 * 224 * 224)
        bitmap?.let {
            val resized = Bitmap.createScaledBitmap(it, 224, 224, true)
            val colors = IntArray(224 * 224)
            resized.getPixels(colors, 0, 224, 0, 0, 224, 224)
            for (index in colors.indices) {
                pixels[index] = ((colors[index] shr 16 and 255) / 255f - .5f) / .5f
                pixels[224 * 224 + index] = ((colors[index] shr 8 and 255) / 255f - .5f) / .5f
                pixels[2 * 224 * 224 + index] = ((colors[index] and 255) / 255f - .5f) / .5f
            }
            if (resized !== it) resized.recycle()
        }
        OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { input ->
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 224, 224)).use { image ->
                session.run(mapOf("input_ids" to input, "pixel_values" to image)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val textEmbedding = (result.get("text_embeds").get().value as Array<FloatArray>)[0].copyOf()
                    @Suppress("UNCHECKED_CAST")
                    val imageEmbedding = (result.get("image_embeds").get().value as Array<FloatArray>)[0].copyOf()
                    return textEmbedding to imageEmbedding
                }
            }
        }
    }
    override fun close() = session.close()
}

object AIImageDecoder {
    fun decode(file: File, orientation: Int, maximumDimension: Int = 1024): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maximumDimension) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })) { "decode" }
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }
}
