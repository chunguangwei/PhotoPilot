package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.FrameSelectionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameSelectionLogicTest {

    @Test
    fun refKeyPrefersAssetId() {
        assertEquals("asset-1", FrameSelectionLogic.refKey("asset-1", null))
    }

    @Test
    fun refKeyFallsBackToSourceAssetId() {
        assertEquals("sa-1", FrameSelectionLogic.refKey(null, "sa-1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bothRefsNullRejected() {
        FrameSelectionLogic.refKey(null, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bothRefsNonNullRejected() {
        FrameSelectionLogic.refKey("asset-1", "sa-1")
    }

    @Test
    fun newSelectionCarriesComputedRefKey() {
        val values = FrameSelectionLogic.newSelection(
            frameId = "frame-1",
            assetId = null,
            sourceAssetId = "sa-1",
            mode = "include",
            manualOrder = 3L,
        )
        assertEquals("frame-1", values.frameId)
        assertEquals("sa-1", values.refKey)
        assertEquals(3L, values.manualOrder)
    }

    @Test
    fun repointFlipsToAssetIdAndPreservesManualOrder() {
        val placeholder = FrameSelectionLogic.newSelection(
            frameId = "frame-1",
            assetId = null,
            sourceAssetId = "sa-1",
            mode = "include",
            manualOrder = 7L,
        )
        val repointed = FrameSelectionLogic.repointToAsset(placeholder, "sa-1", "asset-9")
        assertEquals("asset-9", repointed.assetId)
        assertNull(repointed.sourceAssetId)
        assertEquals("asset-9", repointed.refKey)
        assertEquals(7L, repointed.manualOrder)
    }

    @Test
    fun repointLeavesOtherSelectionsUntouched() {
        val other = FrameSelectionLogic.newSelection(
            frameId = "frame-1",
            assetId = "asset-2",
            sourceAssetId = null,
            mode = "exclude",
            manualOrder = 1L,
        )
        val result = FrameSelectionLogic.repointToAsset(other, "sa-1", "asset-9")
        assertEquals(other, result)
    }
}
