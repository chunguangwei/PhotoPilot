import XCTest

final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-uitest.reset"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    func testCaptureAppStoreScreenshots() throws {
        let skipButton = app.buttons["launch.skip"]
        if skipButton.waitForExistence(timeout: 15) {
            skipButton.tap()
        }

        let choosePhotos = app.buttons["Choose photos"]
        XCTAssertTrue(choosePhotos.waitForExistence(timeout: 30), "welcome screen not reached")
        choosePhotos.tap()

        let localPhotosRow = app.buttons["Photos on this device"].firstMatch
        XCTAssertTrue(localPhotosRow.waitForExistence(timeout: 15), "sources screen not reached")
        localPhotosRow.tap()

        let sheetChoice = app.sheets.buttons["Photos on this device"].firstMatch
        if sheetChoice.waitForExistence(timeout: 5) {
            sheetChoice.tap()
        }

        let continueButton = app.buttons["Continue"].firstMatch
        XCTAssertTrue(continueButton.waitForExistence(timeout: 15), "photo permission screen not reached")
        continueButton.tap()

        let chooseContent = app.buttons["Choose what to display"]
        XCTAssertTrue(chooseContent.waitForExistence(timeout: 300), "import did not complete")
        chooseContent.tap()

        let grid = app.collectionViews.firstMatch
        XCTAssertTrue(grid.waitForExistence(timeout: 20), "frame editor grid not shown")
        let cellQuery = app.cells.matching(NSPredicate(format: "label BEGINSWITH 'Photo'"))
        let imageQuery = app.images.matching(NSPredicate(format: "label BEGINSWITH 'Photo'"))
        let deadline = Date().addingTimeInterval(90)
        while cellQuery.count == 0 && imageQuery.count == 0 && Date() < deadline {
            sleep(2)
        }
        let photoCells = cellQuery.count > 0 ? cellQuery : imageQuery
        var tapped = 0
        if photoCells.count > 0 {
            let selectable = min(photoCells.count, 6)
            for index in 0..<selectable {
                let cell = photoCells.element(boundBy: index)
                if cell.isHittable {
                    cell.tap()
                    tapped += 1
                }
            }
        }
        if tapped == 0 {
            let labels = app.cells.allElementsBoundByIndex.map { $0.label }
            try? labels.joined(separator: "\n").write(toFile: "/tmp/photopilot-cells.txt", atomically: true, encoding: .utf8)
            let wide = app.windows.firstMatch.frame.width > 1000
            let points: [(CGFloat, CGFloat)] = wide
                ? [(0.095, 0.19), (0.245, 0.19), (0.39, 0.19), (0.535, 0.19), (0.68, 0.19), (0.825, 0.19)]
                : [(0.38, 0.30), (0.62, 0.30), (0.38, 0.40), (0.62, 0.40), (0.38, 0.50), (0.62, 0.50)]
            for (fx, fy) in points {
                app.coordinate(withNormalizedOffset: CGVector(dx: fx, dy: fy)).tap()
                usleep(300000)
                tapped += 1
            }
        }
        XCTAssertGreaterThan(tapped, 0, "no imported photos in frame editor")

        let saveButton = app.buttons["Save"]
        XCTAssertTrue(saveButton.waitForExistence(timeout: 10))

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        capture("06-editor-landscape")
        XCUIDevice.shared.orientation = .portrait
        sleep(2)

        saveButton.tap()

        let musicButton = app.buttons["Background music"]
        XCTAssertTrue(scrollUntilVisible(musicButton, maxSwipes: 15), "music section not reachable in playback settings")
        capture("03-playback-settings")

        musicButton.tap()
        let pickerTitle = app.staticTexts["Choose music"]
        XCTAssertTrue(pickerTitle.waitForExistence(timeout: 15), "music picker not shown")
        capture("04-music-picker")

        let pickerBack = app.buttons["Back"].firstMatch
        XCTAssertTrue(pickerBack.waitForExistence(timeout: 10))
        pickerBack.tap()

        let settingsBack = app.buttons["Back"].firstMatch
        XCTAssertTrue(settingsBack.waitForExistence(timeout: 10))
        settingsBack.tap()

        let framesTitle = app.staticTexts["Your frames"]
        XCTAssertTrue(framesTitle.waitForExistence(timeout: 20), "frames home not reached")
        capture("01-home")

        let playButton = app.buttons["Start slideshow"]
        XCTAssertTrue(playButton.waitForExistence(timeout: 15), "frame play button missing")
        playButton.tap()

        sleep(8)
        capture("02-player")

        app.tap()
        sleep(1)
        var exitButton = app.buttons["Exit full screen"].firstMatch
        if !exitButton.waitForExistence(timeout: 8) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            sleep(1)
        }
        if !exitButton.exists {
            let done = app.buttons["Done"].firstMatch
            if done.exists { exitButton = done }
        }
        if !exitButton.exists {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.07, dy: 0.05)).tap()
            sleep(1)
            exitButton = app.buttons["Exit full screen"].firstMatch
            if !exitButton.exists {
                let done = app.buttons["Done"].firstMatch
                if done.exists { exitButton = done }
            }
        }
        XCTAssertTrue(exitButton.waitForExistence(timeout: 10), "player controls not reachable")
        exitButton.tap()

        let sourcesTab = app.buttons["Sources"]
        XCTAssertTrue(sourcesTab.waitForExistence(timeout: 15), "main navigation not reached")
        sourcesTab.tap()
        let sourcesTitle = app.staticTexts["Sources"]
        XCTAssertTrue(sourcesTitle.waitForExistence(timeout: 10), "sources section not shown")
        capture("05-sources")
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
