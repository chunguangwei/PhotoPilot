package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.RenderGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGeometryTest {

    private fun longPair(case: org.json.JSONObject, key: String): Pair<Long, Long> {
        val array = case.getJSONArray(key)
        return array.getLong(0) to array.getLong(1)
    }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("render-geometry.json").getJSONArray("cases")
        assertTrue("expected at least 15 vectors", cases.length() >= 15)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            when (case.getString("scenario")) {
                "aspect_fit", "aspect_fill" -> {
                    val (srcW, srcH) = longPair(case, "src")
                    val (dstW, dstH) = longPair(case, "dst")
                    val rect = if (case.getString("scenario") == "aspect_fit") {
                        RenderGeometry.aspectFit(srcW, srcH, dstW, dstH)
                    } else {
                        RenderGeometry.aspectFill(srcW, srcH, dstW, dstH)
                    }
                    val expected = case.getJSONArray("expected")
                    assertEquals("$id x", expected.getLong(0), rect.x)
                    assertEquals("$id y", expected.getLong(1), rect.y)
                    assertEquals("$id w", expected.getLong(2), rect.width)
                    assertEquals("$id h", expected.getLong(3), rect.height)
                }
                "decode_target" -> {
                    val (viewW, viewH) = longPair(case, "view")
                    val actual = RenderGeometry.decodeTarget(
                        viewW, viewH, case.getLong("scale"), case.getLong("cap"),
                    )
                    assertEquals(id, longPair(case, "expected"), actual)
                }
                "blur_background" -> {
                    val (srcW, srcH) = longPair(case, "src")
                    assertEquals(
                        id,
                        longPair(case, "expected"),
                        RenderGeometry.blurBackgroundSize(srcW, srcH),
                    )
                }
                "cache_key" -> {
                    val (viewportW, viewportH) = longPair(case, "viewport")
                    assertEquals(
                        id,
                        case.getString("expected"),
                        RenderGeometry.cacheKey(
                            assetId = case.getString("asset_id"),
                            assetVersion = case.getLong("asset_version"),
                            viewportW = viewportW,
                            viewportH = viewportH,
                            orientation = case.getString("orientation"),
                            fitMode = case.getString("fit_mode"),
                            qualityLevel = case.getString("quality_level"),
                        ),
                    )
                }
                "edge_color" -> {
                    val pixels = case.getJSONArray("pixels")
                    val input = LongArray(pixels.length()) { pixels.getLong(it) }
                    assertEquals(
                        id,
                        case.getString("expected"),
                        RenderGeometry.edgeColor(input, case.getInt("width"), case.getInt("height")),
                    )
                }
                else -> throw AssertionError("$id: unknown scenario")
            }
        }
    }

    @Test
    fun narrowBarCropThreshold() {
        assertTrue(RenderGeometry.narrowBarCrop(1170, 2532, 1170, 2532))
        assertTrue(RenderGeometry.narrowBarCrop(4000, 2660, 2388, 1668))
        assertTrue(RenderGeometry.narrowBarCrop(3024, 4032, 1170, 2532) == false)
        assertTrue(RenderGeometry.narrowBarCrop(4000, 2660, 1170, 2532) == false)
        assertTrue(RenderGeometry.narrowBarCrop(3024, 3024, 1170, 2532) == false)
        assertTrue(RenderGeometry.narrowBarCrop(1170, 2532, 2388, 1668) == false)
        assertTrue(RenderGeometry.narrowBarCrop(0, 100, 1170, 2532) == false)
        assertTrue(RenderGeometry.narrowBarCrop(100, 100, 0, 0) == false)
    }

    @Test
    fun fillCropKeepsEnoughThreshold() {
        assertTrue(RenderGeometry.fillCropKeepsEnough(4032, 3024, 2388, 1668))
        assertTrue(RenderGeometry.fillCropKeepsEnough(1170, 2532, 1170, 2532))
        assertTrue(RenderGeometry.fillCropKeepsEnough(3024, 4032, 2388, 1668) == false)
        assertTrue(RenderGeometry.fillCropKeepsEnough(1920, 1080, 1170, 2532) == false)
        assertTrue(RenderGeometry.fillCropKeepsEnough(0, 100, 1170, 2532))
    }
}
