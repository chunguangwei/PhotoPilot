package dev.photopilot.scaffold.data.logic

data class TombstoneIdentity(
    val contentHash: String? = null,
    val sourceFingerprint: String? = null,
    val remoteId: String? = null,
    val byteSize: Long? = null,
    val head64kHash: String? = null,
)

enum class TombstoneMatchTier {
    CONTENT_HASH,
    SOURCE_FINGERPRINT_REMOTE_ID,
    SOURCE_FINGERPRINT_SIZE_HEAD64K,
    NONE,
}

object TombstoneMatcher {

    fun matchTier(tombstone: TombstoneIdentity, candidate: TombstoneIdentity): TombstoneMatchTier {
        if (tombstone.contentHash != null &&
            tombstone.contentHash == candidate.contentHash
        ) {
            return TombstoneMatchTier.CONTENT_HASH
        }
        if (tombstone.sourceFingerprint != null &&
            tombstone.remoteId != null &&
            tombstone.sourceFingerprint == candidate.sourceFingerprint &&
            tombstone.remoteId == candidate.remoteId
        ) {
            return TombstoneMatchTier.SOURCE_FINGERPRINT_REMOTE_ID
        }
        if (tombstone.sourceFingerprint != null &&
            tombstone.byteSize != null &&
            tombstone.head64kHash != null &&
            tombstone.sourceFingerprint == candidate.sourceFingerprint &&
            tombstone.byteSize == candidate.byteSize &&
            tombstone.head64kHash == candidate.head64kHash
        ) {
            return TombstoneMatchTier.SOURCE_FINGERPRINT_SIZE_HEAD64K
        }
        return TombstoneMatchTier.NONE
    }

    fun bestMatch(
        candidate: TombstoneIdentity,
        tombstones: Collection<TombstoneIdentity>,
    ): Pair<TombstoneIdentity, TombstoneMatchTier>? {
        var best: Pair<TombstoneIdentity, TombstoneMatchTier>? = null
        for (tombstone in tombstones) {
            val tier = matchTier(tombstone, candidate)
            if (tier == TombstoneMatchTier.NONE) continue
            val current = best?.second ?: TombstoneMatchTier.NONE
            if (tier < current || best == null) {
                best = tombstone to tier
            }
        }
        return best
    }
}
