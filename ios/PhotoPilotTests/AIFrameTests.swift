import XCTest
@testable import PhotoPilot

final class AIFrameTests: XCTestCase {
    func testCosineRejectsInvalidVectors() throws {
        XCTAssertThrowsError(try AIFrameScoring.cosine([0, 0], [1, 0]))
        XCTAssertThrowsError(try AIFrameScoring.cosine([1], [1, 0]))
        XCTAssertThrowsError(try AIFrameScoring.cosine([.nan, 1], [1, 0]))
        XCTAssertEqual(try AIFrameScoring.cosine([2, 0], [1, 0]), 1, accuracy: 0.0001)
    }

    func testIncludesAndExcludesUseSemanticScores() throws {
        let rule = AIFrameRule(category: "海边 / beach", includes: ["日落"], excludes: ["人群"], matchAll: true, threshold: 0.5)
        XCTAssertEqual(rule.decision(categoryScore: 0.8, includeScores: [0.7], excludeScores: [0.2]), .matched)
        XCTAssertEqual(rule.decision(categoryScore: 0.8, includeScores: [0.3], excludeScores: [0.2]), .rejected)
        XCTAssertEqual(rule.decision(categoryScore: 0.8, includeScores: [0.7], excludeScores: [0.8]), .rejected)
        XCTAssertEqual(rule.decision(categoryScore: 0.48, includeScores: [0.7], excludeScores: [0.2]), .review)
    }

    func testManualOwnershipSurvivesReclassification() {
        var state = AIFrameState(configuration: .fixture)
        state.ownedAssetIDs = ["ai", "promoted"]
        state.recordManualChange(includes: ["promoted", "manual"], removed: ["excluded"])
        XCTAssertEqual(state.ownedAssetIDs, ["ai"])
        XCTAssertEqual(state.manualExcludedAssetIDs, ["excluded"])
        XCTAssertFalse(state.canAutomaticallyInclude("excluded"))
        XCTAssertTrue(state.canAutomaticallyInclude("fresh"))
    }

    func testRunTokenRejectsChangedRulesAndCancellation() {
        var state = AIFrameState(configuration: .fixture)
        let token = state.beginRun()
        XCTAssertTrue(state.accepts(token))
        state.cancel()
        XCTAssertFalse(state.accepts(token))
        let next = state.beginRun()
        state.configuration.revision += 1
        XCTAssertFalse(state.accepts(next))
    }

    func testManualCancellationStopsAutomaticForegroundRestart() {
        var configuration = AIFrameConfiguration.fixture
        configuration.automatic = true
        var state = AIFrameState(configuration: configuration)
        XCTAssertTrue(state.shouldRunOnForeground)
        _ = state.beginRun()
        state.cancel()
        XCTAssertFalse(state.shouldRunOnForeground)
        state.status = "pausedAuto"
        XCTAssertTrue(state.shouldRunOnForeground)
        state.status = "failed"
        XCTAssertFalse(state.shouldRunOnForeground)
    }

    func testRuleLimitsCountUnicodeScalarsAndUTF8Bytes() throws {
        XCTAssertNoThrow(try AIFrameRule(category: String(repeating: "猫", count: 500)).validate())
        let familyEmoji = String(repeating: "👩‍👩‍👧‍👧", count: 100)
        XCTAssertLessThan(familyEmoji.count, 500)
        XCTAssertThrowsError(try AIFrameRule(category: familyEmoji).validate()) { error in
            XCTAssertEqual(error as? AIFrameError, .promptTooLong)
        }
    }

    func testMemoryAdmissionProtectsTwoGBDevicesAndReservesWorkingSpace() {
        XCTAssertFalse(AIFrameMemoryPolicy.canLoad(runtime: "litertlm", physicalBytes: 2_147_483_648, availableBytes: 2_000_000_000))
        XCTAssertFalse(AIFrameMemoryPolicy.canLoad(runtime: "litertlm", physicalBytes: 4_294_967_296, availableBytes: 1_499_999_999))
        XCTAssertTrue(AIFrameMemoryPolicy.canLoad(runtime: "litertlm", physicalBytes: 4_294_967_296, availableBytes: 1_500_000_000))
        XCTAssertFalse(AIFrameMemoryPolicy.canLoad(runtime: "onnx", physicalBytes: 2_147_483_648, availableBytes: 549_999_999))
        XCTAssertTrue(AIFrameMemoryPolicy.canLoad(runtime: "onnx", physicalBytes: 2_147_483_648, availableBytes: 550_000_000))
    }

    func testRetiredModelStateRemainsReadableForEditing() throws {
        var configuration = AIFrameConfiguration.fixture
        configuration.modelID = "siglip2-base-int8"
        configuration.rule.threshold = 0.075
        let state = AIFrameState(configuration: configuration, ownedAssetIDs: ["existing-photo"])
        let decoded = try AIFrameState.decode(JSONEncoder().encode(state))
        XCTAssertEqual(decoded.configuration.modelID, "siglip2-base-int8")
        XCTAssertEqual(decoded.ownedAssetIDs, ["existing-photo"])
    }

    func testDurableStateRoundTripsAndRejectsUnknownSchema() throws {
        var state = AIFrameState(configuration: .fixture)
        state.completedContentVersions["asset"] = "hash:revision:1"
        state.ownedAssetIDs = ["asset"]
        let data = try JSONEncoder().encode(state)
        let restored = try AIFrameState.decode(data)
        XCTAssertEqual(restored.completedContentVersions, state.completedContentVersions)
        XCTAssertEqual(restored.ownedAssetIDs, state.ownedAssetIDs)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        object["schemaVersion"] = 99
        XCTAssertThrowsError(try AIFrameState.decode(JSONSerialization.data(withJSONObject: object)))
    }

    func testDefaultIsOneTimeAndBilingualNamesUseActualContentTags() {
        let config = AIFrameConfiguration.fixture
        XCTAssertFalse(config.automatic)
        XCTAssertEqual(AIFrameNames.suggestion(category: "假期", themes: ["beach"], chinese: true), "假期 · 海边")
        XCTAssertEqual(AIFrameNames.suggestion(category: "Holiday", themes: ["beach"], chinese: false), "Holiday · Beach")
    }
}

private extension AIFrameConfiguration {
    static var fixture: AIFrameConfiguration {
        AIFrameConfiguration(frameID: "frame", modelID: "multilingual-clip-vitb32-lite", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cats"))
    }
}
