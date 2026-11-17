import CryptoKit
import Network
import XCTest
@testable import PhotoPilot

final class AIBackgroundDownloadTests: XCTestCase {
    func testPersistedIntentAndGenerationRejectLateCompletionAfterDelete() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = AIModelDownloadJournal(root: root)
        let state = AIModelDownloadState(modelID: "fixture", revision: "fixed", source: .international, allowCellular: false, totalBytes: 6)
        try await store.put(state)
        let reopened = try await AIModelDownloadJournal(root: root).get("fixture")
        XCTAssertEqual(reopened?.generation, state.generation)
        XCTAssertEqual(reopened?.allowCellular, false)
        try await store.remove("fixture")
        let accepted = try await store.accepts(modelID: "fixture", generation: state.generation)
        XCTAssertFalse(accepted)
    }
    func testOldGenerationCannotPauseOrCompleteReplacementIntent() async throws {
        let store = AIModelDownloadJournal(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let old = AIModelDownloadState(modelID: "fixture", revision: "old", source: .international, allowCellular: false, totalBytes: 6)
        try await store.put(old)
        try await store.remove(old.modelID)
        var replacement = AIModelDownloadState(modelID: "fixture", revision: "new", source: .international, allowCellular: false, totalBytes: 6)
        replacement.status = "downloading"
        try await store.put(replacement)
        try await store.update(old.modelID, generation: old.generation) { $0.status = "paused" }
        try await store.update(old.modelID, generation: old.generation) { $0.status = "complete" }
        let current = try await store.get(old.modelID)
        XCTAssertEqual(current?.generation, replacement.generation)
        XCTAssertEqual(current?.status, "downloading")
    }
}

private actor ModelStartBarrier {
    private var continuation: CheckedContinuation<Void, Never>?
    private var entered = false
    func wait() async {
        entered = true
        await withCheckedContinuation { continuation = $0 }
    }
    func reached() -> Bool { entered }
    func release() { continuation?.resume(); continuation = nil }
}

private final class ModelHTTPServer: @unchecked Sendable {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "ai.model.transfer.fixture")
    private let body: Data
    private let delay: Double
    private let lock = NSLock()
    private var received = 0
    var count: Int { lock.lock(); defer { lock.unlock() }; return received }
    init(body: Data, delay: Double = 0) throws {
        self.body = body
        self.delay = delay
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }
    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self] state in
                if case .ready = state, let port = self?.listener.port { continuation.resume(returning: port.rawValue) }
                if case .failed(let error) = state { continuation.resume(throwing: error) }
            }
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else { return }
                connection.start(queue: queue)
                connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { _, _, _, _ in
                    self.lock.lock(); self.received += 1; self.lock.unlock()
                    let header = Data("HTTP/1.1 200 OK\r\nContent-Length: \(self.body.count)\r\nETag: fixture\r\nConnection: close\r\n\r\n".utf8)
                    self.queue.asyncAfter(deadline: .now() + self.delay) {
                        connection.send(content: header + self.body, completion: .contentProcessed { _ in connection.cancel() })
                    }
                }
            }
            listener.start(queue: queue)
        }
    }
    func stop() { listener.cancel() }
}

extension AIBackgroundDownloadTests {
    private func transferCatalog(port: UInt16, body: Data) -> AIModelCatalog {
        let file = AIModelDescriptor.File(name: "model.bin", role: "model", size: Int64(body.count),
            sha256: SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined(),
            urls: .init(international: ["http://127.0.0.1:\(port)/global"], domestic: ["http://127.0.0.1:\(port)/domestic"]))
        return AIModelCatalog(formatVersion: 1, models: [AIModelDescriptor(id: "fixture", displayName: ["en": "Fixture"], runtime: "litertlm", revision: "fixed", files: [file], embeddingDimension: 768, defaultThreshold: 0.2)])
    }

    func testPauseBetweenAuthorizationAndTaskRegistrationDoesNotStartRequest() async throws {
        let body = Data("model transfer fixture".utf8)
        let server = try ModelHTTPServer(body: body)
        let port = try await server.start()
        defer { server.stop() }
        let catalog = transferCatalog(port: port, body: body)
        let gate = ModelStartBarrier()
        let downloader = AIBackgroundModelDownloads(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString),
            catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString, beforeStart: { await gate.wait() })
        let work = Task { try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in }) }
        for _ in 0..<200 {
            if await gate.reached() { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let reached = await gate.reached()
        XCTAssertTrue(reached)
        await downloader.pause("fixture")
        await gate.release()
        do { try await work.value; XCTFail("Paused startup must stop") } catch is CancellationError { }
        let requests = await downloader.activeRequests()
        XCTAssertTrue(requests.isEmpty)
        XCTAssertEqual(server.count, 0)
        let states = await downloader.states()
        XCTAssertEqual(states.first?.status, "paused")
    }

    func testBackgroundSessionDownloadsAndVerifiesActualHTTPFile() async throws {
        let body = Data(repeating: 0x3d, count: 131072)
        let server = try ModelHTTPServer(body: body)
        let port = try await server.start()
        defer { server.stop() }
        let catalog = transferCatalog(port: port, body: body)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let downloader = AIBackgroundModelDownloads(root: root, catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString)
        try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in })
        XCTAssertEqual(server.count, 1)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("fixture/fixed/model.bin")), body)
        let state = try await AIModelDownloadJournal(root: root).get("fixture")
        XCTAssertEqual(state?.status, "complete")
    }
}

