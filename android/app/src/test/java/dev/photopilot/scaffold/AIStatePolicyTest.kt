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
    @Test fun explicitUserPauseOverridesEveryAutomaticRecoveryState() {
        for (status in listOf("running", "paused", "complete", "idle", "model_missing")) {
            val json = state(true, status).json().put("userPaused", true)
            assertFalse("User pause must survive $status", AIConfiguration.parse(json).resumeAutomatically)
        }
    }
    @Test fun sharedRecoveryPolicyMatchesBothPlatforms() {
        val cases = Vectors.load("ai-task-recovery.json").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val restored = AIConfiguration.parse(state(item.getBoolean("automatic"), item.getString("androidStatus")).json().put("userPaused", item.getBoolean("userPaused")))
            assertEquals(item.getString("id"), item.getBoolean("expectedResume"), restored.resumeAutomatically)
        }
    }
    @Test fun protectionPauseReasonSurvivesLegacyCompatibleStateRoundTrip() {
        val original = state(false, "paused").json().put("pauseReason", "temperature")
        assertEquals("temperature", AIConfiguration.parse(original).json().optString("pauseReason"))
    }
    @Test fun inferenceFailurePreservesAnExistingAIPhoto() {
        assertFalse(AIMembershipPolicy.removeExisting(AIMatch.REJECT, inferenceFailed = true, eligible = true))
        assertTrue(AIMembershipPolicy.removeExisting(AIMatch.REJECT, inferenceFailed = false, eligible = true))
        assertTrue(AIMembershipPolicy.removeExisting(AIMatch.MATCH, inferenceFailed = true, eligible = false))
        assertFalse(AIMembershipPolicy.removeExisting(AIMatch.MATCH, inferenceFailed = false, eligible = true))
    }
}
