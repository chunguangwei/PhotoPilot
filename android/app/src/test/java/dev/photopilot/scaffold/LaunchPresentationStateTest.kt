package dev.photopilot.scaffold

import dev.photopilot.scaffold.ui.launch.LaunchMotionTimeline
import dev.photopilot.scaffold.ui.launch.LaunchPresentationState
import dev.photopilot.scaffold.ui.launch.LaunchPresentationState.Initialization
import dev.photopilot.scaffold.ui.launch.LaunchPresentationState.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPresentationStateTest {

    private var now = 10_000L
    private val state = LaunchPresentationState { now }

    @Test
    fun contractVectors() {
        val vector = Vectors.load("launch-presentation.json")
        assertEquals(6_000L, vector.getLong("duration_ms"))
        assertEquals(vector.getLong("duration_ms"), LaunchPresentationState.BRAND_DURATION_MILLIS)
        val cases = vector.getJSONArray("cases")
        assertEquals(10, cases.length())
        val ids = mutableSetOf<String>()
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val id = case.getString("id")
            assertTrue(id, ids.add(id))
            var time = 10_000L
            val presentation = LaunchPresentationState { time }
            presentation.startIfNeeded()
            time += case.getLong("elapsed_ms")
            if (case.getBoolean("skipped")) presentation.skip()
            val initialization = when {
                case.getBoolean("failed") -> Initialization.FAILED
                case.getBoolean("ready") -> Initialization.READY
                else -> Initialization.PENDING
            }
            val snapshot = presentation.snapshot(initialization, active = case.getBoolean("active"))
            val expected = case.getString("expected")
            val expectedPhase = when (expected) {
                "brand" -> Phase.BRAND
                "loading" -> Phase.WAITING
                "route", "defer_route" -> Phase.READY
                "failure" -> Phase.FAILED
                else -> error("unknown launch outcome in $id: $expected")
            }
            assertEquals(id, expectedPhase, snapshot.phase)
            assertEquals(id, expected == "route", snapshot.canRoute)
        }
        assertEquals((1..10).map { "LAUNCH-${it.toString().padStart(2, '0')}" }.toSet(), ids)
    }

    @Test
    fun readyRequiresExplicitActiveWindow() {
        state.startIfNeeded()
        now += LaunchPresentationState.BRAND_DURATION_MILLIS
        val background = state.snapshot(Initialization.READY, active = false)
        assertEquals(Phase.READY, background.phase)
        assertFalse(background.canRoute)
        assertFalse(state.snapshot(Initialization.READY).canRoute)
        assertTrue(state.snapshot(Initialization.READY, active = true).canRoute)
    }

    @Test
    fun eachActualViewActivityConditionGuardsRouting() {
        state.startIfNeeded()
        now += LaunchPresentationState.BRAND_DURATION_MILLIS
        for (mask in 0..15) {
            val active = LaunchPresentationState.isActive(
                attached = mask and 1 != 0,
                windowVisible = mask and 2 != 0,
                shown = mask and 4 != 0,
                windowFocused = mask and 8 != 0,
            )
            val snapshot = state.snapshot(Initialization.READY, active)
            assertEquals("mask=$mask", Phase.READY, snapshot.phase)
            assertEquals("mask=$mask", mask == 15, snapshot.canRoute)
        }
    }

    @Test
    fun queuedRouteMustRecheckActivityAndCanResumeWithoutNewDeadline() {
        state.startIfNeeded()
        now += LaunchPresentationState.BRAND_DURATION_MILLIS
        assertTrue(state.snapshot(Initialization.READY, active = true).canRoute)
        assertFalse(state.snapshot(Initialization.READY, active = false).canRoute)
        now += 60_000L
        state.startIfNeeded()
        val resumed = state.snapshot(Initialization.READY, active = true)
        assertEquals(0L, resumed.remainingMillis)
        assertTrue(resumed.canRoute)
    }

    @Test
    fun firstProcessPresentationStartsWithSixSeconds() {
        val snapshot = state.snapshot(Initialization.PENDING)
        assertEquals(Phase.BRAND, snapshot.phase)
        assertEquals(6_000L, snapshot.remainingMillis)
        assertEquals(0f, snapshot.progress, 0f)
    }

    @Test
    fun readyInitializationStillWaitsUntilExactDeadline() {
        state.startIfNeeded()
        now += 5_999L
        assertEquals(Phase.BRAND, state.snapshot(Initialization.READY).phase)
        now += 1L
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun skipIsAvailableAtFirstSnapshot() {
        state.startIfNeeded()
        state.skip()
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
        assertEquals(0L, state.snapshot(Initialization.READY).remainingMillis)
    }

    @Test
    fun skipLeavesInitializationPendingUntilItActuallyCompletes() {
        state.startIfNeeded()
        state.skip()
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
        now += 90_000L
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun timeoutShowsWaitingRatherThanPretendingInitializationCompleted() {
        state.startIfNeeded()
        now += 6_000L
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
        assertEquals(1f, state.snapshot(Initialization.PENDING).progress, 0f)
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun recreationKeepsOriginalDeadline() {
        state.startIfNeeded()
        now += 2_000L
        state.startIfNeeded()
        assertEquals(4_000L, state.snapshot(Initialization.PENDING).remainingMillis)
        now += 4_000L
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun recreationKeepsSkipAcrossInitializationRestart() {
        state.startIfNeeded()
        state.skip()
        state.startIfNeeded()
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun sameProcessActivityCreationNeverRestartsFinishedBranding() {
        state.startIfNeeded()
        now += 7_000L
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
        repeat(3) {
            state.startIfNeeded()
            assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
            assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
        }
    }

    @Test
    fun backgroundTimeCountsTowardDeadlineWithoutTimerTicks() {
        state.startIfNeeded()
        now += 60_000L
        state.startIfNeeded()
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun failureImmediatelyOverridesBrandWait() {
        state.startIfNeeded()
        now += 10L
        assertEquals(Phase.FAILED, state.snapshot(Initialization.FAILED).phase)
        assertEquals(0L, state.snapshot(Initialization.FAILED).remainingMillis)
    }

    @Test
    fun failedAttemptRetryHasNoMinimumDelay() {
        state.startIfNeeded()
        state.initializationFailed()
        state.startIfNeeded()
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING).phase)
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun failureAfterSkipStillWinsAndRetryDoesNotReplay() {
        state.skip()
        assertEquals(Phase.FAILED, state.snapshot(Initialization.FAILED).phase)
        state.startIfNeeded()
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
    }

    @Test
    fun brandProgressDependsOnlyOnMonotonicTimeNotInitialization() {
        state.startIfNeeded()
        now += 3_000L
        assertEquals(0.5f, state.snapshot(Initialization.PENDING).progress, 0f)
        assertEquals(0.5f, state.snapshot(Initialization.READY).progress, 0f)
        now += 10_000L
        assertEquals(1f, state.snapshot(Initialization.PENDING).progress, 0f)
    }

    @Test
    fun revealCanOnlyBeClaimedOnceAcrossRecreations() {
        state.startIfNeeded()
        assertTrue(state.claimReveal())
        now += 200L
        state.startIfNeeded()
        assertFalse(state.claimReveal())
    }

    @Test
    fun skippedExpiredAndFailedPresentationsNeverReveal() {
        state.skip()
        assertFalse(state.claimReveal())
        val expired = LaunchPresentationState { now }
        expired.startIfNeeded()
        now += 6_000L
        assertFalse(expired.claimReveal())
        val failed = LaunchPresentationState { now }
        failed.initializationFailed()
        assertFalse(failed.claimReveal())
    }

    @Test
    fun motionRevealUsesStaggeredEaseInOutWindows() {
        val initial = LaunchMotionTimeline.sample(0L)
        assertEquals(0f, initial.frameFraction, 0f)
        assertEquals(0f, initial.dotAlpha, 0f)
        assertEquals(0.7f, initial.dotScale, 0f)
        assertEquals(8f, initial.wordmarkOffsetDp, 0f)
        assertEquals(6f, initial.taglineOffsetDp, 0f)
        assertEquals(0f, LaunchMotionTimeline.sample(350L).dotAlpha, 0f)
        val middle = LaunchMotionTimeline.sample(600L)
        assertEquals(0.5f, middle.frameFraction, 0.00001f)
        assertEquals(0.5f, middle.dotAlpha, 0.00001f)
        assertEquals(0.85f, middle.dotScale, 0.00001f)
        assertEquals(1f, LaunchMotionTimeline.sample(850L).dotAlpha, 0f)
        assertEquals(1f, LaunchMotionTimeline.sample(850L).dotScale, 0f)
        assertEquals(1f, LaunchMotionTimeline.sample(1_200L).frameFraction, 0f)
        assertTrue(LaunchMotionTimeline.sample(120L).frameFraction < 0.1f)
        assertTrue(LaunchMotionTimeline.sample(1_080L).frameFraction > 0.9f)
    }

    @Test
    fun motionTextStartsBelowAndSettlesAtItsOwnDeadline() {
        for ((start, middle, end, offset, wordmark) in listOf(
            listOf(250L, 575L, 900L, 8L, 1L),
            listOf(450L, 775L, 1_100L, 6L, 0L),
        )) {
            for ((time, fraction) in listOf(start to 0f, middle to 0.5f, end to 1f)) {
                val frame = LaunchMotionTimeline.sample(time)
                assertEquals(fraction, if (wordmark == 1L) frame.wordmarkAlpha else frame.taglineAlpha, 0.00001f)
                assertEquals(offset * (1f - fraction),
                    if (wordmark == 1L) frame.wordmarkOffsetDp else frame.taglineOffsetDp, 0.00001f)
            }
        }
    }

    @Test
    fun motionBreathesExactlyTwiceWithStationaryFrameAndText() {
        for (time in listOf(1_200L, 3_400L, 5_600L)) {
            val frame = LaunchMotionTimeline.sample(time)
            assertEquals(1f, frame.dotScale, 0f)
            assertEquals(1f, frame.dotAlpha, 0f)
        }
        for (time in listOf(2_300L, 4_500L)) {
            val frame = LaunchMotionTimeline.sample(time)
            assertEquals(1.08f, frame.dotScale, 0.00001f)
            assertEquals(0.82f, frame.dotAlpha, 0.00001f)
        }
        var peaks = 0
        for (time in 1_200L..5_600L) {
            val frame = LaunchMotionTimeline.sample(time)
            assertEquals(1f, frame.frameFraction, 0f)
            assertEquals(1f, frame.wordmarkAlpha, 0f)
            assertEquals(1f, frame.taglineAlpha, 0f)
            assertEquals(0f, frame.wordmarkOffsetDp, 0f)
            assertEquals(0f, frame.taglineOffsetDp, 0f)
            assertTrue(frame.dotAlpha in 0.82f..1f)
            assertTrue(frame.dotScale in 1f..1.08f)
            if (time > 1_200L && time < 5_600L &&
                frame.dotScale > LaunchMotionTimeline.sample(time - 10L).dotScale &&
                frame.dotScale > LaunchMotionTimeline.sample(time + 10L).dotScale &&
                time % 100L == 0L) peaks++
        }
        assertEquals(2, peaks)
    }

    @Test
    fun motionFreezesAfterFivePointSixSecondsWithoutEndingPresentationEarly() {
        state.startIfNeeded()
        now += LaunchMotionTimeline.DURATION_MILLIS
        val snapshot = state.snapshot(Initialization.READY, active = true)
        assertEquals(Phase.BRAND, snapshot.phase)
        assertEquals(400L, snapshot.remainingMillis)
        assertFalse(snapshot.canRoute)
        val settled = LaunchMotionTimeline.sample(5_600L)
        for (time in listOf(5_601L, 5_999L, 6_000L, 90_000L, Long.MAX_VALUE)) {
            assertEquals(settled, LaunchMotionTimeline.sample(time))
        }
        assertEquals(LaunchMotionTimeline.sample(0L), LaunchMotionTimeline.sample(-1L))
        now += 400L
        assertTrue(state.snapshot(Initialization.READY, active = true).canRoute)
    }

    @Test
    fun delayedRevealSamplesExistingDeadlineInsteadOfStartingAtZero() {
        state.startIfNeeded()
        now += 2_300L
        assertTrue(state.claimReveal())
        val elapsed = LaunchMotionTimeline.elapsedFromRemaining(state.snapshot(Initialization.PENDING).remainingMillis)
        assertEquals(2_300L, elapsed)
        assertEquals(3_300L, LaunchMotionTimeline.DURATION_MILLIS - elapsed)
        assertEquals(1.08f, LaunchMotionTimeline.sample(elapsed).dotScale, 0.00001f)
        now += 1_100L
        state.startIfNeeded()
        assertFalse(state.claimReveal())
        assertEquals(3_400L, LaunchMotionTimeline.elapsedFromRemaining(state.snapshot(Initialization.READY).remainingMillis))
    }

    @Test
    fun backgroundAndSkipNeverRestartClaimedMotionOrInitialization() {
        state.startIfNeeded()
        assertTrue(state.claimReveal())
        now += 2_000L
        assertEquals(4_000L, state.snapshot(Initialization.PENDING, active = false).remainingMillis)
        now += 1_000L
        assertFalse(state.claimReveal())
        assertEquals(3_000L, LaunchMotionTimeline.elapsedFromRemaining(state.snapshot(Initialization.PENDING, active = true).remainingMillis))
        state.skip()
        assertEquals(Phase.WAITING, state.snapshot(Initialization.PENDING, active = true).phase)
        assertEquals(6_000L, LaunchMotionTimeline.elapsedFromRemaining(state.snapshot(Initialization.PENDING).remainingMillis))
        assertFalse(state.claimReveal())
        assertTrue(state.snapshot(Initialization.READY, active = true).canRoute)
    }

    @Test
    fun newProcessGetsFreshPresentationEvenAfterPriorProcessSkipped() {
        state.skip()
        assertEquals(Phase.READY, state.snapshot(Initialization.READY).phase)
        val newProcess = LaunchPresentationState { now }
        assertEquals(Phase.BRAND, newProcess.snapshot(Initialization.READY).phase)
        assertEquals(6_000L, newProcess.snapshot(Initialization.READY).remainingMillis)
        assertTrue(newProcess.claimReveal())
    }
}
