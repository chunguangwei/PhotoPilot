package dev.photopilot.scaffold.data.logic

data class PlaybackEvent(
    val type: String,
    val nextReady: Boolean? = null,
    val queueIntact: Boolean? = null,
    val playableRemaining: Boolean? = null,
    val category: String? = null,
    val limitReached: Boolean? = null,
)

data class PlaybackOutcome(
    val state: String,
    val effects: List<String>,
)

object PlaybackController {

    const val IDLE = "idle"
    const val PREPARING = "preparing"
    const val HOLDING = "holding"
    const val TRANSITIONING = "transitioning"
    const val PAUSED = "paused"
    const val SUSPENDED = "suspended"
    const val EMPTY = "empty"
    const val RESTING = "resting"
    const val FAILED = "failed"

    const val EFFECT_CLEAR_TEXTURES = "clear_textures"
    const val EFFECT_SHOW_SAFE_IMAGE = "show_safe_image"
    const val EFFECT_REBUILD_QUEUE = "rebuild_queue"
    const val EFFECT_REBUILD_AT_SAFE_POINT = "rebuild_queue_at_safe_point"
    const val EFFECT_APPLY_PENDING_REBUILD = "apply_pending_rebuild"
    const val EFFECT_PERSIST_SNAPSHOT = "persist_snapshot"
    const val EFFECT_SAVE_REMAINING_HOLD = "save_remaining_hold"
    const val EFFECT_COMPLETE_TRANSITION = "complete_transition"
    const val EFFECT_RESET_HOLD_TIMER = "reset_hold_timer"
    const val EFFECT_RESUME_UNTIL_REST_END = "resume_until_rest_end"
    const val EFFECT_SKIP_TO_NEXT = "skip_to_next"

    private val revokeBaseEffects = arrayOf(
        EFFECT_CLEAR_TEXTURES,
        EFFECT_REBUILD_AT_SAFE_POINT,
        EFFECT_SHOW_SAFE_IMAGE,
    )

    fun decodeFailLimit(queueLength: Int): Int = minOf(5, queueLength)

    fun reduce(state: String, event: PlaybackEvent): PlaybackOutcome = when (event.type) {
        "start" -> outcome(PREPARING, EFFECT_REBUILD_QUEUE)
        "first_ready" -> if (state == PREPARING) outcome(HOLDING, EFFECT_RESET_HOLD_TIMER) else stay(state)
        "none_playable" -> if (state == PREPARING) outcome(EMPTY) else stay(state)
        "hold_elapsed" -> when {
            state != HOLDING -> stay(state)
            event.nextReady == true -> outcome(TRANSITIONING)
            else -> stay(HOLDING)
        }
        "user_next", "user_prev" ->
            if (state == HOLDING) outcome(TRANSITIONING) else stay(state)
        "transition_finished" ->
            if (state == TRANSITIONING) {
                outcome(HOLDING, EFFECT_APPLY_PENDING_REBUILD, EFFECT_RESET_HOLD_TIMER)
            } else {
                stay(state)
            }
        "user_pause" -> when (state) {
            TRANSITIONING -> outcome(PAUSED, EFFECT_COMPLETE_TRANSITION, EFFECT_SAVE_REMAINING_HOLD)
            HOLDING -> outcome(PAUSED, EFFECT_SAVE_REMAINING_HOLD)
            else -> stay(state)
        }
        "user_resume" -> if (state == PAUSED) outcome(HOLDING) else stay(state)
        "app_background" -> when (state) {
            HOLDING, PAUSED, TRANSITIONING -> outcome(SUSPENDED, EFFECT_PERSIST_SNAPSHOT)
            else -> stay(state)
        }
        "app_foreground" -> when {
            state != SUSPENDED -> stay(state)
            event.queueIntact == true -> outcome(HOLDING)
            else -> outcome(PREPARING, EFFECT_REBUILD_QUEUE)
        }
        "asset_revoked" -> revokeOutcome(state, event.playableRemaining != false)
        "selection_changed" -> when (event.category) {
            "revoke" -> revokeOutcome(state, event.playableRemaining != false)
            "add" -> when (state) {
                EMPTY -> outcome(PREPARING, EFFECT_REBUILD_QUEUE)
                else -> outcome(state, EFFECT_REBUILD_AT_SAFE_POINT)
            }
            else -> outcome(state, EFFECT_REBUILD_AT_SAFE_POINT)
        }
        "rest_window_start" -> when (state) {
            HOLDING, PAUSED, TRANSITIONING -> outcome(RESTING, EFFECT_PERSIST_SNAPSHOT)
            else -> stay(state)
        }
        "user_tap" ->
            if (state == RESTING) outcome(HOLDING, EFFECT_RESUME_UNTIL_REST_END) else stay(state)
        "decode_fail" -> when {
            event.limitReached == true -> outcome(FAILED)
            state == HOLDING || state == TRANSITIONING -> outcome(state, EFFECT_SKIP_TO_NEXT)
            else -> stay(state)
        }
        "user_retry" -> if (state == FAILED) outcome(PREPARING, EFFECT_REBUILD_QUEUE) else stay(state)
        "takeover_by_other_window" -> outcome(IDLE)
        "window_resize" -> stay(state)
        else -> stay(state)
    }

    private fun revokeOutcome(state: String, playableRemaining: Boolean): PlaybackOutcome = when (state) {
        PAUSED, SUSPENDED, RESTING -> outcome(state, *revokeBaseEffects)
        else -> if (playableRemaining) {
            outcome(HOLDING, *revokeBaseEffects)
        } else {
            outcome(EMPTY, EFFECT_CLEAR_TEXTURES, EFFECT_SHOW_SAFE_IMAGE)
        }
    }

    private fun outcome(state: String, vararg effects: String) =
        PlaybackOutcome(state, effects.sorted())

    private fun stay(state: String) = PlaybackOutcome(state, emptyList())
}
