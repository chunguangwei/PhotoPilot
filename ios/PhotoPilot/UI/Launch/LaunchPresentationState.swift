import Foundation

struct LaunchPresentationState {
    static let duration: TimeInterval = 6

    private(set) var startedAt: TimeInterval?
    private(set) var deadline: TimeInterval?
    private(set) var wasSkipped = false
    private(set) var hasEnded = false

    @discardableResult
    mutating func beginIfNeeded(now: TimeInterval) -> Bool {
        guard startedAt == nil, !hasEnded else { return false }
        startedAt = now
        deadline = now + Self.duration
        return true
    }

    func remaining(now: TimeInterval) -> TimeInterval {
        guard !hasEnded, let deadline else { return 0 }
        return max(0, min(Self.duration, deadline - now))
    }

    func isPresenting(now: TimeInterval) -> Bool {
        remaining(now: now) > 0
    }

    func progress(now: TimeInterval) -> Double {
        guard startedAt != nil else { return 0 }
        return 1 - remaining(now: now) / Self.duration
    }

    mutating func skip() {
        wasSkipped = true
        hasEnded = true
    }

    mutating func finish() {
        hasEnded = true
    }
}

struct LaunchReadinessState {
    enum Phase {
        case idle
        case loading
        case ready
        case failed
        case routed
    }

    enum PresentationPhase: String {
        case idle
        case brand
        case loading
        case failure
        case route
        case deferRoute = "defer_route"
        case routed
    }

    private(set) var phase: Phase = .idle

    @discardableResult
    mutating func beginLoading() -> Bool {
        guard phase == .idle || phase == .failed else { return false }
        phase = .loading
        return true
    }

    mutating func completeLoading() {
        guard phase == .loading else { return }
        phase = .ready
    }

    mutating func failLoading() {
        guard phase == .loading else { return }
        phase = .failed
    }

    func presentationPhase(
        presentation: LaunchPresentationState,
        now: TimeInterval,
        isVisible: Bool,
        isActive: Bool
    ) -> PresentationPhase {
        switch phase {
        case .idle: return .idle
        case .failed: return .failure
        case .routed: return .routed
        case .loading, .ready:
            if presentation.isPresenting(now: now) { return .brand }
            if phase == .loading { return .loading }
            return isVisible && isActive ? .route : .deferRoute
        }
    }

    func canRoute(
        presentation: LaunchPresentationState,
        now: TimeInterval,
        isVisible: Bool,
        isActive: Bool
    ) -> Bool {
        presentationPhase(
            presentation: presentation,
            now: now,
            isVisible: isVisible,
            isActive: isActive
        ) == .route
    }

    mutating func markRouted() {
        guard phase == .ready else { return }
        phase = .routed
    }
}

@MainActor
final class LaunchPresentationSession: NSObject {
    static let shared = LaunchPresentationSession()
    static let didChangeNotification = Notification.Name("PhotoPilot.LaunchPresentationSession.didChange")

    private(set) var state = LaunchPresentationState()

    @discardableResult
    func beginIfNeeded(now: TimeInterval) -> Bool {
        state.beginIfNeeded(now: now)
    }

    func skip() {
        state.skip()
        notifyChange()
    }

    func finish() {
        guard !state.hasEnded else { return }
        state.finish()
        notifyChange()
    }

    private func notifyChange() {
        NotificationCenter.default.post(name: Self.didChangeNotification, object: self)
    }
}
