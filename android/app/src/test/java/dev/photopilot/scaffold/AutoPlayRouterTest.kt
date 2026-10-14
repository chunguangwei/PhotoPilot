package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.AutoPlayRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPlayRouterTest {

    @Test
    fun playsOnlyWhenAllConditionsMet() {
        assertEquals(
            "frame-1",
            AutoPlayRouter.decideFrame(
                coldStart = true,
                onboardingCompleted = true,
                hasPlayedOnce = true,
                autoPlayEnabled = true,
                lastFrameId = "frame-1",
            ),
        )
    }

    @Test
    fun blockedByEachGate() {
        assertNull(AutoPlayRouter.decideFrame(false, true, true, true, "f"))
        assertNull(AutoPlayRouter.decideFrame(true, false, true, true, "f"))
        assertNull(AutoPlayRouter.decideFrame(true, true, false, true, "f"))
        assertNull(AutoPlayRouter.decideFrame(true, true, true, false, "f"))
        assertNull(AutoPlayRouter.decideFrame(true, true, true, true, null))
    }

    @Test
    fun configurationChangeRecreateNeverAutoPlays() {
        assertNull(
            AutoPlayRouter.decideFrame(
                coldStart = false,
                onboardingCompleted = true,
                hasPlayedOnce = true,
                autoPlayEnabled = true,
                lastFrameId = "frame-1",
            ),
        )
    }
}
