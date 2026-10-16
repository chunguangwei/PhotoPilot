import XCTest

final class WalkthroughTests: XCTestCase {
    private var app: XCUIApplication!
    private var missing: [String] = []

    override func setUpWithError() throws {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(zh-Hans)"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app = nil
    }

    func testFullFeatureWalkthrough() throws {
        sleep(2)
        capture("w-01-launch")
        let skipButton = app.buttons["launch.skip"]
        if skipButton.waitForExistence(timeout: 15) {
            skipButton.tap()
        }

        let welcomeTitle = app.staticTexts["让回忆，留在眼前。"]
        expect(welcomeTitle.waitForExistence(timeout: 30), "w-02 welcome")
        capture("w-02-welcome")

        let demoButton = app.buttons["先看看"]
        if demoButton.waitForExistence(timeout: 5) {
            demoButton.tap()
            sleep(4)
            capture("w-03-demo")
            let demoExit = app.buttons["退出示例"]
            if demoExit.waitForExistence(timeout: 8) {
                demoExit.tap()
            } else {
                app.tap()
                sleep(1)
                if demoExit.waitForExistence(timeout: 5) { demoExit.tap() }
            }
            _ = welcomeTitle.waitForExistence(timeout: 10)
        }

        let choosePhotos = app.buttons["选择照片"]
        if choosePhotos.waitForExistence(timeout: 15) {
            choosePhotos.tap()
        } else {
            missing.append("welcome choose photos")
        }

        let localRow = app.buttons["本机照片"].firstMatch
        if localRow.waitForExistence(timeout: 15) {
            capture("w-04-sources-chooser")
            localRow.tap()
        } else {
            missing.append("w-04 sources chooser")
        }
        let sheetChoice = app.sheets.buttons["本机照片"].firstMatch
        if sheetChoice.waitForExistence(timeout: 5) {
            capture("w-05-source-kind-sheet")
            sheetChoice.tap()
        }

        let continueButton = app.buttons["继续"].firstMatch
        if continueButton.waitForExistence(timeout: 15) {
            capture("w-06-permission")
            continueButton.tap()
        }

        let chooseContent = app.buttons["选择展示内容"]
        if chooseContent.waitForExistence(timeout: 20) {
            capture("w-07-import-progress")
        }
        if chooseContent.waitForExistence(timeout: 300) {
            capture("w-08-import-done")
            chooseContent.tap()
        } else {
            missing.append("import completion")
        }

        let grid = app.collectionViews.firstMatch
        _ = grid.waitForExistence(timeout: 20)
        let cellQuery = app.cells.matching(NSPredicate(format: "label BEGINSWITH '照片'"))
        let imageQuery = app.images.matching(NSPredicate(format: "label BEGINSWITH '照片'"))
        let pollDeadline = Date().addingTimeInterval(90)
        while cellQuery.count == 0 && imageQuery.count == 0 && Date() < pollDeadline {
            sleep(2)
        }
        capture("w-09-editor-photos")
        let photoCells = cellQuery.count > 0 ? cellQuery : imageQuery
        var tapped = 0
        if photoCells.count > 0 {
            for index in 0..<min(photoCells.count, 4) {
                let cell = photoCells.element(boundBy: index)
                if cell.isHittable {
                    cell.tap()
                    tapped += 1
                }
            }
        }
        if tapped == 0 {
            let wide = app.windows.firstMatch.frame.width > 1000
            let points: [(CGFloat, CGFloat)] = wide
                ? [(0.095, 0.19), (0.245, 0.19), (0.39, 0.19), (0.535, 0.19)]
                : [(0.38, 0.30), (0.62, 0.30), (0.38, 0.40), (0.62, 0.40)]
            for (fx, fy) in points {
                app.coordinate(withNormalizedOffset: CGVector(dx: fx, dy: fy)).tap()
                usleep(300000)
                tapped += 1
            }
        }
        capture("w-10-editor-selected")

        let star = app.buttons["设为封面"].firstMatch
        if star.waitForExistence(timeout: 8) {
            star.tap()
            sleep(1)
            capture("w-11-cover-preview")
            let confirm = app.buttons["设为封面"].firstMatch
            if confirm.waitForExistence(timeout: 5) {
                confirm.tap()
                sleep(1)
                capture("w-12-cover-set")
            }
        } else {
            missing.append("cover star")
        }

        let foldersTab = app.buttons["目录"]
        if foldersTab.waitForExistence(timeout: 5) {
            foldersTab.tap()
            sleep(1)
            capture("w-13-editor-folders")
            let photosTab = app.buttons["照片"]
            if photosTab.waitForExistence(timeout: 5) { photosTab.tap() }
        }

        let nameField = app.textFields["相框名称"]
        if nameField.waitForExistence(timeout: 5) {
            nameField.tap()
            nameField.typeText("终审相框")
            dismissKeyboard()
        }

        let saveButton = app.buttons["保存"]
        if saveButton.waitForExistence(timeout: 10) {
            capture("w-13b-editor-named")
            saveButton.tap()
        } else {
            missing.append("editor save")
        }

        let musicRow = app.buttons["背景音乐"]
        let musicReachable = scrollUntilVisible(musicRow, maxSwipes: 15)
        if !musicReachable { missing.append("playback settings music row") }
        capture("w-14-playback-settings-top")
        app.swipeUp()
        capture("w-15-playback-settings-mid")

        if musicReachable && musicRow.exists && musicRow.isHittable {
            musicRow.tap()
        }
        let pickerTitle = app.staticTexts["选择音乐"]
        if !pickerTitle.waitForExistence(timeout: 15) { missing.append("music picker") }
        capture("w-16-music-picker")
        let birthdayTrack = app.staticTexts["生日祝福"]
        if birthdayTrack.waitForExistence(timeout: 8) {
            birthdayTrack.tap()
            sleep(1)
            capture("w-17-music-selected")
        }
        let pickerBack = app.buttons["返回"].firstMatch
        if pickerBack.waitForExistence(timeout: 10) { pickerBack.tap() }

        let manageRow = app.buttons["管理照片"]
        if scrollUntilVisible(manageRow, maxSwipes: 15) {
            manageRow.tap()
            sleep(2)
            capture("w-18-manage-photos")
            let manageBack = app.buttons["返回"].firstMatch
            if manageBack.waitForExistence(timeout: 10) { manageBack.tap() }
        }

        let settingsBack = app.buttons["返回"].firstMatch
        if settingsBack.waitForExistence(timeout: 10) { settingsBack.tap() }

        let framesTitle = app.staticTexts["相框"].firstMatch
        _ = framesTitle.waitForExistence(timeout: 15)
        capture("w-19-frames-home")

        let editButton = app.buttons["编辑"].firstMatch
        if editButton.waitForExistence(timeout: 8) {
            editButton.tap()
            sleep(2)
            capture("w-20-frame-edit-existing")
            let editBack = app.buttons["返回"].firstMatch
            if editBack.waitForExistence(timeout: 10) { editBack.tap() }
        }

        let playButton = app.buttons["开始播放"]
        if playButton.waitForExistence(timeout: 15) {
            playButton.tap()
        } else {
            missing.append("frame play")
        }
        sleep(8)
        capture("w-21-player")

        app.tap()
        sleep(1)
        let exitButton = app.buttons["退出全屏"].firstMatch
        if !exitButton.waitForExistence(timeout: 8) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            sleep(1)
        }
        if exitButton.waitForExistence(timeout: 8) {
            capture("w-22-player-controls")
            let pauseButton = app.buttons["暂停"].firstMatch
            if pauseButton.exists {
                pauseButton.tap()
                sleep(1)
                capture("w-23-player-paused")
            }
            let nextButton = app.buttons["下一张"].firstMatch
            if nextButton.exists {
                nextButton.tap()
                sleep(2)
            }
            let moreButton = app.buttons["更多"].firstMatch
            if moreButton.exists {
                moreButton.tap()
                sleep(1)
                capture("w-24-player-more")
                let cancelAction = app.buttons["取消"].firstMatch
                if cancelAction.waitForExistence(timeout: 5) { cancelAction.tap() }
            }
            exitButton.tap()
        } else {
            missing.append("player controls")
        }

