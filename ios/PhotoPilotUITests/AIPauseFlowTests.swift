import XCTest
import UIKit

@MainActor
final class AIPauseFlowTests: XCTestCase {
    func testEnglishPartialSaveAndContinue() throws { try partialFlow(chinese: false) }
    func testChinesePartialSaveAndContinue() throws { try partialFlow(chinese: true) }
    func testBackgroundScanAndPausedDraftRecovery() throws { try partialFlow(chinese: false, background: true) }

    func testMissingModelFocusPreservesIncompleteFormAcrossRelaunch() throws {
        let app = try launch(chinese: false)
        openAI(app)
        XCTAssertTrue(app.cells["ai.model.embeddinggemma2-vision"].waitForExistence(timeout: 20))
        let category = app.textViews["ai.categories"]
        scroll(category, app)
        category.tap()
        category.typeText("draft before downloading")
        let keyboardDone = app.toolbars.buttons["Done"].exists ? app.toolbars.buttons["Done"] : app.toolbars.buttons["完成"]
        keyboardDone.tap()
        scroll(app.cells["ai.manageModels"], app)
        app.cells["ai.manageModels"].tap()
        let focused = app.staticTexts["models.item.embeddinggemma2-vision"]
        XCTAssertTrue(focused.waitForExistence(timeout: 15))
        XCTAssertTrue(focused.isHittable)
        capture("en-focused-model")
        app.navigationBars.buttons["settings.aiModels.back"].tap()
        scroll(category, app)
        XCTAssertEqual(category.value as? String, "draft before downloading")
        let folder = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai.folder.")).firstMatch
        scroll(folder, app)
        folder.tap()
        let scan = app.cells["ai.scan"]
        scroll(scan, app)
        scan.tap()
        XCTAssertTrue(focused.waitForExistence(timeout: 20))
        XCTAssertTrue(focused.isHittable)
        capture("en-missing-model-scan-route")
        app.navigationBars.buttons["settings.aiModels.back"].tap()
        app.terminate()
        app.launchArguments.removeAll { $0 == "-uitest.reset" }
        app.launch()
        skipLaunch(app)
        XCTAssertTrue(app.buttons["frames.createOptions"].waitForExistence(timeout: 60) || app.buttons["frames.createAI"].exists)
        openAI(app)
        XCTAssertTrue(app.cells["ai.model.embeddinggemma2-vision"].waitForExistence(timeout: 20))
        scroll(category, app)
        XCTAssertEqual(category.value as? String, "draft before downloading")
    }

    func testCentralModelManagementPersistsDownloadSource() throws {
        let app = try launch(chinese: false)
        app.buttons["Settings"].firstMatch.tap()
        let entry = app.buttons["settings.aiModels"]
        scroll(entry, app)
        if entry.value as? String == "Expanded" { entry.tap() }
        entry.tap()
        let source = app.segmentedControls["models.source"]
        XCTAssertTrue(source.waitForExistence(timeout: 20))
        XCTAssertFalse(app.navigationBars.buttons["Done"].exists)
        let global = source.buttons["Global"]
        scroll(global, app)
        global.tap()
        capture("en-inline-model-preferences")
        scroll(entry, app)
        entry.tap()
        XCTAssertFalse(source.isHittable)
        capture("en-models-collapsed")
        entry.tap()
        scroll(source, app)
        XCTAssertTrue(source.buttons["Global"].isSelected)
        let status = app.staticTexts["models.item.multilingual-clip-vitb32-lite"]
        scroll(status, app)
        XCTAssertTrue(status.isHittable)
        XCTAssertFalse(app.buttons["models.action.multilingual-clip-vitb32-lite"].exists)
        capture("en-inline-ready-model")
    }

    func testModelAndDraftLayoutsAtLargestTextAndLandscape() throws {
        for chinese in [false, true] {
            let app = try launch(chinese: chinese, large: true, dark: chinese)
            app.buttons[chinese ? "设置" : "Settings"].firstMatch.tap()
            let entry = app.buttons["settings.aiModels"]
            scroll(entry, app)
            if entry.value as? String == (chinese ? "已收起" : "Collapsed") { entry.tap() }
            let source = app.segmentedControls["models.source"]
            XCTAssertTrue(source.waitForExistence(timeout: 20))
            scroll(source, app)
            XCUIDevice.shared.orientation = .landscapeLeft
            let landscape = UIDevice.current.userInterfaceIdiom == .pad
            let deadline = Date().addingTimeInterval(8)
            while Date() < deadline {
                let size = XCUIScreen.main.screenshot().image.size
                if (size.width > size.height) == landscape { break }
                RunLoop.current.run(until: Date().addingTimeInterval(0.2))
            }
            let size = XCUIScreen.main.screenshot().image.size
            XCTAssertEqual(size.width > size.height, landscape)
            capture(chinese ? "zh-models-AX-dark" : "en-models-AX-light")
            let status = app.staticTexts["models.item.multilingual-clip-vitb32-lite"]
            scroll(status, app)
            XCTAssertTrue(status.isHittable)
            capture(chinese ? "zh-inline-model-AX-dark" : "en-inline-model-AX-light")
            app.buttons[chinese ? "相框" : "Frames"].firstMatch.tap()
            openAI(app)
            XCTAssertTrue(app.cells["ai.model.embeddinggemma2-vision"].waitForExistence(timeout: 20))
            scroll(app.textViews["ai.categories"], app)
            capture(chinese ? "zh-draft-AX-dark" : "en-draft-AX-light")
            XCUIDevice.shared.orientation = .portrait
        }
    }

