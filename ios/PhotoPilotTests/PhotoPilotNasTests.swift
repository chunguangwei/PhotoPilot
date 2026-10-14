import XCTest
@testable import PhotoPilot

final class PhotoPilotNasTests: XCTestCase {

    private func loadVector(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: name, withExtension: "json"),
            "向量文件未入测试 bundle：\(name).json"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testAddressContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("nas-address"))["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 31)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            if let parseInput = vectorCase["parse_input"] as? String {
                let expected = try XCTUnwrap(vectorCase["expected"] as? [String: Any])
                let parsed = NasAddress.parseHostInput(parseInput)
                if (expected["ok"] as? Bool) == true {
                    let value = try XCTUnwrap(parsed, id)
                    XCTAssertEqual(value.host, expected["host"] as? String, id)
                    XCTAssertEqual(value.port, (expected["port"] as? NSNumber)?.intValue, id)
                } else {
                    XCTAssertNil(parsed, id)
                }
            }
            if let classify = vectorCase["classify_host"] as? String {
                XCTAssertEqual(
                    NasAddress.classifyHost(classify).rawValue,
                    vectorCase["expected_kind"] as? String,
                    id
                )
                XCTAssertEqual(
                    NasAddress.isPrivateEligible(host: classify),
                    vectorCase["expected_private"] as? Bool,
                    id
                )
            }
            if let fingerprintInput = vectorCase["fingerprint_input"] as? [String: Any] {
                let fingerprint = NasAddress.sourceFingerprint(
                    scheme: try XCTUnwrap(fingerprintInput["protocol"] as? String),
                    host: try XCTUnwrap(fingerprintInput["host"] as? String),
                    port: (fingerprintInput["port"] as? NSNumber)?.intValue,
                    path: fingerprintInput["path"] as? String ?? ""
                )
                XCTAssertEqual(fingerprint, vectorCase["expected_fingerprint"] as? String, id)
            }
        }
    }

    func testPathContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("nas-path"))["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 17)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let input = try XCTUnwrap(vectorCase["input"] as? String)
            let expected = try XCTUnwrap(vectorCase["expected"] as? [String: Any])
            let normalized = NasPath.normalize(input)
            if (expected["ok"] as? Bool) == true {
                XCTAssertEqual(try XCTUnwrap(normalized, id), expected["path"] as? String, id)
            } else {
                XCTAssertNil(normalized, id)
            }
        }
    }

    func testDeletionContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("nas-deletion"))["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 10)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let batchSize = try XCTUnwrap((vectorCase["batch_size"] as? NSNumber)?.intValue)
            let expectedState = try XCTUnwrap(vectorCase["expected_state"] as? String)

            if let events = vectorCase["events"] as? [[String: Any]] {
                var state = NasDeletionItemState()
                var sawItem = false
                var lastNow: Int64 = 0
                for event in events {
                    guard let scan = event["scan"] as? [String: Any] else { continue }
                    let complete = try XCTUnwrap(scan["complete"] as? Bool)
                    let authOK = try XCTUnwrap(scan["auth_ok"] as? Bool)
                    let now = try XCTUnwrap((scan["now_ms"] as? NSNumber)?.int64Value)
                    let seen = try XCTUnwrap(scan["seen"] as? [String])
                    let itemSeen = !seen.isEmpty
                    NasDeletionJudge.applyScan(
                        state: &state,
                        complete: complete,
                        authOK: authOK,
                        nowMs: now,
                        seen: itemSeen
                    )
                    if complete, authOK {
                        lastNow = now
                    }
                    if itemSeen {
                        sawItem = true
                    }
                }
                let verdict: NasDeletionVerdict
                if state.missingScanCount == 0, sawItem {
                    verdict = .cleared
                } else if !NasDeletionJudge.isDeletionEligible(state: state, nowMs: lastNow) {
                    verdict = .pending
                } else {
                    verdict = NasDeletionJudge.judgeBatch(
                        candidates: 1,
                        batchSize: batchSize
                    )
                }
                XCTAssertEqual(verdict.rawValue, expectedState, id)
                if let expectedCount = (vectorCase["expected_missing_count"] as? NSNumber)?.intValue {
                    XCTAssertEqual(state.missingScanCount, expectedCount, id)
                }
            } else {
                let candidates = try XCTUnwrap((vectorCase["candidates"] as? NSNumber)?.intValue)
                XCTAssertEqual(
                    NasDeletionJudge.judgeBatch(candidates: candidates, batchSize: batchSize).rawValue,
                    expectedState,
                    id
                )
            }
        }
    }

    func testScopeNormalizeDenesting() throws {
        XCTAssertEqual(
            NasScope.normalizeScope(["Photos/2024", "photos", "/Photos/", "photos"]),
            ["Photos", "photos"]
        )
        XCTAssertEqual(NasScope.normalizeScope(["a/b", "a"]), ["a"])
        XCTAssertEqual(NasScope.normalizeScope(["/", "photos"]), [""])
        XCTAssertEqual(NasScope.normalizeScope(["a", "a", "a/"]), ["a"])
        XCTAssertNil(NasScope.normalizeScope([]))
        XCTAssertNil(NasScope.normalizeScope(["../escape"]))
        XCTAssertEqual(NasScope.normalizeScope(["b", "a/b/c", "a"]), ["a", "b"])
        XCTAssertEqual(
            NasScope.scopeJSON(["Photos/2024", "Photos"]),
            "{\"directories\":[\"Photos\"]}"
        )
    }

    func testSyncDiffContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("nas-sync-diff"))["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 8)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let old = try revisionMap(vectorCase["old"])
            let new = try revisionMap(vectorCase["new"])
            let mirrorDelete = try XCTUnwrap(vectorCase["mirror_delete"] as? Bool)
            let expected = try XCTUnwrap(vectorCase["expected"] as? [String: String])
            let result = NasSyncDiff.diff(old: old, new: new, mirrorDelete: mirrorDelete)
            XCTAssertEqual(result.count, expected.count, id)
            for (remoteID, action) in expected {
                XCTAssertEqual(result[remoteID]?.rawValue, action, "\(id) \(remoteID)")
            }
        }
    }

    func testEnumCursorContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("nas-enum-cursor"))["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 6)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let expected = try XCTUnwrap(vectorCase["state"] as? [String: Any])
            let expectedDirs = try XCTUnwrap(expected["dirs"] as? [String])
            let expectedSkip = try XCTUnwrap((expected["skip"] as? NSNumber)?.intValue)
            if let golden = vectorCase["golden"] as? String {
                let decoded = try XCTUnwrap(
                    WebDavSourceAdapter.decodeCursor(golden),
                    "\(id) golden parse"
                )
                XCTAssertEqual(decoded.dirs, expectedDirs, id)
                XCTAssertEqual(decoded.skip, expectedSkip, id)
            }
            if let legacy = vectorCase["legacy"] as? String {
                let decoded = WebDavSourceAdapter.decodeCursor(legacy)
                XCTAssertEqual(decoded?.dirs ?? [], expectedDirs, id)
                XCTAssertEqual(decoded?.skip ?? 0, expectedSkip, id)
            }
            if (vectorCase["roundtrip"] as? Bool) == true {
                let state = WebDavSourceAdapter.EnumState(dirs: expectedDirs, skip: expectedSkip)
                let decoded = try XCTUnwrap(
                    WebDavSourceAdapter.decodeCursor(WebDavSourceAdapter.encodeCursor(state)),
                    "\(id) roundtrip"
                )
                XCTAssertEqual(decoded, state, id)
            }
        }
    }

    func testEnumCursorAndroidGoldenFormatMatches() {
        XCTAssertEqual(WebDavSourceAdapter.cursorPrefix, "queue:")
        let encoded = WebDavSourceAdapter.encodeCursor(
            WebDavSourceAdapter.EnumState(dirs: ["a/b", "c"], skip: 3)
        )
        XCTAssertTrue(encoded.hasPrefix("queue:"), encoded)
        let data = try! XCTUnwrap(String(encoded.dropFirst("queue:".count)).data(using: .utf8))
        let object = try! XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(object["dirs"] as? [String], ["a/b", "c"])
        XCTAssertEqual((object["skip"] as? NSNumber)?.intValue, 3)
    }

    private func revisionMap(_ value: Any?) throws -> [String: String?] {
        let raw = try XCTUnwrap(value as? [String: Any])
        var map: [String: String?] = [:]
        for (key, element) in raw {
            map[key] = element is NSNull ? nil : element as? String
        }
        return map
    }
}