        let libraryTab = app.buttons["照片库"]
        if libraryTab.waitForExistence(timeout: 15) {
            libraryTab.tap()
            sleep(2)
            capture("w-25-library")
            let searchField = app.searchFields.firstMatch
            if searchField.waitForExistence(timeout: 5) {
                searchField.tap()
                searchField.typeText("1")
                sleep(1)
                capture("w-26-library-search")
                let clearButton = searchField.buttons["Clear text"].firstMatch
                if clearButton.exists { clearButton.tap() }
                dismissKeyboard()
            }
            let firstPhoto = app.cells.matching(NSPredicate(format: "label BEGINSWITH '照片'")).firstMatch
            if firstPhoto.waitForExistence(timeout: 8) {
                firstPhoto.tap()
                sleep(2)
                capture("w-27-photo-detail")
                let detailClose = app.buttons["完成"].firstMatch
                if detailClose.waitForExistence(timeout: 5) {
                    detailClose.tap()
                } else {
                    let detailBack = app.buttons["返回"].firstMatch
                    if detailBack.waitForExistence(timeout: 5) { detailBack.tap() }
                }
            }
        }

        let sourcesTab = app.buttons["来源"]
        if sourcesTab.waitForExistence(timeout: 15) {
            sourcesTab.tap()
            sleep(2)
            capture("w-28-sources")
            let nasRow = app.buttons["NAS 共享文件夹"].firstMatch
            if nasRow.waitForExistence(timeout: 8) {
                nasRow.tap()
                sleep(2)
                capture("w-29-nas-form")
                let smbOption = app.buttons["SMB"].firstMatch
                if smbOption.waitForExistence(timeout: 5) {
                    smbOption.tap()
                    sleep(1)
                    capture("w-30-nas-smb")
                }
                let nasBack = app.buttons["返回"].firstMatch
                if nasBack.waitForExistence(timeout: 5) {
                    nasBack.tap()
                    let discard = app.buttons["放弃"].firstMatch
                    if discard.waitForExistence(timeout: 3) { discard.tap() }
                }
            }
        }

