import CryptoKit
import XCTest
@testable import PhotoPilot

final class AIFrameVectorTests: XCTestCase {
    func testSharedClassificationVectors() throws {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: type(of: self))
        #endif
        let url = try XCTUnwrap(bundle.url(forResource: "ai-frame-classify", withExtension: "json"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try XCTUnwrap(object["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 18)
        for item in cases {
            let includes = (item["includeScores"] as? [NSNumber] ?? []).map(\.floatValue)
            let excludes = (item["excludeScores"] as? [NSNumber] ?? []).map(\.floatValue)
            let rule = AIFrameRule(category: "category", includes: includes.map { _ in "include" },
                                   excludes: excludes.map { _ in "exclude" }, matchAll: item["matchAll"] as? Bool ?? true,
                                   threshold: (item["threshold"] as? NSNumber)?.floatValue ?? .nan)
            let actual = rule.decision(categoryScore: (item["categoryScore"] as? NSNumber)?.floatValue ?? .nan,
                                       includeScores: includes, excludeScores: excludes)
            XCTAssertEqual(actual.rawValue, item["expected"] as? String, "\(item["id"] ?? "")")
        }
    }

    private func catalog(fileName: String = "model.onnx", url: String = "https://example.com/model.onnx", digest: String? = nil) throws -> Data {
        let hash = digest ?? SHA256.hash(data: Data("abc".utf8)).map { String(format: "%02x", $0) }.joined()
        return try JSONSerialization.data(withJSONObject: ["formatVersion": 1, "models": [[
            "id": "test", "revision": "fixed", "displayName": ["en": "Test", "zh-Hans": "测试"], "runtime": "litertlm", "embeddingDimension": 768,
            "files": [["name": fileName, "role": "model", "size": 3, "sha256": hash,
                       "urls": ["international": [url], "domestic": []]]]
        ]]])
    }

    func testManifestRejectsTraversalAndUnencryptedURLs() throws {
        XCTAssertThrowsError(try AIModelCatalog.load(data: catalog(fileName: "../model.onnx")))
        XCTAssertThrowsError(try AIModelCatalog.load(data: catalog(url: "http://example.com/model.onnx")))
        XCTAssertThrowsError(try AIModelCatalog.load(data: catalog(url: "https://user:secret@example.com/model.onnx")))
        XCTAssertThrowsError(try AIModelCatalog.load(data: catalog(digest: "invalid")))
        XCTAssertEqual(try AIModelCatalog.load(data: catalog()).models.count, 1)
    }

    func testManifestRequiresRuntimeSpecificDimensionAndRoles() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: catalog()) as? [String: Any])
        var models = try XCTUnwrap(object["models"] as? [[String: Any]])
        models[0]["embeddingDimension"] = 512
        object["models"] = models
        XCTAssertThrowsError(try AIModelCatalog.load(data: JSONSerialization.data(withJSONObject: object)))
        models[0]["runtime"] = "onnx"
        object["models"] = models
        XCTAssertThrowsError(try AIModelCatalog.load(data: JSONSerialization.data(withJSONObject: object)))
    }

    func testModelVerificationRejectsSameSizeCorruption() throws {
        let descriptor = try XCTUnwrap(AIModelCatalog.load(data: catalog()).models.first)
        let file = try XCTUnwrap(descriptor.files.first)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("abc".utf8).write(to: url)
        XCTAssertTrue(try AIModelManager.verify(url, file: file))
        try Data("abd".utf8).write(to: url)
        XCTAssertFalse(try AIModelManager.verify(url, file: file))
        try Data("ab".utf8).write(to: url)
        XCTAssertFalse(try AIModelManager.verify(url, file: file))
    }
}
