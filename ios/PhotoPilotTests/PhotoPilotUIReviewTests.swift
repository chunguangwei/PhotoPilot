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
