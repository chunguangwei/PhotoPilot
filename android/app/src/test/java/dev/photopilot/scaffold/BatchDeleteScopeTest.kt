package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.BatchDeleteScope
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchDeleteScopeTest {

    @Test
    fun singleOriginMembersAreDeletable() {
        assertEquals(
            listOf("a", "b"),
            BatchDeleteScope.deletable(
                memberAssetIds = listOf("a", "b"),
                originCountByAsset = mapOf("a" to 1, "b" to 1),
                alreadyDeleted = emptySet(),
            ),
        )
    }

    @Test
    fun sharedOriginMembersAreSkipped() {
        assertEquals(
            listOf("b"),
            BatchDeleteScope.deletable(
                memberAssetIds = listOf("a", "b"),
                originCountByAsset = mapOf("a" to 2, "b" to 1),
                alreadyDeleted = emptySet(),
            ),
        )
    }

    @Test
    fun alreadyDeletedMembersAreSkipped() {
        assertEquals(
            emptyList<String>(),
            BatchDeleteScope.deletable(
                memberAssetIds = listOf("a"),
                originCountByAsset = mapOf("a" to 1),
                alreadyDeleted = setOf("a"),
            ),
        )
    }

    @Test
    fun missingOriginRowCountsAsSingle() {
        assertEquals(
            listOf("a"),
            BatchDeleteScope.deletable(
                memberAssetIds = listOf("a"),
                originCountByAsset = emptyMap(),
                alreadyDeleted = emptySet(),
            ),
        )
    }
}
