import Foundation

enum PlaybackTiming {
    static let transitionNone = "none"
    static let transitionCrossfade = "crossfade"
    static let transitionSlide = "slide"
    static let transitionWipe = "wipe"
    static let transitionZoom = "zoom"
    static let transitionRipple = "ripple"

    static let holdMinSeconds = 3
    static let holdMaxSeconds = 3600
    static let transitionMinMillis = 200
    static let transitionMaxMillis = 3000

    static let transitionTypes: Set<String> = [
        transitionNone, transitionCrossfade, transitionSlide,
        transitionWipe, transitionZoom, transitionRipple,
    ]

    static func transitionMillis(transition: String, transitionSeconds: Double) -> Int64 {
        transition == transitionNone ? 0 : Int64((transitionSeconds * 1000.0).rounded())
    }

    static func nextTransitionAt(t0: Int64, holdSeconds: Int) -> Int64 {
        t0 + Int64(holdSeconds) * 1000
    }

    static func nextStableAt(t0: Int64, holdSeconds: Int, transition: String, transitionSeconds: Double) -> Int64 {
        nextTransitionAt(t0: t0, holdSeconds: holdSeconds) + transitionMillis(
            transition: transition,
            transitionSeconds: transitionSeconds
        )
    }

    static func remainingAtPause(nextTransitionAt: Int64, pauseAt: Int64) -> Int64 {
        max(0, nextTransitionAt - pauseAt)
    }

    static func resumeTransitionAt(resumeAt: Int64, remaining: Int64) -> Int64 {
        resumeAt + remaining
    }

    static func isValidHoldSeconds(_ holdSeconds: Double) -> Bool {
        let rounded = holdSeconds.rounded()
        guard abs(holdSeconds - rounded) < 1e-9 else { return false }
        let value = Int(rounded)
        return value >= holdMinSeconds && value <= holdMaxSeconds
    }

    static func isValidTransitionSeconds(_ transitionSeconds: Double) -> Bool {
        let tenths = (transitionSeconds * 10.0).rounded()
        guard abs(transitionSeconds * 10.0 - tenths) < 1e-9 else { return false }
        let millis = Int(tenths) * 100
        return millis >= transitionMinMillis && millis <= transitionMaxMillis
    }

    static func isValidSettings(holdSeconds: Double, transition: String, transitionSeconds: Double) -> Bool {
        guard isValidHoldSeconds(holdSeconds) else { return false }
        guard transitionTypes.contains(transition) else { return false }
        if transition == transitionNone { return true }
        return isValidTransitionSeconds(transitionSeconds)
    }

    static func restActive(enabled: Bool, startMinute: Int, endMinute: Int, nowMinute: Int) -> Bool {
        guard enabled, startMinute != endMinute else { return false }
        if startMinute > endMinute {
            return nowMinute >= startMinute || nowMinute < endMinute
        }
        return nowMinute >= startMinute && nowMinute < endMinute
    }
}
