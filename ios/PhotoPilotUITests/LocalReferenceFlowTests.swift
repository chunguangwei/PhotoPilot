import XCTest

@MainActor
final class LocalReferenceFlowTests: XCTestCase {
    func testRealPhotoLibraryReferenceSelectionSavePlaybackAndRestart() throws {
        let simulator = ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? ""
        try XCTSkipUnless(["PhotoPilot Local References QA", "PhotoPilot Local References QA iPad"].contains(simulator), "Requires a dedicated simulator and the repository-only PhotoPilot Synthetic References album fixture")
        continueAfterFailure = false
        let monitor = addUIInterruptionMonitor(withDescription: "Synthetic Photos permission") { alert in
            let full = alert.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Full' OR label CONTAINS[c] 'All Photos' OR label CONTAINS '完整访问'")).firstMatch
            guard full.exists else { return false }
            full.tap()
            return true
        }
        defer { removeUIInterruptionMonitor(monitor) }
        let app = XCUIApplication()
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        let sources = app.buttons["Sources"].firstMatch
        XCTAssertTrue(sources.waitForExistence(timeout: 30))
        sources.tap()
        app.buttons["Photos on this device"].firstMatch.tap()
        let choice = app.sheets.buttons["Photos on this device"]
        XCTAssertTrue(choice.waitForExistence(timeout: 5))
        choice.tap()
        let next = app.buttons["Continue"].firstMatch
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        next.tap()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.alerts.firstMatch.waitForExistence(timeout: 4) {
            let full = springboard.alerts.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'Full' OR label CONTAINS[c] 'All Photos' OR label CONTAINS '完整访问'")).firstMatch
            XCTAssertTrue(full.exists, springboard.alerts.firstMatch.debugDescription)
            full.tap()
            XCTAssertTrue(next.waitForExistence(timeout: 5))
            next.tap()
        }
        let albums = app.navigationBars["Choose albums"]
        XCTAssertTrue(albums.waitForExistence(timeout: 15))
        let synthetic = app.tables.cells.containing(.staticText, identifier: "PhotoPilot Synthetic References").firstMatch
        try XCTSkipUnless(synthetic.waitForExistence(timeout: 5), "Prepare the repository-only PhotoPilot Synthetic References album before this UI test")
        synthetic.tap()
        capture("real-photokit-albums")
        albums.buttons["Continue"].tap()
        let photo = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'grid.photo.remote:'")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 30))
        capture("real-photokit-second-selection")
        photo.tap()
        let name = app.textFields.firstMatch
        name.tap()
        name.typeText("Reference QA")
        if app.keyboards.buttons["Done"].exists { app.keyboards.buttons["Done"].tap() }
        let save = app.buttons["Save"].firstMatch
        XCTAssertTrue(save.isHittable)
        save.tap()
        let back = app.buttons["Back"].firstMatch
        if back.waitForExistence(timeout: 10) { back.tap() }
        let play = app.buttons["Start slideshow"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        play.tap()
        let current = app.images["Current photo, 1 of 1"]
        XCTAssertTrue(current.waitForExistence(timeout: 15))
        capture("real-photokit-playback")
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        if play.waitForExistence(timeout: 8) { play.tap() }
        XCTAssertTrue(current.waitForExistence(timeout: 15))
        capture("real-photokit-restart-playback")
    }

    func testRealExternalFilesPickerReference() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] == "PhotoPilot External Files QA", "Requires the dedicated synthetic external document fixture")
        continueAfterFailure = false
        let provider = XCUIApplication(bundleIdentifier: "dev.photopilot.qa.reference-documents")
        provider.launchArguments = ["-restore"]
        provider.launch()
        XCTAssertTrue(provider.buttons["Restore synthetic files"].waitForExistence(timeout: 10))
        provider.terminate()
        let systemFiles = XCUIApplication(bundleIdentifier: "com.apple.DocumentsApp")
        systemFiles.launchArguments = ["-AppleLanguages", "(en)"]
        systemFiles.launch()
        XCTAssertTrue(systemFiles.buttons["Browse"].waitForExistence(timeout: 20))
        systemFiles.buttons["Browse"].tap()
        let systemPhone = systemFiles.staticTexts["On My iPhone"]
        XCTAssertTrue(systemPhone.waitForExistence(timeout: 10))
        systemPhone.tap()
        let systemContainer = systemFiles.cells["Reference Documents, Container"]
        XCTAssertTrue(systemContainer.waitForExistence(timeout: 10))
        systemContainer.tap()
        let systemFolder = systemFiles.staticTexts["SeedAlbum"]
        let systemSourceReady = systemFolder.waitForExistence(timeout: 10)
        capture("external-files-standalone-container")
        print("EXTERNAL_FILES_STANDALONE", systemFiles.debugDescription)
        systemFiles.terminate()
        try XCTSkipUnless(systemSourceReady, "The simulator Files app cannot enter its visible external shared container. DocumentManager reports Cannot find source for location. The external-provider flow remains unverified.")
        let app = XCUIApplication()
        app.launchArguments = ["-uitest.reset", "-uitest.onboarded", "-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        let sources = app.buttons["Sources"].firstMatch
        XCTAssertTrue(sources.waitForExistence(timeout: 30))
        sources.tap()
        app.buttons["Photos on this device"].firstMatch.tap()
        let files = app.sheets.buttons["Choose photo files"]
        XCTAssertTrue(files.waitForExistence(timeout: 5), app.debugDescription)
        files.tap()
        XCTAssertTrue(app.buttons["Browse"].waitForExistence(timeout: 30), app.debugDescription)
        app.buttons["Browse"].tap()
        let phone = app.staticTexts["On My iPhone"]
        XCTAssertTrue(phone.waitForExistence(timeout: 10), app.debugDescription)
        phone.tap()
        let fixture = app.staticTexts["Reference Documents"]
        XCTAssertTrue(fixture.waitForExistence(timeout: 10), app.debugDescription)
        capture("external-files-container")
        app.cells["Reference Documents, Container"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
        sleep(2)
        capture("external-files-container-open")
        let folder = app.staticTexts["SeedAlbum"]
        XCTAssertTrue(folder.waitForExistence(timeout: 10), app.debugDescription)
        app.cells.containing(.staticText, identifier: "SeedAlbum").firstMatch.doubleTap()
        capture("external-files-system-picker")
        let seed = app.cells.containing(.staticText, identifier: "seed-01").firstMatch
        XCTAssertTrue(seed.waitForExistence(timeout: 10), app.debugDescription)
        seed.tap()
        let open = app.buttons["Open"].firstMatch
        if open.waitForExistence(timeout: 3) { open.tap() }
        let photo = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'grid.photo.remote:'")).firstMatch
        XCTAssertTrue(photo.waitForExistence(timeout: 30), app.debugDescription)
        capture("external-files-second-selection")
        photo.tap()
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText("External file QA")
        if app.keyboards.buttons["Done"].exists { app.keyboards.buttons["Done"].tap() }
        app.buttons["Save"].firstMatch.tap()
        if app.buttons["Back"].firstMatch.waitForExistence(timeout: 10) { app.buttons["Back"].firstMatch.tap() }
        let play = app.buttons["Start slideshow"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        play.tap()
        let current = app.images["Current photo, 1 of 1"]
        XCTAssertTrue(current.waitForExistence(timeout: 15))
        capture("external-files-playback")
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(en)", "-dev.photopilot.appLanguage", "en"]
        app.launch()
        if app.buttons["launch.skip"].waitForExistence(timeout: 15) { app.buttons["launch.skip"].tap() }
        if play.waitForExistence(timeout: 8) { play.tap() }
        XCTAssertTrue(current.waitForExistence(timeout: 15))
        capture("external-files-restart-playback")
        provider.launchArguments = []
        provider.launch()
        provider.buttons["Remove synthetic files"].tap()
        app.activate()
        let absent = NSPredicate(format: "exists == false")
        expectation(for: absent, evaluatedWith: current)
        waitForExpectations(timeout: 20)
        capture("external-files-original-removed")
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
