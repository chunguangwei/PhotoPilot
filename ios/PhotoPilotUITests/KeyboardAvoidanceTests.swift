import XCTest

final class KeyboardAvoidanceTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    func testNasFormPasswordFieldStaysAboveKeyboard() throws {
        sleep(2)
        let skipButton = app.buttons["launch.skip"]
        if skipButton.waitForExistence(timeout: 15) { skipButton.tap() }

        let choosePhotos = app.buttons["选择照片"]
        XCTAssertTrue(choosePhotos.waitForExistence(timeout: 30), "welcome")
        choosePhotos.tap()

        let nasRow = app.buttons["NAS 共享文件夹"].firstMatch
        XCTAssertTrue(nasRow.waitForExistence(timeout: 15), "nas row")
        nasRow.tap()

        let lanContinue = app.buttons["继续"].firstMatch
        if lanContinue.waitForExistence(timeout: 8) { lanContinue.tap() }

        let password = app.secureTextFields["密码"].firstMatch
        let passwordField = password.exists ? password : app.textFields["密码"].firstMatch
        XCTAssertTrue(passwordField.waitForExistence(timeout: 15), "password field")
        capture("kbd-01-nas-form")

        passwordField.tap()
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 10), "keyboard shown")
        sleep(2)
        capture("kbd-02-password-focused")

        let fieldBottom = passwordField.frame.maxY
        let keyboardTop = keyboard.frame.minY
        XCTAssertLessThanOrEqual(
            fieldBottom,
            keyboardTop + 1,
            "password field bottom \(fieldBottom) must be above keyboard top \(keyboardTop)"
        )

        let host = app.textFields["主机地址"].firstMatch
        if host.exists {
            XCTAssertLessThanOrEqual(host.frame.maxY, keyboardTop + 1, "host field visible above keyboard")
        }
    }

    private func capture(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let directory = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"],
              !directory.isEmpty else { return }
        let base = URL(fileURLWithPath: directory)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        try? screenshot.pngRepresentation.write(to: base.appendingPathComponent("\(name).png"))
    }
}
