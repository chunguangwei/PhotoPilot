import Foundation
import XCTest
@testable import PhotoPilot

final class AIEmbeddingTokenLimitTests: XCTestCase {
    private func tokenizer() throws -> AIWordPieceTokenizer {
        #if SWIFT_PACKAGE
        let url = Bundle.module.url(forResource: "vocab", withExtension: "txt")
        #else
        let url = Bundle.main.url(forResource: "vocab", withExtension: "txt")
            ?? ProcessInfo.processInfo.environment["PHOTOPILOT_AI_TEST_MODEL_ROOT"].map {
                URL(fileURLWithPath: $0).appendingPathComponent("vocab.txt")
            }
        #endif
        guard let url else { throw XCTSkip("Requires the verified vocabulary in the isolated AI QA app.") }
        return try AIWordPieceTokenizer(vocabulary: String(contentsOf: url, encoding: .utf8))
    }

    func testTokenizerMatchesOfficialMultilingualGoldenCases() throws {
        let tokenizer = try tokenizer()
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: type(of: self))
        #endif
        struct Example: Decodable { let text: String; let ids: [Int64] }
        let url = try XCTUnwrap(bundle.url(forResource: "ai-wordpiece", withExtension: "json"))
        struct Reference: Decodable { let cases: [Example] }
        let examples = try JSONDecoder().decode(Reference.self, from: Data(contentsOf: url)).cases
        XCTAssertEqual(examples.count, 20)
        for example in examples { XCTAssertEqual(try tokenizer.encode(example.text), example.ids, example.text) }
    }

    func testUnicodeLineAndParagraphSeparatorsMatchHFPreTokenizer() throws {
        let tokenizer = try tokenizer()
        for separator in ["\u{2028}", "\u{2029}", "\u{00A0}"] {
            XCTAssertEqual(try tokenizer.encode("cat" + separator + "dog"), [101, 41163, 17835, 102])
        }
        for control in ["\u{85}", "\u{B}"] {
            XCTAssertEqual(try tokenizer.encode("cat" + control + "dog"), [101, 41163, 62039, 102])
        }
    }

    func testContextBoundaryPreserves126ContentTokens() throws {
        let tokenizer = try tokenizer()
        let ids = try tokenizer.encode(String(repeating: "猫", count: 126))
        XCTAssertEqual(ids.count, 128)
        XCTAssertEqual(ids.first, 101)
        XCTAssertEqual(ids.last, 102)
        for text in [String(repeating: "猫", count: 127), String(repeating: "猫🧬狗 ", count: 50)] {
            XCTAssertThrowsError(try tokenizer.encode(text)) { XCTAssertEqual($0 as? AIFrameError, .promptTooLong) }
        }
    }

    #if !SWIFT_PACKAGE
    func testCLIPCachePreservesDistinctUnicodeSpellings() async throws {
        guard let catalogURL = Bundle.main.url(forResource: "ai-models", withExtension: "json"),
              let directory = Bundle.main.resourceURL,
              FileManager.default.fileExists(atPath: directory.appendingPathComponent("text_model_int8.onnx").path) else {
            throw XCTSkip("Requires the isolated QA app's verified model fixtures.")
        }
        let model = try XCTUnwrap(AIModelCatalog.load(data: Data(contentsOf: catalogURL)).models.first(where: { $0.runtime == "onnx" }))
        let engine = try AIEmbeddingEngine(model: model, directory: directory)
        let composed = try await engine.encode(text: "é")
        let decomposed = try await engine.encode(text: "e\u{301}")
        XCTAssertEqual(composed.count, 512)
        XCTAssertNotEqual(composed, decomposed)
        let cached = try await engine.encode(text: "é")
        XCTAssertEqual(composed, cached)
    }
    #endif

    func testProjectionValidatesHeaderShapeOffsetsAndFiniteValues() throws {
        let header: [String: Any] = ["linear.weight": ["dtype": "F32", "shape": [512, 768], "data_offsets": [0, 512 * 768 * 4]]]
        let json = try JSONSerialization.data(withJSONObject: header)
        var size = UInt64(json.count).littleEndian
        var data = withUnsafeBytes(of: &size) { Data($0) }
        data.append(json)
        data.append(Data(count: 512 * 768 * 4))
        let projection = try AIClipProjection(data: data)
        XCTAssertThrowsError(try projection.project(hidden: Array(repeating: 1, count: 768), tokenCount: 1))
        XCTAssertThrowsError(try AIClipProjection(data: data.dropLast()))
        XCTAssertThrowsError(try AIClipProjection(data: Data(repeating: 255, count: 8)))
        // The row-major matrix maps each output row onto one input coordinate.
        let offset = 8 + json.count
        for index in 0..<512 {
            var one = Float(1).bitPattern.littleEndian
            withUnsafeBytes(of: &one) { data.replaceSubrange((offset + (index * 768 + index) * 4)..<(offset + (index * 768 + index) * 4 + 4), with: $0) }
        }
        let valid = try AIClipProjection(data: data)
        let output = try valid.project(hidden: Array(repeating: 1, count: 2 * 768), tokenCount: 2)
        XCTAssertEqual(output.count, 512)
        XCTAssertEqual(try AIFrameScoring.cosine(output, output), 1, accuracy: 0.00001)
        XCTAssertThrowsError(try valid.project(hidden: [Float.nan], tokenCount: 1))
    }
}
