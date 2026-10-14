import Foundation

struct PlaybackEvent: Sendable {
    let type: String
    var nextReady: Bool?
    var queueIntact: Bool?
    var playableRemaining: Bool?
    var category: String?
    var limitReached: Bool?

    init(
        type: String,
        nextReady: Bool? = nil,
        queueIntact: Bool? = nil,
        playableRemaining: Bool? = nil,
        category: String? = nil,
        limitReached: Bool? = nil
    ) {
        self.type = type
        self.nextReady = nextReady
        self.queueIntact = queueIntact
        self.playableRemaining = playableRemaining
        self.category = category
        self.limitReached = limitReached
    }
}

struct PlaybackOutcome: Sendable, Equatable {
    let state: String
    let effects: [String]
}

enum PlaybackController {
    static let idle = "idle"
    static let preparing = "preparing"
    static let holding = "holding"
    static let transitioning = "transitioning"
    static let paused = "paused"
    static let suspended = "suspended"
    static let empty = "empty"
    static let resting = "resting"
    static let failed = "failed"

    static let effectClearTextures = "clear_textures"
    static let effectShowSafeImage = "show_safe_image"
    static let effectRebuildQueue = "rebuild_queue"
    static let effectRebuildAtSafePoint = "rebuild_queue_at_safe_point"
    static let effectApplyPendingRebuild = "apply_pending_rebuild"
    static let effectPersistSnapshot = "persist_snapshot"
    static let effectSaveRemainingHold = "save_remaining_hold"
    static let effectCompleteTransition = "complete_transition"
    static let effectResetHoldTimer = "reset_hold_timer"
    static let effectResumeUntilRestEnd = "resume_until_rest_end"
    static let effectSkipToNext = "skip_to_next"

    private static var revokeBaseEffects: [String] {
        [effectClearTextures, effectRebuildAtSafePoint, effectShowSafeImage]
    }

    static func decodeFailLimit(queueLength: Int) -> Int {
        min(5, queueLength)
    }

    static func reduce(state: String, event: PlaybackEvent) -> PlaybackOutcome {
        switch event.type {
        case "start":
            return outcome(preparing, effectRebuildQueue)
        case "first_ready":
            return state == preparing ? outcome(holding, effectResetHoldTimer) : stay(state)
        case "none_playable":
            return state == preparing ? outcome(empty) : stay(state)
        case "hold_elapsed":
            guard state == holding else { return stay(state) }
            return event.nextReady == true ? outcome(transitioning) : stay(holding)
        case "user_next", "user_prev":
            return state == holding ? outcome(transitioning) : stay(state)
        case "transition_finished":
            return state == transitioning
                ? outcome(holding, effectApplyPendingRebuild, effectResetHoldTimer)
                : stay(state)
        case "user_pause":
            switch state {
            case transitioning:
                return outcome(paused, effectCompleteTransition, effectSaveRemainingHold)
            case holding:
                return outcome(paused, effectSaveRemainingHold)
            default:
                return stay(state)
            }
        case "user_resume":
            return state == paused ? outcome(holding) : stay(state)
        case "app_background":
            switch state {
            case holding, paused, transitioning:
                return outcome(suspended, effectPersistSnapshot)
            default:
                return stay(state)
            }
        case "app_foreground":
            guard state == suspended else { return stay(state) }
            return event.queueIntact == true ? outcome(holding) : outcome(preparing, effectRebuildQueue)
        case "asset_revoked":
            return revokeOutcome(state: state, playableRemaining: event.playableRemaining != false)
        case "selection_changed":
            switch event.category {
            case "revoke":
                return revokeOutcome(state: state, playableRemaining: event.playableRemaining != false)
            case "add":
                return state == empty
                    ? outcome(preparing, effectRebuildQueue)
                    : outcome(state, effectRebuildAtSafePoint)
            default:
                return outcome(state, effectRebuildAtSafePoint)
            }
        case "rest_window_start":
            switch state {
            case holding, paused, transitioning:
                return outcome(resting, effectPersistSnapshot)
            default:
                return stay(state)
            }
        case "user_tap":
            return state == resting ? outcome(holding, effectResumeUntilRestEnd) : stay(state)
        case "decode_fail":
            if event.limitReached == true { return outcome(failed) }
            if state == holding || state == transitioning { return outcome(state, effectSkipToNext) }
            return stay(state)
        case "user_retry":
            return state == failed ? outcome(preparing, effectRebuildQueue) : stay(state)
        case "takeover_by_other_window":
            return outcome(idle)
        case "window_resize":
            return stay(state)
        default:
            return stay(state)
        }
    }

    private static func revokeOutcome(state: String, playableRemaining: Bool) -> PlaybackOutcome {
        switch state {
        case paused, suspended, resting:
            return outcome(state, revokeBaseEffects)
        default:
            if playableRemaining {
                return outcome(holding, revokeBaseEffects)
            }
            return outcome(empty, [effectClearTextures, effectShowSafeImage])
        }
    }

    private static func outcome(_ state: String, _ effects: [String]) -> PlaybackOutcome {
        PlaybackOutcome(state: state, effects: effects.sorted())
    }

    private static func outcome(_ state: String, _ effects: String...) -> PlaybackOutcome {
        outcome(state, effects)
    }

    private static func stay(_ state: String) -> PlaybackOutcome {
        PlaybackOutcome(state: state, effects: [])
    }
}
