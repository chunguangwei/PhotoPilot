package dev.photopilot.scaffold.data.logic

data class FrameSelectionValues(
    val frameId: String,
    val assetId: String?,
    val sourceAssetId: String?,
    val mode: String,
    val manualOrder: Long?,
    val addedAt: Long,
    val refKey: String,
)

object FrameSelectionLogic {

    fun requireExactlyOneRef(assetId: String?, sourceAssetId: String?): String {
        if (assetId == null && sourceAssetId == null) {
            throw IllegalArgumentException("frame_selection requires exactly one non-null ref")
        }
        if (assetId != null && sourceAssetId != null) {
            throw IllegalArgumentException("frame_selection requires exactly one non-null ref")
        }
        return assetId ?: sourceAssetId as String
    }

    fun refKey(assetId: String?, sourceAssetId: String?): String =
        requireExactlyOneRef(assetId, sourceAssetId)

    fun newSelection(
        frameId: String,
        assetId: String?,
        sourceAssetId: String?,
        mode: String,
        manualOrder: Long?,
        addedAt: Long = 0L,
    ): FrameSelectionValues {
        val key = requireExactlyOneRef(assetId, sourceAssetId)
        return FrameSelectionValues(
            frameId = frameId,
            assetId = assetId,
            sourceAssetId = sourceAssetId,
            mode = mode,
            manualOrder = manualOrder,
            addedAt = addedAt,
            refKey = key,
        )
    }

    fun repointToAsset(
        selection: FrameSelectionValues,
        sourceAssetId: String,
        assetId: String,
    ): FrameSelectionValues {
        if (selection.sourceAssetId != sourceAssetId) return selection
        return selection.copy(
            assetId = assetId,
            sourceAssetId = null,
            refKey = assetId,
        )
    }
}
