package dev.photopilot.scaffold.update

import dev.photopilot.scaffold.data.update.ResumeLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeLogicTest {

    @Test
    fun partialResponseAppendsToExistingPart() {
        assertEquals(ResumeLogic.Decision.APPEND, ResumeLogic.decide(500L, 1_000L, ResumeLogic.HTTP_PARTIAL))
    }

    @Test
    fun partialResponseWithoutPartRestarts() {
        assertEquals(ResumeLogic.Decision.RESTART, ResumeLogic.decide(0L, 1_000L, ResumeLogic.HTTP_PARTIAL))
    }

    @Test
    fun partialResponseWithOversizePartRestarts() {
        assertEquals(ResumeLogic.Decision.RESTART, ResumeLogic.decide(1_000L, 1_000L, ResumeLogic.HTTP_PARTIAL))
        assertEquals(ResumeLogic.Decision.RESTART, ResumeLogic.decide(1_500L, 1_000L, ResumeLogic.HTTP_PARTIAL))
    }

    @Test
    fun okResponseRestartsFromScratch() {
        assertEquals(ResumeLogic.Decision.RESTART, ResumeLogic.decide(500L, 1_000L, ResumeLogic.HTTP_OK))
        assertEquals(ResumeLogic.Decision.RESTART, ResumeLogic.decide(0L, 1_000L, ResumeLogic.HTTP_OK))
    }

    @Test
    fun rangeNotSatisfiableRestartsFromScratch() {
        assertEquals(
            ResumeLogic.Decision.RESTART,
            ResumeLogic.decide(500L, 1_000L, ResumeLogic.HTTP_RANGE_NOT_SATISFIABLE),
        )
        assertEquals(
            ResumeLogic.Decision.RESTART,
            ResumeLogic.decide(0L, 1_000L, ResumeLogic.HTTP_RANGE_NOT_SATISFIABLE),
        )
    }

    @Test
    fun unexpectedStatusMeansRetry() {
        assertEquals(ResumeLogic.Decision.RETRY, ResumeLogic.decide(500L, 1_000L, 404))
        assertEquals(ResumeLogic.Decision.RETRY, ResumeLogic.decide(500L, 1_000L, 500))
    }

    @Test
    fun completionRequiresExactLength() {
        assertTrue(ResumeLogic.isComplete(1_000L, 1_000L))
        assertFalse(ResumeLogic.isComplete(999L, 1_000L))
        assertFalse(ResumeLogic.isComplete(1_001L, 1_000L))
        assertFalse(ResumeLogic.isComplete(1_000L, 0L))
        assertFalse(ResumeLogic.isComplete(0L, 0L))
    }
}
