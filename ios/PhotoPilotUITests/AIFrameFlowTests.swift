import XCTest
import UIKit

@MainActor
final class AIFrameFlowTests: XCTestCase {
    func testEnglishAIFlow() throws { try runFlow(chinese: false) }
    func testChineseAIFlow() throws { try runFlow(chinese: true) }
    func testStandardModelEnglishAIFlow() throws { try runFlow(chinese: false, modelID: "embeddinggemma2-vision") }

    func testLargeTypeAIConfiguration() throws {
        guard ProcessInfo.processInfo.environment["PHOTOPILOT_AI_QA_TESTING"] == "1" else {
            throw XCTSkip("Requires the isolated AI QA fixture app.")
        }
        let app = XCUIApplication(bundleIdentifier: "dev.photopilot.aiqa")
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-dev.photopilot.appLanguage", "system", "-AppleLanguages", "(zh-Hans)",
                               "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let create = app.buttons["frames.createAI"]
        XCTAssertTrue(create.waitForExistence(timeout: 90))
        create.tap()
        XCTAssertTrue(app.cells["ai.model.multilingual-clip-vitb32-lite"].waitForExistence(timeout: 20))
        capture("08-large-type-models", chinese: true)
        let category = app.textViews["ai.categories"]
        scrollTo(category, app: app)
        XCTAssertTrue(category.exists)
        capture("09-large-type-rules", chinese: true)
        XCUIDevice.shared.orientation = .landscapeLeft
        capture("10-large-type-landscape", chinese: true)
        XCUIDevice.shared.orientation = .portrait
        settleOrientation(landscape: false)
    }

    func testLandscapeAIForms() throws {
        guard ProcessInfo.processInfo.environment["PHOTOPILOT_AI_QA_TESTING"] == "1" else {
            throw XCTSkip("Requires the isolated AI QA fixture app.")
        }
        for chinese in [false, true] {
            let app = XCUIApplication(bundleIdentifier: "dev.photopilot.aiqa")
            app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-dev.photopilot.appLanguage", "system", "-AppleLanguages", chinese ? "(zh-Hans)" : "(en)"]
            app.launch()
            let create = app.buttons["frames.createAI"]
            XCTAssertTrue(create.waitForExistence(timeout: 90))
            create.tap()
            XCTAssertTrue(app.cells["ai.model.multilingual-clip-vitb32-lite"].waitForExistence(timeout: 20))
            capture("11-normal-models", chinese: chinese)
            XCUIDevice.shared.orientation = .landscapeLeft
            capture("12-normal-landscape-models", chinese: chinese)
            XCUIDevice.shared.orientation = .portrait
            settleOrientation(landscape: false)
            if !chinese {
                let folder = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai.folder.")).firstMatch
                scrollTo(folder, app: app)
                XCTAssertTrue(folder.staticTexts["1 photo"].exists)
            }
            let category = app.textViews["ai.categories"]
            scrollTo(category, app: app)
            XCTAssertTrue(category.exists)
            capture("13-normal-rules", chinese: chinese)
        }
    }

