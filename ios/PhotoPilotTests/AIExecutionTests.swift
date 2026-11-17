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

    func testSettingsAIModelsExpandInlineAndCollapseWithoutAnotherScrollView() async throws {
        let key = "settings.section.collapsed.settings.ai_models"
        let old = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.set(true, forKey: key)
        defer { UserDefaults.standard.set(old, forKey: key) }
        let persistence = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
        let environment = AppEnvironment(persistence: persistence)
        let catalog = try await environment.aiFrames.catalog()
        XCTAssertEqual(catalog.count, 2)
        let page = SettingsViewController(environment: environment)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = page
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
        let header = try XCTUnwrap(descendants(page.view).compactMap { $0 as? UIControl }.first { $0.accessibilityIdentifier == "settings.aiModels" })
        XCTAssertEqual(header.accessibilityValue, L10n.string("a11y.collapsed"))
        header.sendActions(for: .touchUpInside)
        let panel = try XCTUnwrap(page.children.compactMap { $0 as? AIModelsViewController }.first)
        let deadline = Date().addingTimeInterval(5)
        while !descendants(page.view).contains(where: { $0.accessibilityIdentifier == "models.item.embeddinggemma2-vision" }), Date() < deadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        window.layoutIfNeeded()
        XCTAssertTrue(panel.isPolling)
        XCTAssertNil(page.presentedViewController)
        XCTAssertEqual(header.accessibilityValue, L10n.string("a11y.expanded"))
        XCTAssertEqual(descendants(page.view).filter { $0 is UIScrollView }.count, 1)
        XCTAssertNotNil(descendants(page.view).first { $0.accessibilityIdentifier == "models.item.embeddinggemma2-vision" })
        header.sendActions(for: .touchUpInside)
        XCTAssertEqual(header.accessibilityValue, L10n.string("a11y.collapsed"))
        XCTAssertFalse(panel.isPolling)
        XCTAssertEqual(page.children.count, 1)
        try environment.settings.setValue("2", forKey: "ai.models.source")
        try environment.settings.setValue("true", forKey: "ai.models.cellular")
        header.sendActions(for: .touchUpInside)
        XCTAssertTrue(panel.isPolling)
        let source = try XCTUnwrap(descendants(page.view).compactMap { $0 as? UISegmentedControl }.first { $0.accessibilityIdentifier == "models.source" })
        let cellular = try XCTUnwrap(descendants(page.view).compactMap { $0 as? UISwitch }.first { $0.accessibilityIdentifier == "models.cellular" })
        XCTAssertEqual(source.selectedSegmentIndex, 2)
        XCTAssertTrue(cellular.isOn)
        page.beginAppearanceTransition(false, animated: false)
        page.endAppearanceTransition()
        XCTAssertFalse(panel.isPolling)
    }

    func testCollapsedModelPanelDoesNotPresentLateDownloadFailure() async throws {
        let persistence = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
        let page = SettingsViewController(environment: AppEnvironment(persistence: persistence))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = page
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let panel = try XCTUnwrap(page.children.compactMap { $0 as? AIModelsViewController }.first)
        panel.setActive(false)
        XCTAssertNotNil(panel.view.window)
        panel.show(AIFrameError.missingModel)
        try await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertNil(panel.presentedViewController)
        XCTAssertNil(page.presentedViewController)
        (panel.presentedViewController ?? page.presentedViewController)?.dismiss(animated: false)
    }

    func testFocusedSettingsExpandsPersistedCollapsedModelsAndStopsPollingOnClose() async throws {
        let key = "settings.section.collapsed.settings.ai_models"
        let old = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.set(true, forKey: key)
        defer { UserDefaults.standard.set(old, forKey: key) }
        let persistence = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
        let environment = AppEnvironment(persistence: persistence)
        let catalog = try await environment.aiFrames.catalog()
        XCTAssertEqual(catalog.count, 2)
        let page = SettingsViewController(environment: environment, focusAIModelID: "multilingual-clip-vitb32-lite")
        let navigation = UINavigationController(rootViewController: page)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = navigation
        window.makeKeyAndVisible()
        navigation.loadViewIfNeeded()
        window.layoutIfNeeded()
        navigation.view.layoutIfNeeded()
        XCTAssertNotNil(page.view.window)
        navigation.beginAppearanceTransition(true, animated: false)
        navigation.endAppearanceTransition()
        defer { window.isHidden = true }
        func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
        let panel = try XCTUnwrap(page.children.compactMap { $0 as? AIModelsViewController }.first)
        XCTAssertTrue(panel.isPolling)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: key))
        let deadline = Date().addingTimeInterval(5)
        while !descendants(page.view).contains(where: { $0.accessibilityIdentifier == "models.item.multilingual-clip-vitb32-lite" }), Date() < deadline {
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        window.layoutIfNeeded()
        let item = try XCTUnwrap(descendants(page.view).first { $0.accessibilityIdentifier == "models.item.multilingual-clip-vitb32-lite" })
        let rect = item.convert(item.bounds, to: window)
        XCTAssertGreaterThanOrEqual(rect.minY, window.safeAreaInsets.top)
        XCTAssertLessThan(rect.minY, window.bounds.height)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: key))
        XCTAssertNotNil(page.navigationItem.leftBarButtonItem)
        XCTAssertTrue(panel.isPolling)
        page.beginAppearanceTransition(false, animated: false)
        page.endAppearanceTransition()
        XCTAssertFalse(panel.isPolling)
    }

    func testEmbeddedModelPanelIsReleasedWithSettings() async throws {
        let persistence = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        var page: SettingsViewController? = SettingsViewController(environment: AppEnvironment(persistence: persistence), focusAIModelID: "embeddinggemma2-vision")
        window.rootViewController = page
        window.makeKeyAndVisible()
        page?.beginAppearanceTransition(true, animated: false)
        page?.endAppearanceTransition()
        weak var panel = page?.children.compactMap { $0 as? AIModelsViewController }.first
        XCTAssertNotNil(panel)
        page?.beginAppearanceTransition(false, animated: false)
        page?.endAppearanceTransition()
        XCTAssertEqual(panel?.isPolling, false)
        window.rootViewController = nil
        page = nil
        window.isHidden = true
        let deadline = Date().addingTimeInterval(2)
        while panel != nil, Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertNil(panel)
    }

    func testModelPreferencesFitSmallPhoneAtLargestTextInBothThemesAndLanguages() async throws {
        let oldLanguage = AppLanguage.overrideValue
        defer { AppLanguage.configure(value: oldLanguage) }
        for chinese in [false, true] {
            AppLanguage.configure(value: chinese ? "zh" : "en")
            for dark in [false, true] {
                let controller = PersistenceController(storeURL: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("library.db"))
                let page = SettingsViewController(environment: AppEnvironment(persistence: controller), focusAIModelID: "embeddinggemma2-vision")
                let navigation = UINavigationController(rootViewController: page)
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
                window.overrideUserInterfaceStyle = dark ? .dark : .light
                window.rootViewController = navigation
                navigation.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge), forChild: page)
                window.makeKeyAndVisible()
                window.layoutIfNeeded()
                navigation.view.layoutIfNeeded()
                navigation.beginAppearanceTransition(true, animated: false)
                navigation.endAppearanceTransition()
                func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
                let deadline = Date().addingTimeInterval(5)
                while !descendants(page.view).contains(where: { $0.accessibilityIdentifier == "models.item.multilingual-clip-vitb32-lite" }), Date() < deadline {
                    try await Task.sleep(nanoseconds: 50_000_000)
                }
                XCTAssertNotNil(descendants(page.view).first { $0.accessibilityIdentifier == "models.item.multilingual-clip-vitb32-lite" })
                window.layoutIfNeeded()
                let segments = try XCTUnwrap(descendants(page.view).compactMap { $0 as? UISegmentedControl }.first { $0.accessibilityIdentifier == "models.source" })
                let container = try XCTUnwrap(segments.superview)
                let rect = segments.convert(segments.bounds, to: container)
                XCTAssertGreaterThanOrEqual(rect.minX, 0)
                XCTAssertLessThanOrEqual(rect.maxX, container.bounds.width + 1)
                XCTAssertGreaterThanOrEqual(rect.height, 44)
                for label in descendants(container).compactMap({ $0 as? UILabel }) where !label.isHidden && label.bounds.width > 0 {
                    let required = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude))
                    XCTAssertGreaterThanOrEqual(label.bounds.height + 1, required.height, label.text ?? "")
                }
                let scroll = try XCTUnwrap(descendants(page.view).compactMap { $0 as? UIScrollView }.first)
                scroll.scrollRectToVisible(segments.convert(segments.bounds, to: scroll).insetBy(dx: 0, dy: -60), animated: false)
                window.layoutIfNeeded()
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
