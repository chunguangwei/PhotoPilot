import XCTest

final class ScreenshotTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en", "-uitest.reset"]
        app.launch()
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
        app = nil
    }

    func testLanguageSwitchPersistsAcrossSystemLanguageChange() throws {
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en", "-uitest.reset", "-uitest.onboarded"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()
        let chinese = app.buttons["简体中文"].firstMatch
        if !chinese.exists || !chinese.isHittable { app.buttons["Language"].firstMatch.tap() }
        XCTAssertTrue(chinese.waitForExistence(timeout: 5))
        chinese.tap()
        XCTAssertTrue(app.buttons["设置"].firstMatch.waitForExistence(timeout: 10))
        let english = app.buttons["English"].firstMatch
        if !english.exists || !english.isHittable { app.buttons["语言"].firstMatch.tap() }
        XCTAssertTrue(english.waitForExistence(timeout: 5))
        english.tap()
        XCTAssertTrue(app.buttons["Settings"].firstMatch.waitForExistence(timeout: 10))
        capture("audit-language-English")
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        XCTAssertTrue(app.buttons["Settings"].firstMatch.waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["设置"].exists)
        capture("audit-language-English-persisted")
    }

    func testPrivacyLicensesAndHolidayHours() throws {
        app.terminate()
        app.launchArguments += ["-uitest.onboarded"]
        app.launch()
        let skip = app.buttons["launch.skip"]
        if skip.waitForExistence(timeout: 15) { skip.tap() }
        let settings = app.buttons["Settings"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 20))
        settings.tap()
        let playback = app.buttons["Playback"].firstMatch
        XCTAssertTrue(playback.waitForExistence(timeout: 5))
        let holiday = app.buttons["settings.holiday.open"]
        if !holiday.isHittable { playback.tap() }
        for _ in 0..<6 where !holiday.isHittable { app.swipeUp() }
        XCTAssertTrue(holiday.isHittable)
        holiday.tap()
        let add = app.buttons["Add rule"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        let hours = app.switches["Daily hours (optional)"]
        for _ in 0..<6 where !hours.isHittable { app.swipeUp() }
        XCTAssertTrue(hours.isHittable)
        hours.tap()
        XCTAssertEqual(hours.value as? String, "1")
        XCTAssertTrue(app.staticTexts["Start time"].exists)
        capture("audit-holiday-hours-on")
        hours.tap()
        XCTAssertEqual(hours.value as? String, "0")
        XCTAssertFalse(app.staticTexts["Start time"].exists)
        app.buttons["Back"].firstMatch.tap()
        app.buttons["Back"].firstMatch.tap()
        let policy = app.buttons["Privacy Policy"]
        for _ in 0..<10 where !policy.isHittable { app.swipeUp() }
        XCTAssertTrue(policy.isHittable)
        let licenses = app.buttons["Open source licenses"]
        for _ in 0..<4 where !licenses.isHittable { app.swipeUp() }
        XCTAssertTrue(licenses.isHittable)
        capture("audit-privacy-entry")
        licenses.tap()
        let text = app.textViews.firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        XCTAssertTrue((text.value as? String)?.contains("GNU LESSER GENERAL PUBLIC LICENSE") == true)
        capture("audit-license-text")
    }

    func testCaptureSourcesLandscape() throws {
        app.terminate()
        let relaunched = XCUIApplication()
        relaunched.launchArguments += ["-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en", "-uitest.reset", "-uitest.onboarded"]
        relaunched.launch()
        app = relaunched
        let skipButton = app.buttons["launch.skip"]
        if skipButton.waitForExistence(timeout: 15) {
            skipButton.tap()
        }
        let sourcesTab = app.buttons["Sources"].firstMatch
        XCTAssertTrue(sourcesTab.waitForExistence(timeout: 30), "main container not reached")
        sourcesTab.tap()
        let addSource = app.staticTexts["Add a photo source"]
        XCTAssertTrue(addSource.waitForExistence(timeout: 15), "sources screen not reached")
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(3)
        capture("07-sources-landscape")
        XCUIDevice.shared.orientation = .portrait
        sleep(2)
        capture("08-sources-portrait")
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
