import XCTest
@testable import PhotoPilot

final class PhotoPilotSelectionTests: XCTestCase {

    private func loadVector(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: name, withExtension: "json"),
            "向量文件未入测试 bundle：\(name).json"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func vectorCases(_ name: String) throws -> [[String: Any]] {
        try XCTUnwrap((try loadVector(name))["cases"] as? [[String: Any]])
    }

    private func stringList(_ value: Any?) throws -> [String] {
        try XCTUnwrap(value as? [String])
    }

    private func stringMap(_ value: Any?) throws -> [String: [String]] {
        let raw = try XCTUnwrap(value as? [String: [String]])
        return raw
    }

    func testSelectionResolveContractVectors() throws {
        let cases = try vectorCases("selection-resolve")
        XCTAssertGreaterThanOrEqual(cases.count, 12)
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            let selections = try XCTUnwrap(vector["selections"] as? [[String: Any]]).map { entry in
                SelectionRef(
                    assetId: entry["asset"] as? String,
                    sourceAssetId: entry["source"] as? String,
                    mode: try! XCTUnwrap(entry["mode"] as? String)
                )
            }
            let rules = try XCTUnwrap(vector["rules"] as? [[String: Any]]).map { entry in
                DirectoryRuleRef(
                    collectionId: try! XCTUnwrap(entry["collection"] as? String),
                    recursive: try! XCTUnwrap(entry["recursive"] as? Bool)
                )
            }
            var assets: [String: ResolvableAsset] = [:]
            for (key, value) in try XCTUnwrap(vector["assets"] as? [String: [String: Any]]) {
                assets[key] = ResolvableAsset(
                    id: key,
                    readiness: try XCTUnwrap(value["readiness"] as? String),
                    privacyExcluded: try XCTUnwrap(value["privacy_excluded"] as? Bool),
                    deleted: try XCTUnwrap(value["deleted"] as? Bool),
                    fileExists: try XCTUnwrap(value["file_exists"] as? Bool),
                    policyAllowed: try XCTUnwrap(value["policy_allowed"] as? Bool)
                )
            }
            let resolution = SelectionResolver.resolve(
                SelectionResolveInput(
                    selections: selections,
                    rules: rules,
                    collectionChildren: try stringMap(vector["collection_children"]),
                    collectionAssets: try stringMap(vector["collection_assets"]),
                    assets: assets
                )
            )
            let expected = try XCTUnwrap(vector["expected"] as? [String: Any])
            XCTAssertEqual(try stringList(expected["candidates"]), resolution.candidates, id)
            XCTAssertEqual(try stringList(expected["pending"]), resolution.pending, id)
            XCTAssertEqual(try stringList(expected["allowed"]), resolution.allowed, id)
            XCTAssertEqual(try stringList(expected["playable"]), resolution.playable, id)
        }
    }

    private func queueAssets(_ vector: [String: Any]) throws -> [QueueAsset] {
        try XCTUnwrap(vector["assets"] as? [[String: Any]]).map { entry in
            QueueAsset(
                id: try! XCTUnwrap(entry["id"] as? String),
                capturedAt: (entry["captured_at"] as? NSNumber)?.int64Value,
                importedAt: try! XCTUnwrap((entry["imported_at"] as? NSNumber)?.int64Value),
                manualOrder: (entry["manual_order"] as? NSNumber)?.int64Value,
                addedAt: try! XCTUnwrap((entry["added_at"] as? NSNumber)?.int64Value)
            )
        }
    }

    func testPlaybackQueueContractVectors() throws {
        let cases = try vectorCases("playback-queue")
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            let assets = try queueAssets(vector)
            switch (vector["scenario"] as? String) ?? "queue" {
            case "browse":
                XCTAssertEqual(
                    try stringList(vector["expected"]),
                    PlaybackQueue.browseOrder(assets),
                    id
                )
            case "bucket":
                let expected = try XCTUnwrap(vector["expected"] as? [String: Any])
                let actual = PlaybackQueue.monthBuckets(assets)
                XCTAssertEqual(try stringList(expected["bucket_order"]), actual.bucketOrder, id)
                XCTAssertEqual(try stringList(expected["no_captured_time"]), actual.noCapturedTime, id)
                let expectedBuckets = try XCTUnwrap(expected["buckets"] as? [String: [String]])
                XCTAssertEqual(expectedBuckets, actual.buckets, id)
            default:
                let seed = try XCTUnwrap((vector["random_seed"] as? NSNumber)?.uint64Value)
                XCTAssertEqual(
                    try stringList(vector["expected"]),
                    PlaybackQueue.buildQueue(
                        assets: assets,
                        orderMode: try XCTUnwrap(vector["order_mode"] as? String),
                        randomSeed: seed
                    ),
                    id
                )
            }
        }
    }

    func testRandomSameSeedReproducibleAndPermutation() {
        let ids = (1...50).map { "asset-\($0)" }
        let first = PlaybackQueue.shuffle(ids: ids, seed: 2024)
        let second = PlaybackQueue.shuffle(ids: ids, seed: 2024)
        XCTAssertEqual(first, second)
        XCTAssertEqual(ids.sorted(), first.sorted())
    }

    func testSplitMix64MatchesContractBitPattern() {
        var random = SplitMix64(seed: 0)
        XCTAssertEqual(0xE220_A839_7B1D_CDAF, random.next())
    }

    func testMonthKeyEpochBoundaries() {
        XCTAssertEqual("1970-01", PlaybackQueue.monthKey(0))
        XCTAssertEqual("2024-03", PlaybackQueue.monthKey(1_710_504_000_000))
        XCTAssertEqual("2023-12", PlaybackQueue.monthKey(1_704_063_600_000))
        XCTAssertEqual("1969-12", PlaybackQueue.monthKey(-1))
    }

    private func includeRows(_ value: Any?) throws -> [SaveIncludeRow] {
        try XCTUnwrap(value as? [[String: Any]]).map { entry in
            SaveIncludeRow(
                ref: try! XCTUnwrap(entry["ref"] as? String),
                manualOrder: (entry["manual_order"] as? NSNumber)?.int64Value,
                addedAt: ((entry["added_at"] as? NSNumber)?.int64Value) ?? 0
            )
        }
    }

    private func ruleRows(_ value: Any?) throws -> [SaveRuleRow] {
        try XCTUnwrap(value as? [[String: Any]]).map { entry in
            SaveRuleRow(
                collectionId: try! XCTUnwrap(entry["collection"] as? String),
                recursive: try! XCTUnwrap(entry["recursive"] as? Bool),
                includeFuture: try! XCTUnwrap(entry["include_future"] as? Bool)
            )
        }
    }

    func testSelectionSaveContractVectors() throws {
        let cases = try vectorCases("selection-save")
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            let requestJson = try XCTUnwrap(vector["request"] as? [String: Any])
            var request = SelectionSaveRequest()
            request.addIncludes = try XCTUnwrap(requestJson["add_includes"] as? [[String: Any]]).map { entry in
                AddInclude(
                    ref: try! XCTUnwrap(entry["ref"] as? String),
                    addedAt: try! XCTUnwrap((entry["added_at"] as? NSNumber)?.int64Value)
                )
            }
            request.removeRefs = try stringList(requestJson["remove_refs"])
            request.addExcludes = try stringList(requestJson["add_excludes"])
            request.ruleChanges = try XCTUnwrap(requestJson["rule_changes"] as? [[String: Any]]).map { entry in
                RuleChange(
                    collectionId: try! XCTUnwrap(entry["collection"] as? String),
                    recursive: try! XCTUnwrap(entry["recursive"] as? Bool),
                    includeFuture: try! XCTUnwrap(entry["include_future"] as? Bool),
                    confirmed: try! XCTUnwrap(entry["confirmed"] as? Bool),
                    members: try! stringList(entry["members"]),
                    addedAt: try! XCTUnwrap((entry["added_at"] as? NSNumber)?.int64Value)
                )
            }
            request.removeRules = try stringList(requestJson["remove_rules"])

            let result = SelectionSavePlanner.plan(
                SelectionSaveInput(
                    currentVersion: try XCTUnwrap((vector["current_version"] as? NSNumber)?.int64Value),
                    baseVersion: try XCTUnwrap((vector["base_version"] as? NSNumber)?.int64Value),
                    orderMode: try XCTUnwrap(vector["order_mode"] as? String),
                    existingIncludes: try includeRows(vector["existing_includes"]),
                    existingExcludes: try stringList(vector["existing_excludes"]),
                    existingRules: try ruleRows(vector["existing_rules"]),
                    request: request
                )
            )

            let expected = try XCTUnwrap(vector["expected"] as? [String: Any])
            switch try XCTUnwrap(expected["result"] as? String) {
            case "conflict":
                guard case .conflict(let error) = result else {
                    XCTFail("\(id): expected conflict, got \(result)")
                    continue
                }
                XCTAssertEqual(try XCTUnwrap(expected["error"] as? String), error, id)
            case "rejected":
                XCTAssertEqual(.rejected, result, id)
            default:
                guard case .ok(let newVersion, let includeRows, let excludeRows, let ruleRows) = result else {
                    XCTFail("\(id): expected ok, got \(result)")
                    continue
                }
                XCTAssertEqual(
                    try XCTUnwrap((expected["new_version"] as? NSNumber)?.int64Value),
                    newVersion,
                    id
                )
                let expectedIncludeRows = try self.includeRows(expected["include_rows"])
                XCTAssertEqual(expectedIncludeRows.map { $0.ref }, includeRows.map { $0.ref }, id)
                XCTAssertEqual(expectedIncludeRows.map { $0.manualOrder }, includeRows.map { $0.manualOrder }, id)
                XCTAssertEqual(try stringList(expected["exclude_rows"]), excludeRows, id)
                XCTAssertEqual(try self.ruleRows(expected["rule_rows"]), ruleRows, id)
            }
        }
    }
}
