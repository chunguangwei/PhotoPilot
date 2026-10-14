package dev.photopilot.scaffold.ui.launch

class LaunchPresentationState(private val monotonicMillis: () -> Long) {

    enum class Initialization { PENDING, READY, FAILED }
    enum class Phase { BRAND, WAITING, READY, FAILED }

    data class Snapshot(val phase: Phase, val remainingMillis: Long, val progress: Float, val canRoute: Boolean)

    private var deadlineMillis: Long? = null
    private var ended = false
    private var revealClaimed = false

    fun startIfNeeded() {
        if (deadlineMillis == null) deadlineMillis = monotonicMillis() + BRAND_DURATION_MILLIS
    }

    fun skip() {
        ended = true
    }

    fun initializationFailed() {
        ended = true
    }

    fun claimReveal(): Boolean {
        if (revealClaimed || snapshot(Initialization.PENDING).phase != Phase.BRAND) return false
        revealClaimed = true
        return true
    }

    fun snapshot(initialization: Initialization, active: Boolean = false): Snapshot {
        startIfNeeded()
        if (initialization == Initialization.FAILED) initializationFailed()
        val remaining = if (ended) 0L else
            (requireNotNull(deadlineMillis) - monotonicMillis()).coerceIn(0L, BRAND_DURATION_MILLIS)
        if (remaining == 0L) ended = true
        val phase = when {
            initialization == Initialization.FAILED -> Phase.FAILED
            remaining > 0L -> Phase.BRAND
            initialization == Initialization.READY -> Phase.READY
            else -> Phase.WAITING
        }
        return Snapshot(phase, remaining, 1f - remaining.toFloat() / BRAND_DURATION_MILLIS, phase == Phase.READY && active)
    }

    companion object {
        fun isActive(attached: Boolean, windowVisible: Boolean, shown: Boolean, windowFocused: Boolean): Boolean =
            attached && windowVisible && shown && windowFocused

        const val BRAND_DURATION_MILLIS = 6_000L
    }
}
