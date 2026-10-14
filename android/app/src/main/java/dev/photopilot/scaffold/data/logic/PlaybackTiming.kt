package dev.photopilot.scaffold.data.logic

object PlaybackTiming {

    const val TRANSITION_NONE = "none"
    const val TRANSITION_CROSSFADE = "crossfade"
    const val TRANSITION_SLIDE = "slide"
    const val TRANSITION_WIPE = "wipe"
    const val TRANSITION_ZOOM = "zoom"
    const val TRANSITION_RIPPLE = "ripple"

    const val HOLD_MIN_SECONDS = 3
    const val HOLD_MAX_SECONDS = 3600
    const val TRANSITION_MIN_MILLIS = 200
    const val TRANSITION_MAX_MILLIS = 3000

    val transitionTypes: Set<String> = setOf(
        TRANSITION_NONE, TRANSITION_CROSSFADE, TRANSITION_SLIDE,
        TRANSITION_WIPE, TRANSITION_ZOOM, TRANSITION_RIPPLE,
    )

    fun transitionMillis(transition: String, transitionSeconds: Double): Long =
        if (transition == TRANSITION_NONE) 0L else Math.round(transitionSeconds * 1000.0)

    fun nextTransitionAt(t0: Long, holdSeconds: Int): Long = t0 + holdSeconds * 1000L

    fun nextStableAt(t0: Long, holdSeconds: Int, transition: String, transitionSeconds: Double): Long =
        nextTransitionAt(t0, holdSeconds) + transitionMillis(transition, transitionSeconds)

    fun remainingAtPause(nextTransitionAt: Long, pauseAt: Long): Long =
        maxOf(0L, nextTransitionAt - pauseAt)

    fun resumeTransitionAt(resumeAt: Long, remaining: Long): Long = resumeAt + remaining

    fun isValidHoldSeconds(holdSeconds: Double): Boolean {
        val rounded = Math.round(holdSeconds)
        return kotlin.math.abs(holdSeconds - rounded) < 1e-9 &&
            rounded >= HOLD_MIN_SECONDS && rounded <= HOLD_MAX_SECONDS
    }

    fun isValidTransitionSeconds(transitionSeconds: Double): Boolean {
        val tenths = Math.round(transitionSeconds * 10.0)
        if (kotlin.math.abs(transitionSeconds * 10.0 - tenths) >= 1e-9) return false
        val millis = tenths * 100
        return millis >= TRANSITION_MIN_MILLIS && millis <= TRANSITION_MAX_MILLIS
    }

    fun isValidSettings(holdSeconds: Double, transition: String, transitionSeconds: Double): Boolean {
        if (!isValidHoldSeconds(holdSeconds)) return false
        if (transition !in transitionTypes) return false
        if (transition == TRANSITION_NONE) return true
        return isValidTransitionSeconds(transitionSeconds)
    }

    fun restActive(enabled: Boolean, startMinute: Int, endMinute: Int, nowMinute: Int): Boolean {
        if (!enabled || startMinute == endMinute) return false
        return if (startMinute > endMinute) {
            nowMinute >= startMinute || nowMinute < endMinute
        } else {
            nowMinute >= startMinute && nowMinute < endMinute
        }
    }
}
