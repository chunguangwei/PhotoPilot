package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.FrameEditDiff
import dev.photopilot.scaffold.data.logic.RuleUiState
import dev.photopilot.scaffold.data.logic.SaveRuleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEditDiffTest {

    private val now = 1_700_000_000_000L

    @Test
    fun selectAndDeselectProduceIncludeDeltas() {
        val result = FrameEditDiff.plan(
            includedAssetIds = setOf("a", "b"),
            dynamicRules = emptyMap(),
            selectedAssetIds = setOf("b", "c"),
            removedExcludes = emptySet(),
            ruleStates = emptyMap(),
            staticMembers = emptyMap(),
            now = now,
        )
        assertEquals(listOf("c"), result.addIncludes.map { it.ref })
        assertEquals(listOf("a"), result.removeRefs)
        assertTrue(result.ruleChanges.isEmpty())
        assertTrue(result.removeRules.isEmpty())
    }

    @Test
    fun removedExcludesBecomeRemoveRefs() {
        val result = FrameEditDiff.plan(
            includedAssetIds = setOf("a"),
            dynamicRules = emptyMap(),
            selectedAssetIds = setOf("a"),
            removedExcludes = setOf("x"),
            ruleStates = emptyMap(),
            staticMembers = emptyMap(),
            now = now,
        )
        assertEquals(listOf("x"), result.removeRefs)
    }

    @Test
    fun staticRuleExpandsMembersWithoutConfirmation() {
        val result = FrameEditDiff.plan(
            includedAssetIds = emptySet(),
            dynamicRules = emptyMap(),
            selectedAssetIds = emptySet(),
            removedExcludes = emptySet(),
            ruleStates = mapOf("col-1" to RuleUiState(checked = true, recursive = true, dynamic = false)),
            staticMembers = mapOf("col-1" to listOf("m1", "m2")),
            now = now,
        )
        val change = result.ruleChanges.single()
        assertEquals("col-1", change.collectionId)
        assertEquals(false, change.includeFuture)
        assertEquals(false, change.confirmed)
        assertEquals(listOf("m1", "m2"), change.members)
        assertTrue(result.removeRules.isEmpty())
    }

    @Test
    fun dynamicRuleCarriesConfirmationAndNoMembers() {
        val result = FrameEditDiff.plan(
            includedAssetIds = emptySet(),
            dynamicRules = emptyMap(),
            selectedAssetIds = emptySet(),
            removedExcludes = emptySet(),
            ruleStates = mapOf("col-1" to RuleUiState(checked = true, recursive = false, dynamic = true)),
            staticMembers = emptyMap(),
            now = now,
        )
        val change = result.ruleChanges.single()
        assertEquals(true, change.includeFuture)
        assertEquals(true, change.confirmed)
        assertTrue(change.members.isEmpty())
    }

    @Test
    fun unchangedDynamicRuleIsSilentAndUncheckingRemovesIt() {
        val rules = mapOf("col-1" to SaveRuleRow("col-1", recursive = true, includeFuture = true))
        val unchanged = FrameEditDiff.plan(
            includedAssetIds = emptySet(),
            dynamicRules = rules,
            selectedAssetIds = emptySet(),
            removedExcludes = emptySet(),
            ruleStates = mapOf("col-1" to RuleUiState(checked = true, recursive = true, dynamic = true)),
            staticMembers = emptyMap(),
            now = now,
        )
        assertTrue(unchanged.ruleChanges.isEmpty())
        assertTrue(unchanged.removeRules.isEmpty())

        val unchecked = FrameEditDiff.plan(
            includedAssetIds = emptySet(),
            dynamicRules = rules,
            selectedAssetIds = emptySet(),
            removedExcludes = emptySet(),
            ruleStates = mapOf("col-1" to RuleUiState(checked = false, recursive = true, dynamic = true)),
            staticMembers = emptyMap(),
            now = now,
        )
        assertEquals(listOf("col-1"), unchecked.removeRules)

        val switchedToStatic = FrameEditDiff.plan(
            includedAssetIds = emptySet(),
            dynamicRules = rules,
            selectedAssetIds = emptySet(),
            removedExcludes = emptySet(),
            ruleStates = mapOf("col-1" to RuleUiState(checked = true, recursive = true, dynamic = false)),
            staticMembers = mapOf("col-1" to listOf("m1")),
            now = now,
        )
        assertEquals(listOf("col-1"), switchedToStatic.removeRules)
        assertEquals(1, switchedToStatic.ruleChanges.size)
        assertEquals(false, switchedToStatic.ruleChanges.single().includeFuture)
    }
}
