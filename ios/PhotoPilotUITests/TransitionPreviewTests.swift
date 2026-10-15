import XCTest

final class TransitionPreviewTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)"]
        app.launch()
    }

    func testTransitionPreviewWidths() throws {
        let skipButton = app.buttons["launch.skip"]
        if skipButton.waitForExistence(timeout: 15) {
            skipButton.tap()
        }
        let choosePhotos = app.buttons["选择照片"]
        XCTAssertTrue(choosePhotos.waitForExistence(timeout: 30))
        choosePhotos.tap()
        let localRow = app.buttons["本机照片"].firstMatch
        XCTAssertTrue(localRow.waitForExistence(timeout: 15))
        localRow.tap()
        let sheetChoice = app.sheets.buttons["本机照片"].firstMatch
        if sheetChoice.waitForExistence(timeout: 5) {
            sheetChoice.tap()
        }
        let continueButton = app.buttons["继续"].firstMatch
        if continueButton.waitForExistence(timeout: 15) {
            continueButton.tap()
        }
        let chooseContent = app.buttons["选择展示内容"]
        XCTAssertTrue(chooseContent.waitForExistence(timeout: 300))
        chooseContent.tap()
        let grid = app.collectionViews.firstMatch
        XCTAssertTrue(grid.waitForExistence(timeout: 20))
        let photoCells = app.cells.matching(NSPredicate(format: "label BEGINSWITH '照片'"))
        let deadline = Date().addingTimeInterval(90)
        while photoCells.count == 0 && Date() < deadline {
            sleep(2)
        }
        var tapped = 0
        if photoCells.count > 0 {
            for index in 0..<min(photoCells.count, 2) {
                let cell = photoCells.element(boundBy: index)
                if cell.isHittable {
                    cell.tap()
                    tapped += 1
                }
            }
        }
        if tapped == 0 {
            for (fx, fy) in [(0.095, 0.19), (0.245, 0.19)] as [(CGFloat, CGFloat)] {
                app.coordinate(withNormalizedOffset: CGVector(dx: fx, dy: fy)).tap()
                usleep(300000)
            }
        }
        let saveButton = app.buttons["保存"]
        XCTAssertTrue(saveButton.waitForExistence(timeout: 10))
        saveButton.tap()

        let transitionButton = app.buttons["转场"]
        XCTAssertTrue(scrollUntilVisible(transitionButton, maxSwipes: 12), "transition button not reachable")
        capture("t-00-before")

        let titles = ["无转场", "随机", "淡入淡出", "轻推移", "擦除", "缩放淡入", "涟漪"]
        for (index, title) in titles.enumerated() {
            if !(transitionButton.exists && transitionButton.isHittable) {
                _ = scrollUntilVisible(transitionButton, maxSwipes: 8)
            }
            transitionButton.tap()
            sleep(1)
            let menuOption = app.buttons[title].firstMatch
            if menuOption.waitForExistence(timeout: 5) {
                menuOption.tap()
            } else {
                let menuItem = app.menuItems[title].firstMatch
                if menuItem.waitForExistence(timeout: 3) {
                    menuItem.tap()
                }
            }
            usleep(700000)
            capture(String(format: "t-%02d-%@", index + 1, title))
            sleep(2)
            for _ in 0..<6 {
                app.swipeDown()
            }
            usleep(500000)
            capture(String(format: "t-%02d-%@-settled", index + 1, title))
            _ = scrollUntilVisible(transitionButton, maxSwipes: 10)
        }
    }

    private func scrollUntilVisible(_ element: XCUIElement, maxSwipes: Int) -> Bool {
        for _ in 0..<maxSwipes {
            if element.exists && element.isHittable {
                return true
            }
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    private func capture(_ name: String) {
        let screenshot = XCUIScreen.main.screenshot()
        guard let directory = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"],
              !directory.isEmpty else { return }
        let base = URL(fileURLWithPath: directory)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        try? screenshot.pngRepresentation.write(to: base.appendingPathComponent("\(name).png"))
    }
}
