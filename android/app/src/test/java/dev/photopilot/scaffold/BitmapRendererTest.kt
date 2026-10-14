package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.render.BitmapRenderer
import dev.photopilot.scaffold.data.render.BoxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapRendererTest {

    @Test
    fun uniformBitmapIsUnchangedByBlur() {
        val width = 8
        val height = 6
        val pixels = IntArray(width * height) { 0xFF336699.toInt() }
        val blurred = BoxBlur.blur(pixels, width, height, radius = 3, passes = 2)
        assertTrue(blurred.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun impulseSpreadsToNeighbors() {
        val width = 9
        val height = 9
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[4 * width + 4] = 0xFFFFFFFF.toInt()
        val blurred = BoxBlur.blur(pixels, width, height, radius = 2, passes = 1)
        assertTrue(blurred[4 * width + 4] != 0xFFFFFFFF.toInt())
        assertTrue(blurred[4 * width + 5] != 0xFF000000.toInt())
        assertTrue(blurred[5 * width + 4] != 0xFF000000.toInt())
        assertEquals(0xFF000000.toInt(), blurred[0])
    }

    @Test
    fun sampleSizeHalvesUntilTarget() {
        assertEquals(1, BitmapRenderer.sampleSize(1000, 800, 1024, 768))
        assertEquals(2, BitmapRenderer.sampleSize(4000, 3000, 1024, 768))
        assertEquals(4, BitmapRenderer.sampleSize(8000, 6000, 1024, 768))
        assertEquals(2, BitmapRenderer.sampleSize(2100, 2000, 1024, 768))
    }
}
