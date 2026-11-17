package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.AITaskRecord
import org.junit.Assert.*
import org.junit.Test

class AITaskPolicyTest {
    @Test fun interruptedTaskKeepsItsProtectionReason() {
        val value = AITaskRecord("task", "preview", "model", "revision").json().put("reason", "low_battery")
        assertEquals("low_battery", AITaskRecord.parse(value).json().optString("reason"))
    }
    @Test fun persistedUserPauseAndSystemStopHaveDistinctRecoveryPolicies() {
        val work = AITaskRecord("task", "download", "model", "revision")
        assertTrue(work.copy(status = "running").mayRecover)
        assertFalse(work.copy(status = "running", userPaused = true).mayRecover)
        assertFalse(work.copy(status = "deleted").mayRecover)
        assertFalse(work.copy(status = "complete").mayRecover)
        val restored = AITaskRecord.parse(work.copy(userPaused = true, source = "domestic", allowMetered = true).json())
        assertTrue(restored.userPaused)
        assertTrue(restored.allowMetered)
        assertEquals("domestic", restored.source)
    }
}
