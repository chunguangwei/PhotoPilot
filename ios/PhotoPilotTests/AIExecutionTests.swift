import XCTest
@testable import PhotoPilot

@MainActor
final class AIExecutionTests: XCTestCase {
    func testWakeOwnersCannotReleaseAnotherTask() {
        ScreenWakeOwner.shared.set("ai-test", enabled: true)
        ScreenWakeOwner.shared.set("player-test", enabled: true)
        ScreenWakeOwner.shared.set("player-test", enabled: false)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
        ScreenWakeOwner.shared.set("ai-test", enabled: false)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
    }

    func testOldRunReleaseCannotEndNewPermitForSameFrame() {
        let runtime = AIExecutionRuntime.shared
        let first = runtime.begin("test-frame", userInitiated: false, expiration: { _ in })
        let second = runtime.begin("test-frame", userInitiated: false, expiration: { _ in })
        runtime.end("test-frame", token: first)
        XCTAssertTrue(UIApplication.shared.isIdleTimerDisabled)
        runtime.end("test-frame", token: second)
        XCTAssertFalse(UIApplication.shared.isIdleTimerDisabled)
    }

    func testSystemExpirationCallbackCanArriveOnBackgroundQueue() async {
        let result = AsyncStream<Bool>.makeStream()
        let handler = AIExecutionRuntime.expirationHandler {
            let onMain = await MainActor.run { Thread.isMainThread }
            result.continuation.yield(onMain)
        }
        await Task.detached { handler() }.value
        var iterator = result.stream.makeAsyncIterator()
        let onMain = await iterator.next()
        XCTAssertEqual(onMain, true)
        result.continuation.finish()
    }

    func testModelPreferencesFitSmallPhoneAtLargestTextInBothThemesAndLanguages() async throws {
        let oldLanguage = AppLanguage.overrideValue
        defer { AppLanguage.configure(value: oldLanguage) }
        for chinese in [false, true] {
            AppLanguage.configure(value: chinese ? "zh" : "en")
            for dark in [false, true] {
                let controller = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
                let page = AIModelsViewController(environment: AppEnvironment(persistence: controller))
                let navigation = UINavigationController(rootViewController: page)
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
                window.overrideUserInterfaceStyle = dark ? .dark : .light
                window.rootViewController = navigation
                navigation.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge), forChild: page)
                window.makeKeyAndVisible()
                try await Task.sleep(nanoseconds: 250_000_000)
                window.layoutIfNeeded()
                page.tableView.layoutIfNeeded()
                let cell = try XCTUnwrap(page.tableView.cellForRow(at: IndexPath(row: 0, section: 0)))
                let stack = try XCTUnwrap(cell.contentView.subviews.compactMap { $0 as? UIStackView }.first)
                let label = try XCTUnwrap(stack.arrangedSubviews.first as? UILabel)
                let segments = try XCTUnwrap(stack.arrangedSubviews.last as? UISegmentedControl)
                let rect = segments.convert(segments.bounds, to: cell.contentView)
                XCTAssertGreaterThanOrEqual(rect.minX, 0)
                XCTAssertLessThanOrEqual(rect.maxX, cell.contentView.bounds.width + 1)
                XCTAssertGreaterThanOrEqual(rect.height, 44)
                let required = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude))
                XCTAssertGreaterThanOrEqual(label.bounds.height + 1, required.height)
                let image = UIGraphicsImageRenderer(bounds: window.bounds).image { window.layer.render(in: $0.cgContext) }
                let attachment = XCTAttachment(image: image)
                attachment.name = "models-320-AX-" + (chinese ? "zh" : "en") + (dark ? "-dark" : "-light")
                attachment.lifetime = .keepAlways
                add(attachment)
                window.isHidden = true
            }
        }
    }

    func testPausedZeroPhotoFrameShowsItsStatusNearTopWithoutEmptyCover() async throws {
        let persistence = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
        let environment = AppEnvironment(persistence: persistence)
        let id = try await environment.frameManager.createFrame(name: "Paused frame", storageMode: .remote)
        _ = try await environment.aiFrames.store.configure(AIFrameConfiguration(frameID: id, modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cats")))
        try await environment.aiFrames.store.stop(frameID: id, status: "userPaused")
        let page = FramesListViewController(environment: environment)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = page
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 350_000_000)
        window.layoutIfNeeded()
        func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
        let views = descendants(page.view)
        let label = try XCTUnwrap(views.compactMap { $0 as? UILabel }.first { $0.text == "Paused frame" })
        XCTAssertLessThan(label.convert(label.bounds, to: window).minY, window.bounds.height / 2)
        XCTAssertFalse(views.contains { String(describing: type(of: $0)) == "FrameCoverView" })
        let menu = try XCTUnwrap(views.compactMap { $0 as? UIButton }.first { $0.accessibilityIdentifier == "frames.createOptions" })
        XCTAssertEqual(menu.menu?.children.count, 3)
    }

    func testRecoveryMatchesSharedVectors() throws {
        struct Example: Decodable { let id: String; let iosStatus: String; let automatic: Bool; let userPaused: Bool; let expectedResume: Bool }
        struct Root: Decodable { let cases: [Example] }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "ai-task-recovery", withExtension: "json"))
        for item in try JSONDecoder().decode(Root.self, from: Data(contentsOf: url)).cases {
            var configuration = AIFrameConfiguration(frameID: "frame", modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cat"))
            configuration.automatic = item.automatic
            var state = AIFrameState(configuration: configuration)
            state.status = item.iosStatus
            state.userPaused = item.userPaused
            XCTAssertEqual(state.shouldRunOnForeground, item.expectedResume, item.id)
        }
    }
}
