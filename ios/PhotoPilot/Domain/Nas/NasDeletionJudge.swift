import Foundation

struct NasDeletionItemState: Sendable, Equatable {
    var missingScanCount = 0
    var firstMissingAtMs: Int64?
}

enum NasDeletionVerdict: String, Sendable {
    case pending
    case removed
    case needsConfirmation = "needs_confirmation"
    case cleared
}

enum NasDeletionJudge {
    static let minIntervalMs: Int64 = 60_000
    static let confirmationThresholdPercent = 20

    static func applyScan(
        state: inout NasDeletionItemState,
        complete: Bool,
        authOK: Bool,
        nowMs: Int64,
        seen: Bool
    ) {
        guard complete, authOK else { return }
        if seen {
            state.missingScanCount = 0
            state.firstMissingAtMs = nil
        } else {
            state.missingScanCount += 1
            if state.firstMissingAtMs == nil {
                state.firstMissingAtMs = nowMs
            }
        }
    }

    static func isDeletionEligible(state: NasDeletionItemState, nowMs: Int64) -> Bool {
        guard state.missingScanCount >= 2, let first = state.firstMissingAtMs else { return false }
        return nowMs - first >= minIntervalMs
    }

    static func judgeBatch(candidates: Int, batchSize: Int) -> NasDeletionVerdict {
        guard batchSize > 0, candidates > 0 else { return .pending }
        if candidates * 100 > batchSize * confirmationThresholdPercent {
            return .needsConfirmation
        }
        return .removed
    }
}
