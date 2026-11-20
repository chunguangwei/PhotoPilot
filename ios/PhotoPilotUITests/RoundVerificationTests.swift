import XCTest
import UIKit

@MainActor
final class RoundVerificationTests: XCTestCase {

    private func launchApp() -> XCUIApplication {
        let app = XCUIApplication(bundleIdentifier: "dev.photopilot.scaffold")
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-AppleLanguages", "(zh-Hans)"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 8) {
            app.buttons["launch.skip"].tap()
        }
        return app
    }

    private func capture(_ name: String, lifetime: XCTAttachment.Lifetime = .keepAlways) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = lifetime
        add(attachment)
    }

    func testFramesScreenRendersStably() async throws {
        let app = launchApp()
        let emptyCreate = app.buttons["frames.createAI"].firstMatch
        let optionsCreate = app.buttons["frames.createOptions"].firstMatch
        XCTAssertTrue(emptyCreate.waitForExistence(timeout: 90) || optionsCreate.waitForExistence(timeout: 60))
        try await Task.sleep(nanoseconds: 3_000_000_000)
        capture("round-verify-frames-top")

        let scroll = app.scrollViews.firstMatch
        XCTAssertTrue(scroll.exists)
        scroll.swipeUp()
        try await Task.sleep(nanoseconds: 2_000_000_000)
        capture("round-verify-frames-scrolled")

        let still = app.scrollViews.firstMatch
        XCTAssertTrue(still.exists)
    }

    func testAISettingsEntryDoesNotCrash() async throws {
        let app = launchApp()
        let actions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "frame.actions.")).firstMatch
        guard actions.waitForExistence(timeout: 60) else {
            throw XCTSkip("No existing frames on this device; skip AI-settings crash regression.")
        }
        actions.tap()

        let editZH = app.buttons["编辑"].firstMatch
        if editZH.waitForExistence(timeout: 5) {
            editZH.tap()
        } else {
            app.buttons["Edit"].firstMatch.tap()
        }

        let aiSettings = app.buttons["frame.ai.settings"].firstMatch
        XCTAssertTrue(aiSettings.waitForExistence(timeout: 30))
        aiSettings.tap()

        let saveZH = app.buttons["保存"].firstMatch
        let saveEN = app.buttons["Save"].firstMatch
        XCTAssertTrue(saveZH.waitForExistence(timeout: 20) || saveEN.exists)
        capture("round-verify-ai-settings")
        try await Task.sleep(nanoseconds: 3_000_000_000)
        XCTAssertTrue(saveZH.exists || saveEN.exists)
        capture("round-verify-ai-settings-after-monitor")
    }
}
