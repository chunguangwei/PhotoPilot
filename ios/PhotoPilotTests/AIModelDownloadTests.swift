import CryptoKit
import Foundation
import XCTest
@testable import PhotoPilot

private final class ModelDownloadFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    let responder: @Sendable (URLRequest, Int) throws -> (HTTPURLResponse, Data)
    init(_ responder: @escaping @Sendable (URLRequest, Int) throws -> (HTTPURLResponse, Data)) { self.responder = responder }
    func response(_ request: URLRequest) throws -> (HTTPURLResponse, Data) {
        lock.lock()
        count += 1
        let index = count
        lock.unlock()
        return try responder(request, index)
    }
}

private final class ModelDownloadProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var fixture: ModelDownloadFixture?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (response, data) = try Self.fixture!.response(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

@MainActor
final class AIModelDownloadTests: XCTestCase {
    private func manager() throws -> (AIModelManager, AIModelDescriptor, URL) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let bytes = Data("abcdef".utf8)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let data = try JSONSerialization.data(withJSONObject: ["formatVersion": 1, "models": [[
            "id": "fixture", "revision": "fixed", "displayName": ["en": "Fixture", "zh-Hans": "测试"], "runtime": "litertlm", "embeddingDimension": 768,
            "files": [["name": "model.onnx", "role": "model", "size": 6, "sha256": digest,
                       "urls": ["international": ["https://models.example.com/model.onnx"], "domestic": []]]]
        ]]])
        let catalog = try AIModelCatalog.load(data: data)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ModelDownloadProtocol.self]
        return (AIModelManager(root: root, catalog: catalog, sessionConfiguration: configuration), catalog.models[0], root)
    }

    func testCompositeModelDownloadsAllFilesUsingFilePinnedURLs() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let bytes = Data("abcdef".utf8)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let roles = ["model", "text", "projection", "tokenizer"]
        let files: [[String: Any]] = roles.map { role in
            ["name": role + ".bin", "role": role, "size": bytes.count, "sha256": digest,
             "revision": "upstream-file-pin", "urls": ["international": ["https://models.example.com/resolve/upstream-file-pin/" + role + ".bin"], "domestic": []]]
        }
        let data = try JSONSerialization.data(withJSONObject: ["formatVersion": 1, "models": [[
            "id": "composite", "revision": "composition-cache-identity", "runtime": "onnx", "embeddingDimension": 512,
            "displayName": ["en": "Lite", "zh-Hans": "轻量版"], "files": files
        ]]])
        let catalog = try AIModelCatalog.load(data: data)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ModelDownloadProtocol.self]
        let manager = AIModelManager(root: root, catalog: catalog, sessionConfiguration: configuration)
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, _ in
            XCTAssertTrue(request.url!.path.contains("/resolve/upstream-file-pin/"))
            XCTAssertFalse(request.url!.path.contains("composition-cache-identity"))
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Length": "6"])!, bytes)
        }
        try await manager.download(id: "composite", source: .international, allowCellular: false, progress: { _ in })
        let model = catalog.models[0]
        let installed = await manager.isInstalled(model)
        XCTAssertTrue(installed)
        let directory = try await manager.acquire(model)
        XCTAssertTrue(directory.path.contains("composition-cache-identity"))
        for role in roles { XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent(role + ".bin")), bytes) }
        await manager.release(id: model.id)
    }

    private func seedPartial(root: URL, etag: String = "v1") throws {
        let directory = root.appendingPathComponent("fixture/fixed")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("abc".utf8).write(to: directory.appendingPathComponent("model.onnx.partial"))
        try etag.write(to: directory.appendingPathComponent("model.onnx.identity"), atomically: true, encoding: .utf8)
    }

    func testResumeWithMatchingETagAndRange() async throws {
        let (manager, model, root) = try manager()
        defer { try? FileManager.default.removeItem(at: root) }
        try seedPartial(root: root)
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, _ in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Range"), "bytes=3-5")
            XCTAssertEqual(request.value(forHTTPHeaderField: "If-Range"), "v1")
            return (HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                    headerFields: ["Content-Range": "bytes 3-5/6", "ETag": "v1"])!, Data("def".utf8))
        }
        try await manager.download(id: model.id, source: .international, allowCellular: false) { _ in }
        let installed = await manager.isInstalled(model)
        XCTAssertTrue(installed)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("fixture/fixed/model.onnx")), Data("abcdef".utf8))
    }

    func testChangedETagRestartsAtZero() async throws {
        let (manager, model, root) = try manager()
        defer { try? FileManager.default.removeItem(at: root) }
        try seedPartial(root: root)
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, index in
            let initial = index == 1
            XCTAssertEqual(request.value(forHTTPHeaderField: "Range"), initial ? "bytes=3-5" : "bytes=0-5")
            return (HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                                    headerFields: ["Content-Range": initial ? "bytes 3-5/6" : "bytes 0-5/6", "ETag": "v2"])!, Data((initial ? "def" : "abcdef").utf8))
        }
        try await manager.download(id: model.id, source: .international, allowCellular: false) { _ in }
        let installed = await manager.isInstalled(model)
        XCTAssertTrue(installed)
    }

    func testIgnoredRange200ReplacesPartialWithoutAppending() async throws {
        let (manager, model, root) = try manager()
        defer { try? FileManager.default.removeItem(at: root) }
        try seedPartial(root: root)
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, _ in
            (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["ETag": "v2"])!, Data("abcdef".utf8))
        }
        try await manager.download(id: model.id, source: .international, allowCellular: false) { _ in }
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("fixture/fixed/model.onnx")), Data("abcdef".utf8))
    }

    func testCorruptBytesNeverCreateInstalledMarker() async throws {
        let (manager, model, root) = try manager()
        defer { try? FileManager.default.removeItem(at: root) }
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, _ in
            (HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                             headerFields: ["Content-Range": "bytes 0-5/6", "ETag": "v1"])!, Data("abcdeg".utf8))
        }
        do {
            try await manager.download(id: model.id, source: .international, allowCellular: false) { _ in }
            XCTFail("Corrupt model bytes must fail SHA256 verification")
        } catch { XCTAssertEqual(error as? AIFrameError, .invalidDownload) }
        let installed = await manager.isInstalled(model)
        XCTAssertFalse(installed)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("fixture/fixed/verified").path))
    }

    func testMalformedContentRangeCannotAppendToPartial() async throws {
        let (manager, model, root) = try manager()
        defer { try? FileManager.default.removeItem(at: root) }
        try seedPartial(root: root)
        ModelDownloadProtocol.fixture = ModelDownloadFixture { request, _ in
            (HTTPURLResponse(url: request.url!, statusCode: 206, httpVersion: nil,
                             headerFields: ["Content-Range": "bytes 2-4/6", "ETag": "v1"])!, Data("def".utf8))
        }
        do {
            try await manager.download(id: model.id, source: .international, allowCellular: false) { _ in }
            XCTFail("Mismatching range must fail")
        } catch { XCTAssertEqual(error as? AIFrameError, .invalidDownload) }
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("fixture/fixed/model.onnx.partial")), Data("abc".utf8))
    }
}
