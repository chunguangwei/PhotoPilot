import XCTest
import UIKit
@testable import PhotoPilot

@MainActor
final class PhotoPilotUIReviewTests: XCTestCase {
    func testActionButtonWrapsAtAccessibilitySize() {
        let button = ActionButton(titleKey: "welcome.primary_action", style: .primary)
        let controller = UIViewController()
        controller.view.addSubview(button)
        button.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor, constant: 16),
            button.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor, constant: -16),
            button.topAnchor.constraint(equalTo: controller.view.topAnchor, constant: 24)
        ])
        let host = UIViewController()
        host.addChild(controller)
        host.view.addSubview(controller.view)
        controller.didMove(toParent: host)
        host.setOverrideTraitCollection(
            UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge),
            forChild: controller
        )
        controller.view.frame = CGRect(x: 0, y: 0, width: 280, height: 500)
        controller.view.layoutIfNeeded()
        XCTAssertGreaterThanOrEqual(button.bounds.height, 44)
        XCTAssertLessThanOrEqual(button.frame.maxX, 264)
        let label = button.subviews.compactMap { $0 as? UILabel }.first
        XCTAssertEqual(label?.numberOfLines, 0)
        XCTAssertGreaterThanOrEqual(button.bounds.height, label?.bounds.height ?? 0)
    }

    func testActionButtonSelectionAndDisabledSemantics() {
        let button = ActionButton(titleKey: "common.continue", style: .primary)
        button.isOptionSelected = true
        XCTAssertTrue(button.accessibilityTraits.contains(.selected))
        button.isEnabled = false
        XCTAssertTrue(button.accessibilityTraits.contains(.notEnabled))
        XCTAssertLessThan(button.alpha, 1)
        button.isHighlighted = true
        XCTAssertLessThan(button.alpha, 0.5)
        button.isEnabled = true
        button.isHighlighted = false
        XCTAssertEqual(button.alpha, 1)
        XCTAssertFalse(button.accessibilityTraits.contains(.notEnabled))
    }

    func testBrandingLayoutAndSkipTarget() {
        for width in [393, 1194] {
            let controller = UIViewController()
            let branding = LaunchBrandingView()
            controller.view = branding
            var state = LaunchPresentationState()
            state.beginIfNeeded(now: 0)
            branding.updateStatus(isLoading: false, isSlow: false)
            branding.updatePresentation(state, now: 2, animate: false)
            let window = show(controller, size: CGSize(width: CGFloat(width), height: 834))
            XCTAssertFalse(branding.skipButton.isHidden)
            XCTAssertGreaterThanOrEqual(branding.skipButton.bounds.height, 44)
            XCTAssertGreaterThanOrEqual(branding.skipButton.frame.minY, 0)
            XCTAssertLessThanOrEqual(branding.skipButton.frame.maxX, CGFloat(width))
            saveSnapshot(window, name: "launch-\(width)")
            window.isHidden = true
        }
    }

    func testWelcomePhoneAndTabletLayouts() throws {
        let environment = makeEnvironment()
        for size in [CGSize(width: 393, height: 852), CGSize(width: 1194, height: 834)] {
            let controller = WelcomeViewController(environment: environment)
            let window = show(controller, size: size)
            XCTAssertFalse(controller.view.hasAmbiguousLayout)
            saveSnapshot(window, name: "welcome-\(Int(size.width))")
            window.isHidden = true
        }
    }

    func testMainSectionsLightDarkAndLargeText() async throws {
        let environment = makeEnvironment()
        _ = try await environment.frameManager.createFrame(name: "Morning light")
        _ = try await environment.frameManager.createFrame(name: "A quiet collection of everyday moments")
        for (width, height, dark, large) in [
            (393, 852, false, false),
            (1194, 834, false, false),
            (834, 1194, true, false),
            (393, 852, false, true)
        ] {
            for section in ["nav.frames", "nav.library", "nav.sources", "nav.settings"] {
                let controller = MainContainerViewController(environment: environment)
                controller.initialSectionTitleKey = section
                let host = UIViewController()
                host.addChild(controller)
                host.view.addSubview(controller.view)
                controller.view.translatesAutoresizingMaskIntoConstraints = false
                NSLayoutConstraint.activate([
                    controller.view.topAnchor.constraint(equalTo: host.view.topAnchor),
                    controller.view.bottomAnchor.constraint(equalTo: host.view.bottomAnchor),
                    controller.view.leadingAnchor.constraint(equalTo: host.view.leadingAnchor),
                    controller.view.trailingAnchor.constraint(equalTo: host.view.trailingAnchor)
                ])
                controller.didMove(toParent: host)
                host.setOverrideTraitCollection(
                    UITraitCollection(traitsFrom: [
                        UITraitCollection(userInterfaceStyle: dark ? .dark : .light),
                        UITraitCollection(preferredContentSizeCategory: large ? .accessibilityExtraExtraExtraLarge : .large)
                    ]),
                    forChild: controller
                )
                let window = show(host, size: CGSize(width: CGFloat(width), height: CGFloat(height)))
                try await Task.sleep(nanoseconds: 250_000_000)
                window.layoutIfNeeded()
                XCTAssertGreaterThan(controller.view.bounds.width, 0)
                saveSnapshot(window, name: "\(section)-\(width)-\(dark ? "dark" : "light")-\(large ? "AX" : "normal")")
                window.isHidden = true
            }
        }
    }

    func testFlipClockFirstFrameAndPhoneTabletLayout() throws {
        for width in [280, 393, 834, 1194] {
            let controller = UIViewController()
            let clock = FlipClockView(frame: CGRect(x: 0, y: 100, width: CGFloat(width), height: 180))
            clock.hour24 = true
            controller.view.addSubview(clock)
            let window = show(controller, size: CGSize(width: CGFloat(width), height: 834))
            defer { window.isHidden = true }
            clock.setDate(Date(timeIntervalSince1970: 1_700_000_000))
            clock.layoutIfNeeded()
            let stack = try XCTUnwrap(clock.subviews.first as? UIStackView)
            let row = try XCTUnwrap(stack.arrangedSubviews.first as? UIStackView)
            XCTAssertEqual(row.arrangedSubviews.count, 3)
            for card in row.arrangedSubviews {
                let rect = card.convert(card.bounds, to: clock)
                XCTAssertGreaterThan(card.bounds.width, 0)
                XCTAssertGreaterThanOrEqual(rect.minX, -0.5)
                XCTAssertLessThanOrEqual(rect.maxX, CGFloat(width) + 0.5)
                XCTAssertTrue(card.subviews.allSatisfy { $0.layer.animation(forKey: "flip") == nil })
            }
            saveSnapshot(window, name: "flip-clock-\(width)")
        }
    }

    func testFlipClockContinuousUpwardLeavesAndCancellation() throws {
        let controller = UIViewController()
        let clock = FlipClockView(frame: CGRect(x: 0, y: 100, width: 600, height: 180))
        clock.hour24 = true
        controller.view.addSubview(clock)
        let window = show(controller, size: CGSize(width: 834, height: 1194))
        defer { window.isHidden = true }
        let date = Date(timeIntervalSince1970: 1_700_000_039)
        clock.setDate(date)
        clock.setDate(date.addingTimeInterval(1))
        let stack = try XCTUnwrap(clock.subviews.first as? UIStackView)
        let row = try XCTUnwrap(stack.arrangedSubviews.first as? UIStackView)
        let card = try XCTUnwrap(row.arrangedSubviews.last)
        let leaves = card.subviews.filter { $0.layer.animation(forKey: "flip") != nil }
        XCTAssertEqual(leaves.count, 2)
        for (index, leaf) in leaves.enumerated() {
            let group = try XCTUnwrap(leaf.layer.animation(forKey: "flip") as? CAAnimationGroup)
            XCTAssertEqual(group.duration, 0.92, accuracy: 0.001)
            let rotation = try XCTUnwrap(group.animations?.first as? CAKeyframeAnimation)
            let values = try XCTUnwrap(rotation.values as? [NSValue])
            XCTAssertEqual(values.count, 121)
            XCTAssertEqual(values[60].caTransform3DValue.m22, 0, accuracy: 0.0001)
            XCTAssertGreaterThanOrEqual(values[30].caTransform3DValue.m22, -0.0001)
            XCTAssertGreaterThanOrEqual(values[90].caTransform3DValue.m22, -0.0001)
            XCTAssertEqual(leaf.layer.anchorPoint.y, index == 0 ? 0 : 1)
            if index == 0 {
                XCTAssertGreaterThan(values[30].caTransform3DValue.m23, 0)
            } else {
                XCTAssertLessThan(values[90].caTransform3DValue.m23, 0)
            }
        }
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertTrue(leaves.allSatisfy { $0.layer.animation(forKey: "flip") == nil && $0.layer.opacity == 0 })
        let labels = card.subviews.prefix(2).compactMap { $0.subviews.first as? UILabel }
        XCTAssertEqual(labels.count, 2)
        XCTAssertEqual(labels.first?.text, labels.last?.text)
        clock.setDate(date.addingTimeInterval(60))
        XCTAssertTrue(card.subviews.allSatisfy { $0.layer.animation(forKey: "flip") == nil })
    }

    func testFlipClockAnimationSettlesWithoutStaleDigits() async throws {
        let controller = UIViewController()
        let clock = FlipClockView(frame: CGRect(x: 0, y: 100, width: 600, height: 180))
        clock.hour24 = true
        controller.view.addSubview(clock)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        clock.frame.size.width = window.bounds.width
        window.layoutIfNeeded()
        defer { window.isHidden = true }
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        clock.setDate(date)
        clock.setDate(date.addingTimeInterval(1))
        let clockStack = try XCTUnwrap(clock.subviews.first as? UIStackView)
        let clockRow = try XCTUnwrap(clockStack.arrangedSubviews.first as? UIStackView)
        let seconds = try XCTUnwrap(clockRow.arrangedSubviews.last)
        try await Task.sleep(nanoseconds: 230_000_000)
        XCTAssertEqual(seconds.subviews.filter { $0.layer.animation(forKey: "flip") != nil }.count, 2)
        saveFlipPresentation(window, name: "flip-clock-lifting")
        try await Task.sleep(nanoseconds: 460_000_000)
        XCTAssertEqual(seconds.subviews.filter { $0.layer.animation(forKey: "flip") != nil }.count, 2)
        saveFlipPresentation(window, name: "flip-clock-unfolding")
        try await Task.sleep(nanoseconds: 510_000_000)
        let stack = try XCTUnwrap(clock.subviews.first as? UIStackView)
        let row = try XCTUnwrap(stack.arrangedSubviews.first as? UIStackView)
        for card in row.arrangedSubviews {
            XCTAssertTrue(card.subviews.allSatisfy { $0.layer.animation(forKey: "flip") == nil })
            let labels = card.subviews.prefix(2).compactMap { $0.subviews.first as? UILabel }
            XCTAssertEqual(labels.first?.text, labels.last?.text)
        }
    }

    private func saveFlipPresentation(_ window: UIWindow, name: String) {
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
            (window.layer.presentation() ?? window.layer).render(in: context.cgContext)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("PhotoPilot-UI-review")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? image.pngData()?.write(to: directory.appendingPathComponent(name + ".png"))
    }

    private func makeEnvironment() -> AppEnvironment {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return AppEnvironment(persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")))
    }

    private func show(_ controller: UIViewController, size: CGSize) -> UIWindow {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.frame = window.bounds
        window.layoutIfNeeded()
        controller.view.layoutIfNeeded()
        return window
    }

    private func saveSnapshot(_ window: UIWindow, name: String) {
        window.layoutIfNeeded()
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { context in
            window.layer.render(in: context.cgContext)
        }
        if let cgImage = image.cgImage, let bytes = cgImage.dataProvider?.data {
            let data = bytes as Data
            XCTAssertGreaterThan(Set(data).count, 8, "截图不得为空白帧")
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("PhotoPilot-UI-review")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? image.pngData()?.write(to: directory.appendingPathComponent(name + ".png"))
    }
}
