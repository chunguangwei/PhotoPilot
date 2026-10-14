package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.TombstoneIdentity
import dev.photopilot.scaffold.data.logic.TombstoneMatcher
import dev.photopilot.scaffold.data.logic.TombstoneMatchTier
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneMatcherTest {
    private fun str(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotEmpty() }

    private fun identity(json: JSONObject): TombstoneIdentity =
        TombstoneIdentity(
            contentHash = str(json, "content_hash"),
            sourceFingerprint = str(json, "source_fingerprint"),
            remoteId = str(json, "remote_id"),
            byteSize = if (json.isNull("byte_size")) null else json.getLong("byte_size"),
            head64kHash = str(json, "head64k_hash"),
        )

    private fun tierFromNumber(tier: Int): TombstoneMatchTier = when (tier) {
        1 -> TombstoneMatchTier.CONTENT_HASH
        2 -> TombstoneMatchTier.SOURCE_FINGERPRINT_REMOTE_ID
        3 -> TombstoneMatchTier.SOURCE_FINGERPRINT_SIZE_HEAD64K
        0 -> TombstoneMatchTier.NONE
        else -> error("unknown tier: $tier")
    }

    @Test
    fun tombstoneVectors() {
        val root = Vectors.load("tombstone-match.json")
        val tombstones = root.getJSONArray("tombstones")
        val byId = (0 until tombstones.length()).associate { idx ->
            val t = tombstones.getJSONObject(idx)
            t.getString("id") to identity(t)
        }
        val cases = root.getJSONArray("cases")
        assertTrue("expected at least 9 tombstone vectors", cases.length() >= 9)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val probe = identity(case.getJSONObject("probe"))
            val expectedTier = tierFromNumber(case.getInt("expected_tier"))
            val expectedId = if (case.isNull("expected_tombstone")) null else case.getString("expected_tombstone")
            val pool = byId.values.toList()

            val best = TombstoneMatcher.bestMatch(probe, pool)
            val actualTier = best?.second ?: TombstoneMatchTier.NONE
            assertEquals("$id tier", expectedTier, actualTier)

            val actualId = best?.let { (identityMatched, _) ->
                byId.entries.firstOrNull { it.value == identityMatched }?.key
            }
            assertEquals("$id tombstone", expectedId, actualId)
        }
    }

    @Test
    fun bestMatchReturnsNullWhenNothingMatches() {
        val probe = TombstoneIdentity(contentHash = "sha256:none")
        val other = TombstoneIdentity(contentHash = "sha256:other")
        assertNull(TombstoneMatcher.bestMatch(probe, listOf(other)))
    }

    @Test
    fun matchTierNoneForDisjointIdentities() {
        val tombstone = TombstoneIdentity(contentHash = "sha256:a")
        val probe = TombstoneIdentity(contentHash = "sha256:b")
        assertEquals(TombstoneMatchTier.NONE, TombstoneMatcher.matchTier(tombstone, probe))
    }
}