extension AIBackgroundDownloadTests {
    func testCompletePartialAndPersistedInboundRecoverWithoutHTTPRequests() async throws {
        let body = Data("verified fixture".utf8)
        let server = try ModelHTTPServer(body: body)
        let port = try await server.start()
        defer { server.stop() }
        let catalog = transferCatalog(port: port, body: body)
        for inbound in [false, true] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let directory = root.appendingPathComponent("fixture/fixed")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var state = AIModelDownloadState(modelID: "fixture", revision: "fixed", source: .international, allowCellular: false, totalBytes: Int64(body.count))
            state.status = "downloading"
            try await AIModelDownloadJournal(root: root).put(state)
            if inbound {
                let incoming = root.appendingPathComponent("incoming")
                try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
                let file = incoming.appendingPathComponent(UUID().uuidString)
                let token = AITransferToken(modelID: "fixture", revision: "fixed", file: "model.bin", generation: state.generation, offset: 0, urlIndex: 0)
                let record = AIInboundDownload(token: token, url: "http://127.0.0.1:\(port)/global", status: 200, headers: [:])
                try body.write(to: file)
                try JSONEncoder().encode(record).write(to: file.appendingPathExtension("json"))
            } else { try body.write(to: directory.appendingPathComponent("model.bin.partial")) }
            let downloader = AIBackgroundModelDownloads(root: root, catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString)
            try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in })
            XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("model.bin")), body)
            let reopened = try await AIModelDownloadJournal(root: root).get("fixture")
            XCTAssertEqual(reopened?.status, "complete")
        }
        XCTAssertEqual(server.count, 0)
    }
}

extension AIBackgroundDownloadTests {
    func testChangedDownloadPreferencesDiscardIncompatibleResumeRequest() async throws {
        let body = Data("model fixture".utf8)
        let server = try ModelHTTPServer(body: body, delay: 1)
        let port = try await server.start()
        defer { server.stop() }
        let catalog = transferCatalog(port: port, body: body)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let directory = root.appendingPathComponent("fixture/fixed")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let resume = try PropertyListSerialization.data(fromPropertyList: ["NSURLSessionResumeInfoVersion": 2], format: .xml, options: 0)
        try resume.write(to: directory.appendingPathComponent("model.bin.resume"))
        let old = AIModelResumePolicy(url: "http://127.0.0.1:\(port)/domestic", allowCellular: true)
        try JSONEncoder().encode(old).write(to: directory.appendingPathComponent("model.bin.resume-policy"))
        let downloader = AIBackgroundModelDownloads(root: root, catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString)
        let work = Task { try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in }) }
        var request: URLRequest?
        for _ in 0..<200 {
            request = await downloader.activeRequests().first
            if request != nil { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(request?.url?.path, "/global")
        XCTAssertEqual(request?.allowsCellularAccess, false)
        try await work.value
        XCTAssertEqual(server.count, 1)
    }
}

extension AIBackgroundDownloadTests {
    func testPersistedRangeBodyReplayTruncatesInterruptedAppendAndAcceptsCompletePartial() async throws {
        let body = Data("abcdef".utf8)
        let catalog = transferCatalog(port: 1, body: body)
        for partial in ["abcd", "abcdef"] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let directory = root.appendingPathComponent("fixture/fixed")
            let incoming = root.appendingPathComponent("incoming")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
            try Data(partial.utf8).write(to: directory.appendingPathComponent("model.bin.partial"))
            try "fixed-etag".write(to: directory.appendingPathComponent("model.bin.identity"), atomically: true, encoding: .utf8)
            var state = AIModelDownloadState(modelID: "fixture", revision: "fixed", source: .international, allowCellular: false, totalBytes: 6)
            state.status = "downloading"
            try await AIModelDownloadJournal(root: root).put(state)
            let record = AIInboundDownload(token: AITransferToken(modelID: "fixture", revision: "fixed", file: "model.bin", generation: state.generation, offset: 3, urlIndex: 0),
                url: "http://127.0.0.1:1/global", status: 206, headers: ["Content-Range": "bytes 3-5/6", "ETag": "fixed-etag"])
            for _ in 0..<2 {
                let location = incoming.appendingPathComponent(UUID().uuidString)
                try Data("def".utf8).write(to: location)
                try JSONEncoder().encode(record).write(to: location.appendingPathExtension("json"))
            }
            let downloader = AIBackgroundModelDownloads(root: root, catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString)
            await downloader.restore()
            XCTAssertEqual(try Data(contentsOf: directory.appendingPathComponent("model.bin")), body)
            let recovered = try await AIModelDownloadJournal(root: root).get("fixture")
            XCTAssertEqual(recovered?.status, "complete")
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: incoming.path).count, 0)
        }
    }
}

extension AIBackgroundDownloadTests {
    func testRestoredSecondObserverCoalescesExistingFileTask() async throws {
        let body = Data("model fixture".utf8)
        let server = try ModelHTTPServer(body: body, delay: 0.5)
        let port = try await server.start()
        defer { server.stop() }
        let catalog = transferCatalog(port: port, body: body)
        let downloader = AIBackgroundModelDownloads(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString),
            catalog: catalog, sessionIdentifier: "dev.photopilot.test." + UUID().uuidString)
        let first = Task { try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in }) }
        for _ in 0..<200 {
            if !(await downloader.activeRequests()).isEmpty { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        let second = Task { try await downloader.download(model: catalog.models[0], source: .international, allowCellular: false, progress: { _ in }) }
        try await first.value
        try await second.value
        XCTAssertEqual(server.count, 1)
    }
}
