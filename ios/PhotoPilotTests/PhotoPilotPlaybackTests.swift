import XCTest
@testable import PhotoPilot

final class PhotoPilotPlaybackTests: XCTestCase {

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
