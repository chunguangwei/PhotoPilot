package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.*
import org.junit.Assert.*
import org.junit.Test

class AIFrameRulesTest {
    @Test fun semanticRulesRequireEveryIncludeAndRejectExcludes() {
        val rule = AIRule("海边 beach", listOf("日落 sunset"), listOf("人 people"), true, .25f)
        assertEquals(AIMatch.MATCH, rule.evaluate(.8f, listOf(.7f), listOf(.1f)))
        assertEquals(AIMatch.REVIEW, rule.evaluate(.8f, listOf(.23f), listOf(.1f)))
        assertEquals(AIMatch.REJECT, rule.evaluate(.8f, listOf(.7f), listOf(.8f)))
    }
    @Test fun invalidVectorsNeverProduceMatches() {
        assertThrows(IllegalArgumentException::class.java) { AIEmbedding.cosine(floatArrayOf(1f), floatArrayOf(1f,2f)) }
        assertThrows(IllegalArgumentException::class.java) { AIEmbedding.cosine(floatArrayOf(Float.NaN), floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { AIEmbedding.cosine(floatArrayOf(0f), floatArrayOf(1f)) }
    }
    @Test fun privacyReadinessAndManualExclusionsWin() {
        assertFalse(AIEligibility.allowed("ready", true, false, "image", true, false))
        assertFalse(AIEligibility.allowed("ready", false, false, "image", true, true))
        assertFalse(AIEligibility.allowed("ready", false, false, "video", true, false))
        assertFalse(AIEligibility.allowed("pending", false, false, "image", true, false))
        assertTrue(AIEligibility.allowed("ready", false, false, "image", true, false))
    }
    @Test fun interruptedOrOldJobsCannotCommit() {
        assertTrue(AIJobGuard.canCommit(2, 2, "model-a", "model-a", false))
        assertFalse(AIJobGuard.canCommit(1, 2, "model-a", "model-a", false))
        assertFalse(AIJobGuard.canCommit(2, 2, "model-a", "model-b", false))
        assertFalse(AIJobGuard.canCommit(2, 2, "model-a", "model-a", true))
    }
}
