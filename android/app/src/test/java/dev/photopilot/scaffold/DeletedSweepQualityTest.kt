package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.DeletedSweep
import dev.photopilot.scaffold.data.logic.QualityScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedSweepTest {

    @Test
    fun expiredOnlyBeyondTwentyFourHours() {
        val base = 1_700_000_000_000L
        assertFalse(DeletedSweep.isExpired(base, base + DeletedSweep.RETENTION_MS - 1))
        assertFalse(DeletedSweep.isExpired(base, base + DeletedSweep.RETENTION_MS))
        assertTrue(DeletedSweep.isExpired(base, base + DeletedSweep.RETENTION_MS + 1))
        assertFalse(DeletedSweep.isExpired(base, base - 1))
    }
}

class QualityScaleTest {

    @Test
    fun factorsMatchFrozenSemantics() {
        assertEquals(1.0, QualityScale.factor("auto"), 1e-9)
        assertEquals(0.75, QualityScale.factor("balanced"), 1e-9)
        assertEquals(0.5, QualityScale.factor("low"), 1e-9)
        assertEquals(1.0, QualityScale.factor(null), 1e-9)
        assertEquals(1.0, QualityScale.factor("unknown-future"), 1e-9)
    }

    @Test
    fun scaleAppliesFactorWithFloorOne() {
        assertEquals(750, QualityScale.scale(1000, "balanced"))
        assertEquals(500, QualityScale.scale(1000, "low"))
        assertEquals(1000, QualityScale.scale(1000, "auto"))
        assertEquals(1, QualityScale.scale(1, "low"))
    }
}
