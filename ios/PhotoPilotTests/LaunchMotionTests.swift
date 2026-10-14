import XCTest
import UIKit
@testable import PhotoPilot

@MainActor
final class LaunchMotionTests: XCTestCase {
    func testStopClearsAllBrandAnimationsWithoutHidingSkip() {
        let view = LaunchBrandingView(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        var state = LaunchPresentationState()
        state.beginIfNeeded(now: 0)
        view.updatePresentation(state, now: 0.1, animate: true)
        XCTAssertFalse(view.skipButton.isHidden)
        XCTAssertNil(view.skipButton.layer.animationKeys())
        view.stopAnimations()
        XCTAssertTrue(allLayers(view.layer).allSatisfy { ($0.animationKeys() ?? []).isEmpty })
        XCTAssertFalse(view.skipButton.isHidden)
    }

    func testSkippedPresentationStopsMotionAndKeepsTextVisible() {
        let view = LaunchBrandingView(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        var state = LaunchPresentationState()
        state.beginIfNeeded(now: 0)
        view.updatePresentation(state, now: 0.2, animate: true)
        state.skip()
        view.updatePresentation(state, now: 0.3, animate: false)
        XCTAssertTrue(view.skipButton.isHidden)
        let labels = allViews(view).compactMap { $0 as? UILabel }
        XCTAssertTrue(labels.allSatisfy { $0.alpha == 1 && $0.transform == .identity })
        XCTAssertTrue(allLayers(view.layer).allSatisfy { ($0.animationKeys() ?? []).isEmpty })
    }

    func testBrandMotionEndsBeforeSixSecondDeadline() {
        let mark = BrandMarkView(frame: CGRect(x: 0, y: 0, width: 152, height: 152))
        mark.reveal(elapsed: 5.6)
        XCTAssertTrue(allLayers(mark.layer).allSatisfy { ($0.animationKeys() ?? []).isEmpty })
        mark.reveal(elapsed: 6)
        XCTAssertTrue(allLayers(mark.layer).allSatisfy { ($0.animationKeys() ?? []).isEmpty })
        XCTAssertEqual(LaunchPresentationState.duration, 6)
    }

    func testSharedBrandMarkIsStaticUnlessExplicitlyAnimated() {
        let mark = BrandMarkView(frame: CGRect(x: 0, y: 0, width: 48, height: 48))
        mark.layoutIfNeeded()
        XCTAssertTrue(allLayers(mark.layer).allSatisfy { ($0.animationKeys() ?? []).isEmpty })
    }

    private func allLayers(_ layer: CALayer) -> [CALayer] {
        [layer] + (layer.sublayers ?? []).flatMap(allLayers)
    }

    private func allViews(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(allViews)
    }
}