    private func partialFlow(chinese: Bool, background: Bool = false) throws {
        let app = try launch(chinese: chinese)
        openAI(app)
        let model = app.cells["ai.model.multilingual-clip-vitb32-lite"]
        XCTAssertTrue(model.waitForExistence(timeout: 20))
        model.tap()
        let folder = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH %@", "ai.folder.")).firstMatch
        scroll(folder, app)
        folder.tap()
        let category = app.textViews["ai.categories"]
        scroll(category, app)
        category.tap()
        category.typeText(chinese ? "彩色几何图案\n抽象艺术" : "colorful geometric shapes\nabstract art")
        app.toolbars.buttons[chinese ? "完成" : "Done"].tap()
        let filters = app.cells["ai.filters"]
        scroll(filters, app)
        filters.tap()
        scroll(app.sliders["ai.threshold"], app)
        app.sliders["ai.threshold"].adjust(toNormalizedSliderPosition: 0)
        let scan = app.cells["ai.scan"]
        scroll(scan, app)
        app.switches["ai.automatic"].tap()
        scan.tap()
        let pause = app.staticTexts[chinese ? "暂停并查看结果" : "Pause and view results"]
        XCTAssertTrue(pause.waitForExistence(timeout: 20))
        let progress = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", chinese ? "分类进度" : "Classification progress")).firstMatch
        let deadline = Date().addingTimeInterval(90)
        var processed = false
        while Date() < deadline {
            if progress.exists, progress.label.range(of: "[1-9][0-9]*/", options: .regularExpression) != nil { processed = true; break }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        XCTAssertTrue(processed)
        if background {
            let before = progress.label
            XCUIDevice.shared.press(.home)
            RunLoop.current.run(until: Date().addingTimeInterval(5))
            app.activate()
            scroll(scan, app)
            print("AI_BACKGROUND_PROGRESS before=\(before) after=\(progress.exists ? progress.label : "checkpoint retained")")
            capture("en-returned-from-background")
        }
        if pause.exists { scan.tap() }
        let resume = app.staticTexts[chinese ? "继续扫描" : "Continue scanning"]
        XCTAssertTrue(resume.waitForExistence(timeout: 60))
        capture(chinese ? "zh-paused-partial" : "en-paused-partial")
        let name0 = app.textFields["ai.name.0"]
        scroll(name0, app)
        XCTAssertTrue(name0.exists)
        let name1 = app.textFields["ai.name.1"]
        scroll(name1, app)
        XCTAssertTrue(name1.exists)
        capture(chinese ? "zh-category-progress" : "en-category-progress")
        app.navigationBars.buttons[chinese ? "保存" : "Save"].tap()
        let frameName = chinese ? "抽象艺术" : "abstract art"
        XCTAssertTrue(app.staticTexts[frameName].firstMatch.waitForExistence(timeout: 30))
        capture(chinese ? "zh-paused-frames" : "en-paused-frames")
        app.terminate()
        app.launchArguments.removeAll { $0 == "-uitest.reset" }
        app.launch()
        skipLaunch(app)
        XCTAssertTrue(app.staticTexts[frameName].firstMatch.waitForExistence(timeout: 60))
        let paused = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", chinese ? "已暂停" : "Paused")).firstMatch
        XCTAssertTrue(paused.exists)
        app.staticTexts[frameName].firstMatch.tap()
        let settings = app.buttons["frame.ai.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        scroll(scan, app)
        scan.tap()
        XCTAssertTrue(pause.waitForExistence(timeout: 20))
        capture(chinese ? "zh-continued-existing" : "en-continued-existing")
        scan.tap()
        XCTAssertTrue(resume.waitForExistence(timeout: 60))
    }

    private func launch(chinese: Bool, large: Bool = false, dark: Bool = false) throws -> XCUIApplication {
        guard ProcessInfo.processInfo.environment["PHOTOPILOT_AI_WORK_QA_TESTING"] == "1" else {
            throw XCTSkip("Requires the isolated AI work QA app and its synthetic NAS fixture.")
        }
        let app = XCUIApplication(bundleIdentifier: "dev.photopilot.aiworkqa")
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-dev.photopilot.appLanguage", "system", "-AppleLanguages", chinese ? "(zh-Hans)" : "(en)"]
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        if dark { app.launchArguments.append("-remoteqa.dark") }
        app.launch()
        skipLaunch(app)
        XCTAssertTrue(app.buttons["frames.createOptions"].waitForExistence(timeout: 90) || app.buttons["frames.createAI"].exists)
        return app
    }

    private func openAI(_ app: XCUIApplication) {
        if app.buttons["frames.createAI"].exists { app.buttons["frames.createAI"].tap(); return }
        app.buttons["frames.createOptions"].tap()
        let english = app.buttons["Create AI frames"]
        let chinese = app.buttons["AI 创建相框"]
        if english.exists { english.tap() } else { chinese.tap() }
    }

    private func skipLaunch(_ app: XCUIApplication) {
        if app.buttons["launch.skip"].waitForExistence(timeout: 10) { app.buttons["launch.skip"].tap() }
    }
    private func scroll(_ element: XCUIElement, _ app: XCUIApplication) {
        for direction in [true, false] {
            for _ in 0..<12 {
                if element.exists {
                    let frame = element.frame
                    if !frame.isEmpty && app.frame.insetBy(dx: 0, dy: 110).contains(CGPoint(x: frame.midX, y: frame.midY)) { return }
                }
                if direction { app.swipeUp() } else { app.swipeDown() }
            }
        }
    }
    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
