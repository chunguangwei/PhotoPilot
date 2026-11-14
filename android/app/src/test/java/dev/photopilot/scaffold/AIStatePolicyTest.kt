package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.*
import org.junit.Assert.*
import org.junit.Test

class AIStatePolicyTest {
    private fun state(automatic: Boolean, status: String) = AIConfiguration("frame", 1, "model", "rev", listOf("folder"), false, automatic, AIRule("cats"), status = status)
    @Test fun manualCancellationRequiresExplicitResume() {
        assertFalse(state(false, "cancelled").resumeAutomatically)
        assertFalse(state(true, "cancelled").resumeAutomatically)
        assertTrue(state(false, "paused").resumeAutomatically)
        assertTrue(state(false, "running").resumeAutomatically)
        assertFalse(state(false, "complete").resumeAutomatically)
        assertTrue(state(true, "complete").resumeAutomatically)
    }
    @Test fun inferenceFailurePreservesAnExistingAIPhoto() {
        assertFalse(AIMembershipPolicy.removeExisting(AIMatch.REJECT, inferenceFailed = true, eligible = true))
        assertTrue(AIMembershipPolicy.removeExisting(AIMatch.REJECT, inferenceFailed = false, eligible = true))
        assertTrue(AIMembershipPolicy.removeExisting(AIMatch.MATCH, inferenceFailed = true, eligible = false))
        assertFalse(AIMembershipPolicy.removeExisting(AIMatch.MATCH, inferenceFailed = false, eligible = true))
    }
}
