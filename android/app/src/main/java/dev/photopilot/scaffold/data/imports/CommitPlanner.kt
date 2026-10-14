package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.logic.TombstoneMatchTier

sealed interface CommitPlan {
    data class MergeExisting(val assetId: String) : CommitPlan
    data class CreateNew(val assetId: String) : CommitPlan
    data object SkipByTombstone : CommitPlan
    data object CancelByEpoch : CommitPlan
}

object CommitPlanner {

    fun plan(
        epochStale: Boolean,
        tombstoneTier: TombstoneMatchTier,
        existingAssetId: String?,
        newAssetId: () -> String,
    ): CommitPlan = when {
        epochStale -> CommitPlan.CancelByEpoch
        tombstoneTier != TombstoneMatchTier.NONE -> CommitPlan.SkipByTombstone
        existingAssetId != null -> CommitPlan.MergeExisting(existingAssetId)
        else -> CommitPlan.CreateNew(newAssetId())
    }
}
