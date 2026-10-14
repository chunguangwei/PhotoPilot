package dev.photopilot.scaffold.data.logic

object BatchDeleteScope {

    fun deletable(
        memberAssetIds: List<String>,
        originCountByAsset: Map<String, Int>,
        alreadyDeleted: Set<String>,
    ): List<String> =
        memberAssetIds.filter {
            (originCountByAsset[it] ?: 0) <= 1 && it !in alreadyDeleted
        }
}
