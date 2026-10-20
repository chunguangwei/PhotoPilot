import CoreData
import XCTest
import UIKit
@testable import PhotoPilot

final class PhotoPilotPlaybackTests: XCTestCase {

    @MainActor
    func testWeatherCacheAppearsWhileRefreshIsPending() async throws {
        let provider = PendingPlaybackWeatherProvider()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")),
            weatherProvider: provider
        )
        try environment.settings.setValue("true", forKey: WeatherRepository.enabledKey)
        try environment.settings.setValue("celsius", forKey: WeatherRepository.unitsKey)
        let place = WeatherPlace(placeId: "test:weather", nameZh: "测试城市", nameEn: "Test City", latE7: 0, lonE7: 0, region: "")
        try await environment.weatherRepository.savePlace(place, followLocation: false)
        let now = Int(Date().timeIntervalSince1970 * 1000)
        try await environment.weatherRepository.saveSnapshot(WeatherSnapshot(
            placeId: place.placeId, conditionCode: "clear", temperatureMilli: 22_000,
            observedAt: now - 40 * 60_000, fetchedAt: now - 40 * 60_000,
            expiresAt: now + 20 * 60_000, attributionText: "Open-Meteo.com", attributionURL: "https://open-meteo.com/"
        ))
        let context = environment.persistence.container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "WeatherSnapshot")
        request.predicate = NSPredicate(format: "place_id == %@", place.placeId)
        let cached = try XCTUnwrap(context.fetch(request).first)
        cached.setValue(NSNumber(value: now - 40 * 60_000), forKey: "fetched_at")
        try context.save()
        let frameID = try await environment.frameManager.createFrame(name: "Weather cache")
        let player = PlayerViewController(environment: environment, frameID: frameID)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 375, height: 667))
        window.rootViewController = player
        window.makeKeyAndVisible()
        defer {
            PlayerViewController.dismissActiveIfAny()
            window.isHidden = true
        }
        for _ in 0..<100 {
            if await provider.requestCount > 0 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let requests = await provider.requestCount
        XCTAssertGreaterThan(requests, 0)
        let label = try XCTUnwrap(Mirror(reflecting: player).children.first { $0.label == "weatherLabel" }?.value as? UILabel)
        for _ in 0..<100 where label.isHidden {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(label.isHidden, "缓存展示不能等待未完成的天气请求")
        XCTAssertTrue(label.attributedText?.string.contains("22") == true)
        await provider.release()
        for _ in 0..<100 where label.attributedText?.string.contains("28") != true {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertFalse(label.isHidden)
        XCTAssertTrue(label.attributedText?.string.contains("28") == true, "新天气完成后应主动刷新")
    }

    @MainActor
    func testWeatherRefreshKeepsCacheWarmAfterRequestingPlayerIsCancelled() async throws {
        let provider = PendingPlaybackWeatherProvider()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")),
            weatherProvider: provider
        )
        let place = WeatherPlace(placeId: "test:weather", nameZh: "测试城市", nameEn: "Test City", latE7: 0, lonE7: 0, region: "")
        let first = Task { await environment.weatherRefresher.refresh(place: place, manual: false) }
        for _ in 0..<100 {
            if await provider.requestCount > 0 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        first.cancel()
        await provider.release()
        _ = await first.value
        let snapshot = try await environment.weatherRepository.snapshot(placeId: place.placeId)
        XCTAssertEqual(snapshot?.temperatureMilli, 28_000, "退出播放器不应丢弃已完成的共享天气刷新结果")
    }

    @MainActor
    func testConcurrentWeatherRefreshesDoNotDuplicatePendingRequest() async throws {
        let provider = PendingPlaybackWeatherProvider()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")),
            weatherProvider: provider
        )
        let place = WeatherPlace(placeId: "test:weather", nameZh: "测试城市", nameEn: "Test City", latE7: 0, lonE7: 0, region: "")
        let first = Task { await environment.weatherRefresher.refresh(place: place, manual: false) }
        for _ in 0..<100 {
            if await provider.requestCount > 0 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let second = Task { await environment.weatherRefresher.refresh(place: place, manual: false) }
        try await Task.sleep(nanoseconds: 100_000_000)
        await provider.release()
        let firstOutcome = await first.value
        let secondOutcome = await second.value
        XCTAssertEqual(firstOutcome, .updated)
        XCTAssertEqual(secondOutcome, .updated)
        let requests = await provider.requestCount
        XCTAssertEqual(requests, 1)
    }

    @MainActor
    func testEmptyQueueClearsPreviouslyDisplayedPhotos() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        )
        let frameID = try await environment.frameManager.createFrame(name: "Empty queue")
        for state in [PlaybackController.holding, PlaybackController.transitioning,
                      PlaybackController.paused, PlaybackController.suspended, PlaybackController.resting] {
            let player = PlayerViewController(environment: environment, frameID: frameID)
            player.loadViewIfNeeded()
            player.controllerState = state
            for child in Mirror(reflecting: player).children {
                if ["frontImageView", "backImageView", "backgroundView"].contains(child.label ?? "") {
                    (child.value as? UIImageView)?.image = UIImage(systemName: "photo")
                }
            }
            player.rebuildQueue(keepIndex: true)
            for _ in 0..<100 {
                let safe = Mirror(reflecting: player).children.first { $0.label == "safeImageShown" }?.value as? Bool
                if safe == true { break }
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            let preserving = [PlaybackController.paused, PlaybackController.suspended, PlaybackController.resting].contains(state)
            XCTAssertEqual(player.controllerState, preserving ? state : PlaybackController.empty)
            let values = Mirror(reflecting: player).children
            XCTAssertEqual(values.first { $0.label == "safeImageShown" }?.value as? Bool, true)
            for label in ["frontImageView", "backImageView", "backgroundView"] {
                let imageView = values.first { $0.label == label }?.value as? UIImageView
                XCTAssertNil(imageView?.image)
            }
            PlayerViewController.dismissActiveIfAny()
        }
    }

    @MainActor
    func testEmptyPlayerResumesWhenQueueBecomesPlayable() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        )
        let frameID = try await environment.frameManager.createFrame(name: "Repopulated queue")
        let player = PlayerViewController(environment: environment, frameID: frameID)
        player.loadViewIfNeeded()
        player.controllerState = PlaybackController.holding
        player.rebuildQueue(keepIndex: true)
        defer { PlayerViewController.dismissActiveIfAny() }
        for _ in 0..<100 where player.controllerState != PlaybackController.empty {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(player.controllerState, PlaybackController.empty)
        let context = environment.persistence.container.viewContext
        func insert(_ entity: String, _ values: [String: Any]) -> NSManagedObject {
            let row = NSEntityDescription.insertNewObject(forEntityName: entity, into: context)
            for (key, attribute) in row.entity.attributesByName where !attribute.isOptional {
                row.setValue(attribute.defaultValue ?? (attribute.attributeType == .stringAttributeType ? "" : 0), forKey: key)
            }
            values.forEach { row.setValue($0.value, forKey: $0.key) }
            return row
        }
        let source = insert("Source", ["id": "fixture-source", "type": "local_files", "status": "active"])
        let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
        request.predicate = NSPredicate(format: "id == %@", frameID)
        let frame = try XCTUnwrap(context.fetch(request).first)
        try FileManager.default.createDirectory(at: environment.originalsRootURL, withIntermediateDirectories: true)
        let fixture = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
        }
        for id in ["fixture-one", "fixture-two"] {
            let path = id + ".jpg"
            try XCTUnwrap(fixture.jpegData(compressionQuality: 1)).write(to: environment.originalsRootURL.appendingPathComponent(path))
            let asset = insert("Asset", [
                "id": id, "content_hash": id, "original_rel_path": path,
                "readiness": "ready", "media_kind": "image", "format": "jpeg",
                "pixel_width": 16, "pixel_height": 16, "exif_orientation": 1,
                "orientation_class": "square", "captured_at_source": "unknown"
            ])
            let remote = insert("SourceAsset", ["id": id, "source": source, "remote_key": path, "access_state": "available"])
            _ = insert("AssetOrigin", ["asset": asset, "sourceAsset": remote, "policy_state": "allowed", "origin_state": "active"])
            _ = insert("FrameSelection", ["frame": frame, "asset": asset, "ref_key": id, "mode": "include"])
        }
        try context.save()
        let playable = try await environment.frames.buildPlayQueue(frameID: frameID)
        XCTAssertEqual(playable.count, 2)
        player.rebuildQueue(keepIndex: true)
        for _ in 0..<200 where player.controllerState != PlaybackController.holding {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(player.controllerState, PlaybackController.holding)
        let values = Mirror(reflecting: player).children
        XCTAssertEqual((values.first { $0.label == "emptyOverlay" }?.value as? UIView)?.isHidden, true)
        XCTAssertNotNil((values.first { $0.label == "frontImageView" }?.value as? UIImageView)?.image)
        XCTAssertNotNil(values.first { $0.label == "holdTask" }?.value as? Task<Void, Never>)
    }

    @MainActor
    func testSnapshotCannotRestoreAssetsOutsideCurrentPlayableQueue() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let environment = AppEnvironment(
            persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        )
        let frameID = try await environment.frameManager.createFrame(name: "Snapshot privacy")
        let bundle = try await environment.frameManager.framePlaybackBundle(frameID: frameID)
        try await environment.frameManager.savePlaybackSnapshot(
            frameID: frameID,
            remainingHoldMs: 500,
            cursorJSON: #"{"queue":["withdrawn-photo"],"index":0}"#,
            seed: bundle.randomSeed,
            selectionVersion: Int(bundle.selectionVersion)
        )
        let player = PlayerViewController(environment: environment, frameID: frameID)
        player.loadViewIfNeeded()
        player.viewWillAppear(false)
        defer { PlayerViewController.dismissActiveIfAny() }
        for _ in 0..<100 {
            let state = Mirror(reflecting: player).children.first { $0.label == "controllerState" }?.value as? String
            if state == PlaybackController.empty || state == PlaybackController.failed { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let values = Mirror(reflecting: player).children
        XCTAssertEqual(values.first { $0.label == "queue" }?.value as? [String], [])
        XCTAssertEqual(values.first { $0.label == "controllerState" }?.value as? String, PlaybackController.empty)
    }

    private func loadVector(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: name, withExtension: "json"),
            "向量文件未入测试 bundle：\(name).json"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func stringList(_ value: Any?) throws -> [String] {
        try XCTUnwrap(value as? [String])
    }

    func testStateMachineContractVectors() throws {
        let vector = try loadVector("playback-state")
        let cases = try XCTUnwrap(vector["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 34)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let eventJson = try XCTUnwrap(vectorCase["event"] as? [String: Any])
            let event = PlaybackEvent(
                type: try XCTUnwrap(eventJson["type"] as? String),
                nextReady: eventJson["next_ready"] as? Bool,
                queueIntact: eventJson["queue_intact"] as? Bool,
                playableRemaining: eventJson["playable_remaining"] as? Bool,
                category: eventJson["category"] as? String,
                limitReached: eventJson["limit_reached"] as? Bool
            )
            let outcome = PlaybackController.reduce(
                state: try XCTUnwrap(vectorCase["state"] as? String),
                event: event
            )
            let expected = try XCTUnwrap(vectorCase["expected"] as? [String: Any])
            XCTAssertEqual(try XCTUnwrap(expected["state"] as? String), outcome.state, id)
            XCTAssertEqual(try stringList(expected["effects"]), outcome.effects, id)
        }
    }

    func testDecodeFailLimitContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("playback-state"))["decode_fail_limit_cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 4)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            XCTAssertEqual(
                try XCTUnwrap((vectorCase["expected"] as? NSNumber)?.intValue),
                PlaybackController.decodeFailLimit(
                    queueLength: try XCTUnwrap((vectorCase["queue_length"] as? NSNumber)?.intValue)
                ),
                id
            )
        }
    }

    func testTimingContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("playback-timing"))["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 26)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let expected = try XCTUnwrap(vectorCase["expected"] as? [String: Any])
            switch try XCTUnwrap(vectorCase["scenario"] as? String) {
            case "schedule":
                let t0 = try XCTUnwrap((vectorCase["t0"] as? NSNumber)?.int64Value)
                let hold = try XCTUnwrap((vectorCase["hold_seconds"] as? NSNumber)?.intValue)
                let transition = try XCTUnwrap(vectorCase["transition"] as? String)
                let transitionSeconds = try XCTUnwrap((vectorCase["transition_seconds"] as? NSNumber)?.doubleValue)
                XCTAssertEqual(
                    try XCTUnwrap((expected["next_transition_at"] as? NSNumber)?.int64Value),
                    PlaybackTiming.nextTransitionAt(t0: t0, holdSeconds: hold),
                    id
                )
                XCTAssertEqual(
                    try XCTUnwrap((expected["next_stable_at"] as? NSNumber)?.int64Value),
                    PlaybackTiming.nextStableAt(
                        t0: t0,
                        holdSeconds: hold,
                        transition: transition,
                        transitionSeconds: transitionSeconds
                    ),
                    id
                )
            case "pause_resume":
                let nextTransition = PlaybackTiming.nextTransitionAt(
                    t0: try XCTUnwrap((vectorCase["t0"] as? NSNumber)?.int64Value),
                    holdSeconds: try XCTUnwrap((vectorCase["hold_seconds"] as? NSNumber)?.intValue)
                )
                let remaining = PlaybackTiming.remainingAtPause(
                    nextTransitionAt: nextTransition,
                    pauseAt: try XCTUnwrap((vectorCase["pause_at"] as? NSNumber)?.int64Value)
                )
                XCTAssertEqual(
                    try XCTUnwrap((expected["next_transition_at"] as? NSNumber)?.int64Value),
                    PlaybackTiming.resumeTransitionAt(
                        resumeAt: try XCTUnwrap((vectorCase["resume_at"] as? NSNumber)?.int64Value),
                        remaining: remaining
                    ),
                    id
                )
            case "validate":
                XCTAssertEqual(
                    try XCTUnwrap(expected["valid"] as? Bool),
                    PlaybackTiming.isValidSettings(
                        holdSeconds: try XCTUnwrap((vectorCase["hold_seconds"] as? NSNumber)?.doubleValue),
                        transition: try XCTUnwrap(vectorCase["transition"] as? String),
                        transitionSeconds: try XCTUnwrap((vectorCase["transition_seconds"] as? NSNumber)?.doubleValue)
                    ),
                    id
                )
            case "rest":
                XCTAssertEqual(
                    try XCTUnwrap(expected["active"] as? Bool),
                    PlaybackTiming.restActive(
                        enabled: try XCTUnwrap(vectorCase["enabled"] as? Bool),
                        startMinute: try XCTUnwrap((vectorCase["start_minute"] as? NSNumber)?.intValue),
                        endMinute: try XCTUnwrap((vectorCase["end_minute"] as? NSNumber)?.intValue),
                        nowMinute: try XCTUnwrap((vectorCase["now_minute"] as? NSNumber)?.intValue)
                    ),
                    id
                )
            default:
                XCTFail("\(id): unknown scenario")
            }
        }
    }

    func testRestDisplayDefaultsToClockWhenMissing() {
        let parsed = PlayerSettings.parse(
            json: #"{"rest":{"enabled":true,"startMinute":120,"endMinute":360}}"#
        )
        XCTAssertEqual("clock", parsed.restDisplay)
        XCTAssertTrue(parsed.restEnabled)
        XCTAssertEqual(120, parsed.restStartMinute)
    }

    func testRestDisplayAcceptsAllModes() {
        for mode in ["dim", "clock", "clock_weather", "black"] {
            let parsed = PlayerSettings.parse(json: "{\"rest\":{\"display\":\"\(mode)\"}}")
            XCTAssertEqual(mode, parsed.restDisplay)
        }
    }

    func testRestDisplayFallsBackToClockForUnknownValue() {
        let unknown = PlayerSettings.parse(json: #"{"rest":{"display":"rainbow"}}"#)
        XCTAssertEqual("clock", unknown.restDisplay)
        let nonString = PlayerSettings.parse(json: #"{"rest":{"display":3}}"#)
        XCTAssertEqual("clock", nonString.restDisplay)
        let legacy = PlayerSettings.parse(json: #"{"holdSeconds":10}"#)
        XCTAssertEqual("clock", legacy.restDisplay)
    }

    func testDefaultSettingsJSONRestDisplayIsClock() throws {
        let json = FrameManager.defaultSettingsJSON()
        let object = try XCTUnwrap(JSONSerialization.jsonObject(
            with: Data(json.utf8)
        ) as? [String: Any])
        let rest = try XCTUnwrap(object["rest"] as? [String: Any])
        XCTAssertEqual("clock", rest["display"] as? String)
        XCTAssertEqual("clock", PlayerSettings.parse(json: json).restDisplay)
    }

    func testRestMergePreservesUnknownKeys() throws {
        let original = #"{"rest":{"enabled":true,"display":"dim","futureKey":{"nested":1}},"futureTop":"keep"}"#
        var settings = try XCTUnwrap(JSONSerialization.jsonObject(
            with: Data(original.utf8)
        ) as? [String: Any])
        var rest = settings["rest"] as? [String: Any] ?? [:]
        rest["enabled"] = false
        rest["startMinute"] = 0
        rest["endMinute"] = 60
        rest["display"] = "black"
        settings["rest"] = rest
        let data = try JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys])
        let roundTrip = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let restOut = try XCTUnwrap(roundTrip["rest"] as? [String: Any])
        XCTAssertEqual("black", restOut["display"] as? String)
        XCTAssertEqual(["nested": 1], restOut["futureKey"] as? [String: Int])
        XCTAssertEqual("keep", roundTrip["futureTop"] as? String)
        XCTAssertEqual("black", PlayerSettings.parse(
            json: String(decoding: data, as: UTF8.self)
        ).restDisplay)
    }
}

private actor PendingPlaybackWeatherProvider: WeatherProvider {
    private(set) var requestCount = 0
    private var continuation: CheckedContinuation<WeatherSnapshot, Never>?
    private var released = false

    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot {
        requestCount += 1
        if released || requestCount > 1 { return snapshot() }
        return await withCheckedContinuation { continuation = $0 }
    }

    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate] { [] }

    func release() {
        released = true
        continuation?.resume(returning: snapshot())
        continuation = nil
    }

    private func snapshot() -> WeatherSnapshot {
        let now = Int(Date().timeIntervalSince1970 * 1000)
        return WeatherSnapshot(
            placeId: "test:weather", conditionCode: "clear", temperatureMilli: 28_000,
            observedAt: now, fetchedAt: now, expiresAt: now + 60 * 60_000,
            attributionText: "Open-Meteo.com", attributionURL: "https://open-meteo.com/"
        )
    }
}