        let settingsTab = app.buttons["设置"]
        if settingsTab.waitForExistence(timeout: 15) {
            settingsTab.tap()
        } else {
            missing.append("settings tab")
        }
        sleep(2)
        capture("w-31-settings-top")
        app.swipeUp()
        capture("w-32-settings-mid")
        app.swipeUp()
        capture("w-33-settings-bottom")

        let holidayButton = app.buttons["设置"].firstMatch
        if holidayButton.waitForExistence(timeout: 5) {
            holidayButton.tap()
            sleep(2)
            capture("w-34-holiday-rules")
            let addRule = app.buttons["添加规则"].firstMatch
            if addRule.waitForExistence(timeout: 5) {
                addRule.tap()
                sleep(1)
                capture("w-35-holiday-add")
                let holidayCancel = app.buttons["取消"].firstMatch
                if holidayCancel.waitForExistence(timeout: 5) { holidayCancel.tap() }
            }
            let holidayBack = app.buttons["返回"].firstMatch
            if holidayBack.waitForExistence(timeout: 5) { holidayBack.tap() }
        }

        let licensesRow = app.buttons["开放源代码许可"].firstMatch
        if scrollUntilVisible(licensesRow, maxSwipes: 10) {
            licensesRow.tap()
            sleep(2)
            capture("w-36-licenses")
            let licenseBack = app.buttons["返回"].firstMatch
            if licenseBack.waitForExistence(timeout: 5) { licenseBack.tap() }
        } else {
            missing.append("licenses row")
        }

        if !missing.isEmpty {
            XCTFail("missing steps: \(missing.joined(separator: ", "))")
        }
    }

    private func expect(_ condition: Bool, _ label: String) {
        if !condition {
            missing.append(label)
        }
    }

    private func dismissKeyboard() {
        guard app.keyboards.firstMatch.waitForExistence(timeout: 2) else { return }
        for label in ["完成", "return", "Return", "done", "Done", "换行"] {
            let key = app.keyboards.buttons[label].firstMatch
            if key.exists {
                key.tap()
                return
            }
        }
        app.swipeDown()
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