    private func runFlow(chinese: Bool, modelID: String = "multilingual-clip-vitb32-lite") throws {
        guard ProcessInfo.processInfo.environment["PHOTOPILOT_AI_QA_TESTING"] == "1" else {
            throw XCTSkip("Requires the isolated dev.photopilot.aiqa app with authorized model and image fixtures.")
        }
        let app = XCUIApplication(bundleIdentifier: "dev.photopilot.aiqa")
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-dev.photopilot.appLanguage", "system", "-AppleLanguages", chinese ? "(zh-Hans)" : "(en)"]
        app.launch()
        let create = app.buttons["frames.createAI"]
        XCTAssertTrue(create.waitForExistence(timeout: 90))
        capture("01-frames", chinese: chinese)
        create.tap()
        let model = app.cells["ai.model." + modelID]
        XCTAssertTrue(model.waitForExistence(timeout: 20))
        model.tap()
        capture("02-models", chinese: chinese)
        let folder = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai.folder.")).firstMatch
        scrollTo(folder, app: app)
        XCTAssertTrue(folder.exists)
        folder.tap()
        let categories = app.textViews["ai.categories"]
        scrollTo(categories, app: app)
        categories.tap()
        categories.typeText(chinese ? "猫的照片\n宠物的照片" : "a photo of a cat\na photo of a pet")
        let done = app.toolbars.buttons[chinese ? "完成" : "Done"]
        if done.exists { done.tap() } else { app.swipeDown() }
        capture("03-rules", chinese: chinese)
        let scan = app.cells["ai.scan"]
        scrollTo(scan, app: app)
        let automatic = app.switches["ai.automatic"]
        XCTAssertEqual(automatic.value as? String, "0")
        scan.tap()
        let name = app.textFields["ai.name.0"]
        let deadline = Date().addingTimeInterval(240)
        while Date() < deadline && !name.exists {
            if app.alerts.firstMatch.exists {
                let description = app.alerts.firstMatch.staticTexts.allElementsBoundByIndex.map { $0.label }.joined(separator: " ")
                throw NSError(domain: "AIFrameUIRegression", code: 1, userInfo: [NSLocalizedDescriptionKey: description])
            }
            RunLoop.current.run(until: Date().addingTimeInterval(2))
            app.swipeUp()
        }
        XCTAssertTrue(name.exists)
        scrollTo(name, app: app)
        capture("04-preview", chinese: chinese)
        let match = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai.match.")).firstMatch
        XCTAssertTrue(match.exists, "The real image must produce a semantic match")
        let secondName = app.textFields["ai.name.1"]
        scrollTo(secondName, app: app)
        XCTAssertTrue(secondName.exists, "Both requested categories need previews")
        let save = app.navigationBars.buttons[chinese ? "保存" : "Save"]
        XCTAssertTrue(save.exists)
        save.tap()
        XCTAssertTrue(create.waitForExistence(timeout: 30))
        capture("05-saved", chinese: chinese)
        XCUIDevice.shared.orientation = .landscapeLeft
        capture("06-landscape", chinese: chinese)
        XCUIDevice.shared.orientation = .portrait
        settleOrientation(landscape: false)
        let actions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "frame.actions.")).firstMatch
        scrollTo(actions, app: app)
        XCTAssertTrue(actions.exists)
        actions.tap()
        app.sheets.buttons[chinese ? "编辑" : "Edit"].tap()
        let settings = app.buttons["frame.ai.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        scrollTo(automatic, app: app)
        XCTAssertEqual(automatic.value as? String, "0")
        automatic.tap()
        capture("07-edit-continuous", chinese: chinese)
        app.navigationBars.buttons[chinese ? "保存" : "Save"].tap()
    }

    private func scrollTo(_ element: XCUIElement, app: XCUIApplication) {
        for _ in 0..<10 {
            if element.exists && element.isHittable { return }
            app.swipeUp()
        }
    }

    private func settleOrientation(landscape: Bool) {
        let deadline = Date().addingTimeInterval(8)
        var stableSamples = 0
        while Date() < deadline {
            let size = XCUIScreen.main.screenshot().image.size
            if (size.width > size.height) == landscape { stableSamples += 1 }
            else { stableSamples = 0 }
            if stableSamples >= 2 { return }
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        }
        XCTFail("The UI did not settle into the requested orientation")
    }

    private func capture(_ name: String, chinese: Bool) {
        var captureName = name
        if name.contains("landscape") {
            let supportsLandscape = UIDevice.current.userInterfaceIdiom == .pad
            settleOrientation(landscape: supportsLandscape)
            if !supportsLandscape { captureName = name.replacingOccurrences(of: "landscape", with: "portrait-policy") }
        }
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = (chinese ? "zh-" : "en-") + captureName
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
