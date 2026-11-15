import XCTest
import UIKit

@MainActor
final class RemoteFrameFlowTests: XCTestCase {
    func testEnglishRemoteStorageAndSelection() throws { try run(chinese: false, large: false, dark: false) }
    func testChineseRemoteStorageAndSelection() throws { try run(chinese: true, large: false, dark: false) }
    func testChineseLargeDarkRemoteStorage() throws { try run(chinese: true, large: true, dark: true) }

    private func run(chinese: Bool, large: Bool, dark: Bool) throws {
        guard ProcessInfo.processInfo.environment["PHOTOPILOT_REMOTE_QA_TESTING"] == "1" else { throw XCTSkip("Requires isolated remote protocol fixture app") }
        let app = XCUIApplication(bundleIdentifier: "dev.photopilot.remoteqa")
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-AppleLanguages", chinese ? "(zh-Hans)" : "(en)", "-AppleInterfaceStyle", dark ? "Dark" : "Light"]
        if dark { app.launchArguments += ["-remoteqa.dark"] }
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        app.launch()
        let actions = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'frame.actions.'")).firstMatch
        XCTAssertTrue(actions.waitForExistence(timeout: 60))
        XCTAssertTrue(app.staticTexts[chinese ? "3 张照片" : "3 photos"].exists)
        actions.tap()
        let storage = app.buttons[chinese ? "照片存储" : "Photo storage"]
        XCTAssertTrue(storage.waitForExistence(timeout: 10))
        storage.tap()
        let toggle = app.switches["storage.syncLocal"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 15))
        XCTAssertEqual(toggle.value as? String, "0")
        capture("storage-online", chinese: chinese, large: large, dark: dark)
        toggle.tap()
        let ready = app.staticTexts[chinese ? "NAS 离线照片已就绪" : "NAS photos ready for offline playback"]
        XCTAssertTrue(ready.waitForExistence(timeout: 30))
        capture("storage-offline", chinese: chinese, large: large, dark: dark)
        toggle.tap()
        XCTAssertEqual(toggle.value as? String, "0")
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            let deadline = Date().addingTimeInterval(10)
            while Date() < deadline && app.frame.width <= app.frame.height { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
            XCTAssertGreaterThan(app.frame.width, app.frame.height)
            capture("storage-landscape", chinese: chinese, large: large, dark: dark)
        }
        app.buttons[chinese ? "完成" : "Done"].tap()
        XCUIDevice.shared.orientation = .portrait
        let online = app.buttons["frames.onlinePhotos"]
        XCTAssertTrue(online.waitForExistence(timeout: 15))
        online.tap()
        let source = app.cells.containing(.staticText, identifier: "Family NAS").firstMatch
        XCTAssertTrue(source.waitForExistence(timeout: 10))
        source.tap()
        let newFrame = app.buttons[chinese ? "新建相框" : "Create new frame"]
        XCTAssertTrue(newFrame.waitForExistence(timeout: 15))
        newFrame.tap()
        let name = app.textFields.firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 15))
        capture("online-selection", chinese: chinese, large: large, dark: dark)
        let photo = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'grid.photo.'")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 10))
        photo.tap()
        name.tap()
        name.typeText(chinese ? "远端相框" : "Online frame")
        let returnKey = app.keyboards.buttons[chinese ? "完成" : "Done"]
        if returnKey.exists { returnKey.tap() }
        let save = app.buttons[chinese ? "保存" : "Save"]
        XCTAssertTrue(save.isHittable)
        save.tap()
    }

    private func capture(_ name: String, chinese: Bool, large: Bool, dark: Bool) {
        var screenshot = XCUIScreen.main.screenshot()
        if name.contains("landscape") {
            let deadline = Date().addingTimeInterval(10)
            while Date() < deadline && screenshot.image.size.width <= screenshot.image.size.height {
                RunLoop.current.run(until: Date().addingTimeInterval(0.25))
                screenshot = XCUIScreen.main.screenshot()
            }
            XCTAssertGreaterThan(screenshot.image.size.width, screenshot.image.size.height)
        }
        let image = screenshot.image
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = true
        let normalized = UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: image.size))
        }
        if name.contains("landscape") {
            XCTAssertGreaterThan(normalized.cgImage?.width ?? 0, normalized.cgImage?.height ?? 0)
        }
        let attachment = XCTAttachment(image: normalized)
        attachment.name = (chinese ? "zh-" : "en-") + name + (large ? "-AX" : "") + (dark ? "-dark" : "")
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
