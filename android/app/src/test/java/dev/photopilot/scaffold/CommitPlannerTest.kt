package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.CommitPlan
import dev.photopilot.scaffold.data.imports.CommitPlanner
import dev.photopilot.scaffold.data.logic.TombstoneMatchTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPlannerTest {

    private var idSeq = 0
    private val ids: () -> String = { "asset-${++idSeq}" }

    @Test
    fun epochStaleWinsOverEverything() {
        val plan = CommitPlanner.plan(
            epochStale = true,
            tombstoneTier = TombstoneMatchTier.CONTENT_HASH,
            existingAssetId = "asset-x",
            newAssetId = ids,
        )
        assertEquals(CommitPlan.CancelByEpoch, plan)
    }

    @Test
    fun tombstoneBeatsExistingAsset() {
        val plan = CommitPlanner.plan(
            epochStale = false,
            tombstoneTier = TombstoneMatchTier.SOURCE_FINGERPRINT_REMOTE_ID,
            existingAssetId = "asset-x",
            newAssetId = ids,
        )
        assertEquals(CommitPlan.SkipByTombstone, plan)
    }

    @Test
    fun existingAssetMergesWithoutNewId() {
        val plan = CommitPlanner.plan(
            epochStale = false,
            tombstoneTier = TombstoneMatchTier.NONE,
            existingAssetId = "asset-x",
            newAssetId = ids,
        )
        assertEquals(CommitPlan.MergeExisting("asset-x"), plan)
    }

    @Test
    fun noHitCreatesNewAssetWithGeneratedId() {
        val plan = CommitPlanner.plan(
            epochStale = false,
            tombstoneTier = TombstoneMatchTier.NONE,
            existingAssetId = null,
            newAssetId = ids,
        )
        assertTrue(plan is CommitPlan.CreateNew)
        assertEquals("asset-1", (plan as CommitPlan.CreateNew).assetId)
    }

    @Test
    fun priorityOrderIsEpochThenTombstoneThenMerge() {
        assertEquals(
            CommitPlan.SkipByTombstone,
            CommitPlanner.plan(
                epochStale = false,
                tombstoneTier = TombstoneMatchTier.SOURCE_FINGERPRINT_SIZE_HEAD64K,
                existingAssetId = "asset-x",
                newAssetId = ids,
            ),
        )
    }
}
