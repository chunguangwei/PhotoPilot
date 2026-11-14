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

class MultilingualClipEmbedder(
    textModel: File,
    visionModel: File,
    projectionFile: File,
    vocabularyFile: File,
) : AIEmbedder {
    private val tokenizer = WordPieceTokenizer(vocabularyFile.readLines())
    private val projection = ClipProjection(projectionFile.readBytes())
    private val environment = OrtEnvironment.getEnvironment()
    private val textSession = openSession(textModel)
    private val visionSession = try {
        openSession(visionModel)
    } catch (failure: Throwable) {
        textSession.close()
        throw failure
    }

    private fun openSession(file: File): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        environment.createSession(file.absolutePath, options)
    }

    override fun text(text: String): FloatArray {
        val tokens = tokenizer.encode(text)
        val attentionMask = LongArray(tokens.size) { 1 }
        val shape = longArrayOf(1, tokens.size.toLong())
        OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), shape).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(attentionMask), shape).use { mask ->
                textSession.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val hidden = result.get("last_hidden_state").get().value as Array<Array<FloatArray>>
                    require(hidden.size == 1)
                    val pooled = ClipEmbeddingMath.meanPool(hidden[0], attentionMask)
                    return ClipEmbeddingMath.normalize(projection.apply(pooled))
                }
            }
        }
    }

    override fun image(bitmap: Bitmap): FloatArray {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val pixels = ClipImagePreprocessor.pixelValues(bitmap.width, bitmap.height, argb)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 224, 224)).use { input ->
            visionSession.run(mapOf("pixel_values" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val values = result.get("image_embeds").get().value as Array<FloatArray>
                require(values.size == 1 && values[0].size == 512)
                return ClipEmbeddingMath.normalize(values[0])
            }
        }
    }

    override fun close() {
        try { textSession.close() } finally { visionSession.close() }
    }
}

internal fun createAIEmbedder(context: Context, model: AIModel, directory: File): AIEmbedder = when (model.runtime) {
    "litertlm" -> {
        require(model.embeddingDimension == 768)
        RemoteGemmaEmbedder(context, File(directory, model.files.single { it.role == "model" }.name))
    }
    "onnx" -> {
        require(model.embeddingDimension == 512)
        MultilingualClipEmbedder(
            textModel = File(directory, model.files.single { it.role == "text" }.name),
            visionModel = File(directory, model.files.single { it.role == "model" }.name),
            projectionFile = File(directory, model.files.single { it.role == "projection" }.name),
            vocabularyFile = File(directory, model.files.single { it.role == "tokenizer" }.name),
        )
    }
    else -> throw java.io.IOException("model_missing")
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
