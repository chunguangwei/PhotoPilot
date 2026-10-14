import XCTest
@testable import PhotoPilot

final class LaunchPresentationTests: XCTestCase {
    private struct Contract: Decodable {
        let durationMs: Int
        let clock: String
        let cases: [Vector]
    }

    private struct Vector: Decodable {
        let id: String
        let elapsedMs: Int
        let skipped: Bool
        let ready: Bool
        let failed: Bool
        let active: Bool
        let expected: String
    }

    private func loadContract() throws -> Contract {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(
            forResource: "launch-presentation", withExtension: "json"
        ))
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(Contract.self, from: Data(contentsOf: url))
    }

    func testLaunchPresentationContractVectors() throws {
        let contract = try loadContract()
        XCTAssertEqual(contract.durationMs, 6000)
        XCTAssertEqual(LaunchPresentationState.duration * 1000, Double(contract.durationMs))
        XCTAssertEqual(contract.clock, "monotonic_process_local")
        XCTAssertEqual(contract.cases.map(\.id), (1...10).map { String(format: "LAUNCH-%02d", $0) })
        for vector in contract.cases {
            var presentation = LaunchPresentationState()
            var readiness = LaunchReadinessState()
            let start: TimeInterval = 100
            let now = start + Double(vector.elapsedMs) / 1000
            presentation.beginIfNeeded(now: start)
            readiness.beginLoading()
            if vector.skipped { presentation.skip() }
            if vector.failed {
                readiness.failLoading()
                presentation.finish()
            } else if vector.ready {
                readiness.completeLoading()
            }
            let expected = try XCTUnwrap(
                LaunchReadinessState.PresentationPhase(rawValue: vector.expected), vector.id
            )
            XCTAssertEqual(
                readiness.presentationPhase(
                    presentation: presentation, now: now, isVisible: true, isActive: vector.active
                ), expected, vector.id
            )
            XCTAssertEqual(
                readiness.canRoute(
                    presentation: presentation, now: now, isVisible: true, isActive: vector.active
                ), expected == .route, vector.id
            )
            XCTAssertEqual(
                readiness.phase, vector.failed ? .failed : (vector.ready ? .ready : .loading), vector.id
            )
            XCTAssertFalse(
                readiness.canRoute(
                    presentation: presentation, now: now, isVisible: false, isActive: vector.active
                ), vector.id + " offscreen"
            )
            XCTAssertFalse(
                readiness.canRoute(
                    presentation: presentation, now: now, isVisible: true, isActive: false
                ), vector.id + " inactive"
            )
        }
    }

    func testSixSecondBoundaryUsesInjectedMonotonicTime() {
        var state = LaunchPresentationState()
        XCTAssertTrue(state.beginIfNeeded(now: 100))
        XCTAssertEqual(state.deadline, 106)
        XCTAssertEqual(state.remaining(now: 100), 6)
        XCTAssertTrue(state.isPresenting(now: 105.999))
        XCTAssertFalse(state.isPresenting(now: 106))
        XCTAssertEqual(state.remaining(now: 107), 0)
    }

    func testRepeatedBeginDoesNotRestartDeadline() {
        var state = LaunchPresentationState()
        state.beginIfNeeded(now: 100)
        XCTAssertFalse(state.beginIfNeeded(now: 103))
        XCTAssertFalse(state.beginIfNeeded(now: 200))
        XCTAssertEqual(state.startedAt, 100)
        XCTAssertEqual(state.deadline, 106)
    }

    func testSkipOnFirstFrameDoesNotMakeLibraryReady() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        state.skip()
        XCTAssertTrue(state.wasSkipped)
        XCTAssertFalse(state.isPresenting(now: 100))
        XCTAssertEqual(readiness.phase, .loading)
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 100, isVisible: true, isActive: true))
        readiness.completeLoading()
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 100.1, isVisible: true, isActive: true))
    }

    func testReadyBeforeDeadlineWaitsUntilExactlySixSeconds() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        readiness.completeLoading()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 105.999, isVisible: true, isActive: true))
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 106, isVisible: true, isActive: true))
    }

    func testDeadlineDoesNotRouteBeforeInitializationFinishes() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 120, isVisible: true, isActive: true))
        readiness.completeLoading()
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 120, isVisible: true, isActive: true))
    }

    func testReadyThenSkipRoutesWithoutWaiting() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        readiness.completeLoading()
        state.skip()
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 101, isVisible: true, isActive: true))
    }

    func testFailureEndsBrandingAndRetryDoesNotRestartIt() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        readiness.failLoading()
        state.finish()
        XCTAssertFalse(state.isPresenting(now: 100.1))
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 100.1, isVisible: true, isActive: true))
        XCTAssertTrue(readiness.beginLoading())
        XCTAssertFalse(state.beginIfNeeded(now: 100.2))
        readiness.completeLoading()
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 100.3, isVisible: true, isActive: true))
    }

    func testSkipNeverBypassesFailure() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        state.skip()
        readiness.failLoading()
        state.finish()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 110, isVisible: true, isActive: true))
        readiness.completeLoading()
        XCTAssertEqual(readiness.phase, .failed)
    }

    func testOffscreenReadyResultRemainsRoutableWhenVisibleAgain() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        readiness.completeLoading()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 108, isVisible: false, isActive: true))
        XCTAssertEqual(readiness.phase, .ready)
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 109, isVisible: true, isActive: true))
    }

    func testBackgroundTimeDoesNotRestartBrandingAndActiveIsRequired() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        readiness.beginLoading()
        readiness.completeLoading()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 120, isVisible: true, isActive: false))
        XCTAssertFalse(state.beginIfNeeded(now: 120))
        XCTAssertTrue(readiness.canRoute(presentation: state, now: 120, isVisible: true, isActive: true))
    }

    func testRouteCanOnlyBeConsumedOnce() {
        var state = LaunchPresentationState()
        var readiness = LaunchReadinessState()
        state.beginIfNeeded(now: 100)
        state.skip()
        readiness.beginLoading()
        readiness.completeLoading()
        readiness.markRouted()
        XCTAssertFalse(readiness.canRoute(presentation: state, now: 101, isVisible: true, isActive: true))
        XCTAssertFalse(readiness.beginLoading())
    }

    func testDuplicateLoadIsRejectedWhileLoadingOrReady() {
        var readiness = LaunchReadinessState()
        XCTAssertTrue(readiness.beginLoading())
        XCTAssertFalse(readiness.beginLoading())
        readiness.completeLoading()
        XCTAssertFalse(readiness.beginLoading())
    }

    func testBrandProgressIsIndependentOfInitialization() {
        var state = LaunchPresentationState()
        state.beginIfNeeded(now: 100)
        XCTAssertEqual(state.progress(now: 100), 0)
        XCTAssertEqual(state.progress(now: 103), 0.5)
        XCTAssertEqual(state.progress(now: 106), 1)
        XCTAssertEqual(state.progress(now: 110), 1)
    }

    func testNewProcessStateGetsNewFullBrandingWindow() {
        var previousProcess = LaunchPresentationState()
        previousProcess.beginIfNeeded(now: 100)
        previousProcess.skip()
        var newProcess = LaunchPresentationState()
        XCTAssertTrue(newProcess.beginIfNeeded(now: 200))
        XCTAssertFalse(newProcess.wasSkipped)
        XCTAssertEqual(newProcess.remaining(now: 200), 6)
    }

    func testMultipleControllersShareDeadlineAndSkipWithinProcess() async {
        await MainActor.run {
            let session = LaunchPresentationSession()
            let firstControllerSession = session
            let secondControllerSession = session
            XCTAssertTrue(firstControllerSession.beginIfNeeded(now: 100))
            XCTAssertFalse(secondControllerSession.beginIfNeeded(now: 102))
            XCTAssertEqual(secondControllerSession.state.remaining(now: 102), 4)
            firstControllerSession.skip()
            XCTAssertFalse(secondControllerSession.state.isPresenting(now: 102))
            XCTAssertTrue(secondControllerSession.state.wasSkipped)
            XCTAssertFalse(secondControllerSession.beginIfNeeded(now: 110))
        }
    }

    func testFailureInOneControllerEndsSharedBrandingForRetryAndRecreation() async {
        await MainActor.run {
            let session = LaunchPresentationSession()
            session.beginIfNeeded(now: 100)
            session.finish()
            XCTAssertFalse(session.beginIfNeeded(now: 101))
            XCTAssertEqual(session.state.remaining(now: 101), 0)
            XCTAssertFalse(session.state.wasSkipped)
        }
    }
}
