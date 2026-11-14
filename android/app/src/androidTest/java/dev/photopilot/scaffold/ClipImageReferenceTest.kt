package dev.photopilot.scaffold

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.ClipImagePreprocessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ClipImageReferenceTest {
    @Test fun realImageBicubicMatchesPillowReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "ai-validation")
        val image = requireNotNull(BitmapFactory.decodeFile(File(folder, "validation-cats.png").absolutePath))
        try {
            val colors = IntArray(image.width * image.height)
            image.getPixels(colors, 0, image.width, 0, 0, image.width, image.height)
            val actual = ClipImagePreprocessor.pixelValues(image.width, image.height, colors)
            val expected = FloatArray(actual.size)
            ByteBuffer.wrap(File(folder, "multilingual-clip/clip-pixels-f32.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(expected)
            val maxError = actual.indices.maxOf { kotlin.math.abs(actual[it] - expected[it]) }
            val meanError = actual.indices.sumOf { kotlin.math.abs(actual[it] - expected[it]).toDouble() } / actual.size
            android.util.Log.i("PhotoPilotClipValidation", "Pillow bicubic maxError=$maxError meanAbsoluteError=$meanError")
            File(context.filesDir, "clip-preprocessing-validation.json").writeText("{\"maxError\":$maxError,\"meanAbsoluteError\":$meanError}")
            assertTrue("Pillow per-pixel rounding tolerance", maxError < .016f)
            assertTrue("Pillow mean error", meanError < .0003)
        } finally { image.recycle() }
    }
}
