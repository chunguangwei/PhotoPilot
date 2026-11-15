import AMSMB2
import CoreData
import Foundation
import XCTest
import UIKit
@testable import PhotoPilot

private final class RemoteHTTPFixture: @unchecked Sendable {
    private let lock = NSLock()
    private var requests: [String] = []
    let body: Data
    let contentLength: String?
    let listing: Data?

    init(body: Data, contentLength: String? = nil, listing: Data? = nil) {
        self.listing = listing
        self.body = body
        self.contentLength = contentLength
    }

    func record(_ method: String) {
        lock.lock()
        requests.append(method)
        lock.unlock()
    }

    func methods() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }
}

private final class RemoteHTTPProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var fixture: RemoteHTTPFixture?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let fixture = Self.fixture else { return }
        fixture.record(request.httpMethod ?? "GET")
        let body = request.httpMethod == "PROPFIND" ? (fixture.listing ?? fixture.body) : fixture.body
        let headers = fixture.contentLength.map { ["Content-Length": $0] } ?? [:]
        let response = HTTPURLResponse(url: request.url!, statusCode: request.httpMethod == "PROPFIND" ? 207 : 200, httpVersion: nil, headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for chunk in stride(from: 0, to: body.count, by: 4096) {
            client?.urlProtocol(self, didLoad: body[chunk..<min(chunk + 4096, body.count)])
        }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

actor RemoteIndexFixture: RemoteReadableSource {
    private(set) var downloads = 0
    private(set) var requested: [String] = []
    let failing: Set<String>
    let files: [SourceAssetDescriptor]
    static let pixels = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAEUlEQVR4nGPQ6LmEFTEMLQkA7nNhgWN/1oEAAAAASUVORK5CYII=")!

    init(paths: [String] = ["family/cat.jpg", "family/holiday/dog.jpg", "family2/car.jpg"], failing: Set<String> = []) {
        self.failing = failing
        files = paths.map { SourceAssetDescriptor(remoteID: $0, revision: "v1", byteSize: Self.pixels.count, remoteTakenAtMillis: nil) }
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        SourceAssetPage(assets: files, nextCursor: nil, totalKnown: files.count)
    }

    func downloadRemote(remoteID: String, to destination: URL, maximumBytes: Int64) async throws {
        downloads += 1
        requested.append(remoteID)
        if failing.contains(remoteID) { throw RemoteMediaError.unavailable }
        guard maximumBytes >= Self.pixels.count else { throw RemoteMediaError.tooLarge }
        try Self.pixels.write(to: destination)
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload { throw RemoteMediaError.unavailable }
}

private final class RemoteSMBTransport: SmbTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var lengths: [Int] = []
    func requests() -> [Int] { lock.withLock { lengths } }
    func connectShare(name: String, encrypted: Bool) async throws {}
    func disconnectShare(gracefully: Bool) async throws {}
    func listShares(enumerateHidden: Bool) async throws -> [(name: String, comment: String)] { [] }
    func contentsOfDirectory(atPath path: String, recursive: Bool) async throws -> [[URLResourceKey: Any]] {
        [[.nameKey: "own.png", .isDirectoryKey: false, .fileSizeKey: RemoteIndexFixture.pixels.count]]
    }
    func readChunk(atPath path: String, offset: Int64, length: Int) async throws -> Data {
        lock.withLock { lengths.append(length) }
        let bytes = RemoteIndexFixture.pixels
        guard offset < bytes.count else { return Data() }
        return bytes[Int(offset)..<min(bytes.count, Int(offset) + length)]
    }
    func downloadItem(atPath path: String, to url: URL, progress: SMB2Manager.ReadProgressHandler) async throws { throw RemoteMediaError.unavailable }
}

@MainActor
final class RemoteProtocolTests: XCTestCase {
    private func library() throws -> (PersistenceController, String) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let sources = SourceRepository(container: controller.container)
        let id = try sources.ensureWebdavSource(endpointKey: "webdav://nas.invalid:443/photos", displayName: "Test NAS", recursive: true, directories: [""])
        return (controller, id)
    }

    func testMetadataIndexCreatesSelectableRemoteQueueWithoutOriginalsOrImportJobs() async throws {
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture()
        let repository = RemoteMediaRepository(controller: controller)
        _ = try await repository.index(sourceID: sourceID, adapter: fixture)
        let rows = try await repository.records(sourceID: sourceID)
        XCTAssertEqual(rows.count, 3)
        let downloads = await fixture.downloads
        XCTAssertEqual(downloads, 0)
        XCTAssertEqual(controller.container.managedObjectModel.entities.count, 17)
        let context = controller.container.viewContext
        for entity in ["Asset", "ImportJob", "ImportItem"] {
            XCTAssertEqual(try context.count(for: NSFetchRequest<NSManagedObject>(entityName: entity)), 0)
        }
        let frames = FrameManager(controller: controller)
        let frameID = try await frames.createFrame(name: "Online")
        try await frames.updateSettingsJSON(frameID: frameID, settingsJSON: FrameStorageMode.remote.applying(to: "{\"holdSeconds\":17}"))
        let selections = FrameRepository(controller: controller)
        let row = try XCTUnwrap(rows.first)
        let detail = try await frames.frameDetail(frameID: frameID)
        _ = try await selections.save(frameID: frameID, baseVersion: detail.selectionVersion, addIncludes: [SelectionRefEdit(assetId: nil, sourceAssetId: row.descriptor.sourceAssetID)])
        let queue = try await selections.buildPlayQueue(frameID: frameID)
        XCTAssertEqual(queue, [row.descriptor.runtimeID])
        let service = RemoteMediaService(controller: controller, cacheRoot: controller.storeURL.deletingLastPathComponent().appendingPathComponent("cache"),
            adapterFactory: { _ in fixture })
        let lease = try await service.acquire(runtimeID: row.descriptor.runtimeID)
        XCTAssertEqual(try Data(contentsOf: lease.fileURL), RemoteIndexFixture.pixels)
        await lease.release()
        let image = try await service.image(runtimeID: row.descriptor.runtimeID, width: 64, height: 64)
        XCTAssertEqual(image.cgImage?.width, 8)
        let fetched = await fixture.downloads
        XCTAssertEqual(fetched, 1)
        let source = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Source")).first)
        source.setValue("disconnected", forKey: "status")
        source.setValue(2, forKey: "epoch")
        try context.save()
        do { _ = try await service.acquire(runtimeID: row.descriptor.runtimeID); XCTFail() }
        catch { XCTAssertEqual(error as? RemoteMediaError, .revoked) }
        XCTAssertEqual(try context.count(for: NSFetchRequest<NSManagedObject>(entityName: "Asset")), 0)
    }

    func testMetadataIndexKeepsUnicodeByteDistinctFoldersSeparate() async throws {
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture(paths: ["caf\u{E9}/a.jpg", "cafe\u{301}/b.jpg"])
        let repository = RemoteMediaRepository(controller: controller)
        _ = try await repository.index(sourceID: sourceID, adapter: fixture)
        let context = controller.container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(format: "source.id == %@", sourceID)
        let folders = try context.fetch(request).filter { ($0.value(forKey: "rel_path") as? String ?? "") != "" }
        XCTAssertEqual(folders.count, 2)
        for folder in folders {
            let members = try await repository.records(collectionID: folder.value(forKey: "id") as? String, recursive: true)
            XCTAssertEqual(members.count, 1)
        }
    }

    func testAIRemoteCandidatesAndManualOverridesUseCanonicalSourceReferences() async throws {
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture()
        let repository = RemoteMediaRepository(controller: controller)
        _ = try await repository.index(sourceID: sourceID, adapter: fixture)
        let manager = FrameManager(controller: controller)
        let frameID = try await manager.createFrame(name: "AI online")
        try await manager.updateSettingsJSON(frameID: frameID, settingsJSON: FrameStorageMode.remote.applying(to: "{}"))
        let context = controller.container.viewContext
        let folders = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Collection"))
        let folderID = try XCTUnwrap(folders.first { $0.value(forKey: "rel_path") as? String == "family" }?.value(forKey: "id") as? String)
        let store = AIFrameStore(controller: controller)
        let configuration = AIFrameConfiguration(frameID: frameID, modelID: "fixture", modelRevision: "v1", collectionIDs: [folderID], rule: AIFrameRule(category: "cat"))
        let committed = try await store.configure(configuration)
        let candidates = try await store.candidates(configuration: committed)
        XCTAssertEqual(candidates.count, 2)
        let candidate = try XCTUnwrap(candidates.first)
        XCTAssertTrue(candidate.id.hasPrefix("remote:"))
        let match = AIFrameMatch(assetID: candidate.id, score: 0.9, decision: .matched, themes: ["pets"], contentVersion: candidate.contentHash)
        try await store.applyPreview(frameID: frameID, configuration: committed, matches: [match])
        let selections = FrameRepository(controller: controller)
        let resolution = try await selections.resolve(frameID: frameID)
        XCTAssertEqual(resolution.playable, [candidate.id])
        let detail = try await manager.frameDetail(frameID: frameID)
        _ = try await selections.save(frameID: frameID, baseVersion: detail.selectionVersion, addExcludes: [MediaReference(runtimeID: candidate.id).selection])
        try await store.applyPreview(frameID: frameID, configuration: committed, matches: [match])
        let excluded = try await selections.resolve(frameID: frameID)
        XCTAssertTrue(excluded.playable.isEmpty)
        let state = try await store.state(frameID: frameID)
        XCTAssertTrue(state?.manualExcludedAssetIDs.contains(candidate.id) == true)
        XCTAssertEqual(try context.count(for: NSFetchRequest<NSManagedObject>(entityName: "Asset")), 0)
    }

    func testNativeWebDAVCatalogToBoundedPhotoAndScopedSyncUsesRealProtocol() async throws {
        let (controller, sourceID) = try library()
        let xml = "<d:multistatus xmlns:d='DAV:'><d:response><d:href>/photos/own.png</d:href><d:propstat><d:prop><d:getetag>v1</d:getetag><d:getcontentlength>\(RemoteIndexFixture.pixels.count)</d:getcontentlength></d:prop></d:propstat></d:response></d:multistatus>"
        let fixture = RemoteHTTPFixture(body: RemoteIndexFixture.pixels, listing: Data(xml.utf8))
        RemoteHTTPProtocol.fixture = fixture
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RemoteHTTPProtocol.self]
        let client = WebDavClient(config: .init(baseURL: URL(string: "https://nas.invalid/photos")!, username: "", password: "", cleartextPermitted: false), session: URLSession(configuration: configuration))
        let adapter = WebDavSourceAdapter(client: client, scopeJSON: "{\"directories\":[\"\"]}")
        let service = RemoteMediaService(controller: controller, adapterFactory: { _ in adapter })
        _ = try await service.index(sourceID: sourceID)
        XCTAssertEqual(fixture.methods(), ["PROPFIND"])
        let rows = try await service.repository.records(sourceID: sourceID)
        let record = try XCTUnwrap(rows.first)
        let frame = try await FrameManager(controller: controller).createFrame(name: "WebDAV", storageMode: .remote)
        _ = try await FrameRepository(controller: controller).save(frameID: frame, baseVersion: 0, addIncludes: [MediaReference(runtimeID: record.descriptor.runtimeID).selection])
        let queue = try await FrameRepository(controller: controller).buildPlayQueue(frameID: frame)
        XCTAssertEqual(queue, [record.descriptor.runtimeID])
        _ = try await service.image(runtimeID: record.descriptor.runtimeID, width: 64, height: 64)
        XCTAssertEqual(fixture.methods(), ["PROPFIND", "GET"])
        XCTAssertEqual(try controller.container.viewContext.count(for: NSFetchRequest<NSManagedObject>(entityName: "Asset")), 0)
        let storage = FrameStorageService(controller: controller, media: service)
        try await storage.setMode(frameID: frame, mode: .local)
        await storage.wait(frameID: frame)
        let status = try await storage.state(frameID: frame)
        XCTAssertEqual(status.status, "complete")
        XCTAssertEqual(fixture.methods(), ["PROPFIND", "GET", "GET"])
    }

    func testNativeSMBCatalogToBoundedPNGUsesRangeReader() async throws {
        let (controller, _) = try library()
        let sourceID = try SourceRepository(container: controller.container).ensureSmbSource(endpointKey: "smb://nas.invalid:445/photos", displayName: "SMB", recursive: true, directories: [""])
        let transport = RemoteSMBTransport()
        let adapter = SmbSourceAdapter(config: .init(host: "nas.invalid", port: 445, share: "photos", username: "", password: ""), directories: [""], managerFactory: { transport })
        let service = RemoteMediaService(controller: controller, adapterFactory: { _ in adapter })
        _ = try await service.index(sourceID: sourceID)
        XCTAssertTrue(transport.requests().isEmpty)
        let rows = try await service.repository.records(sourceID: sourceID)
        let record = try XCTUnwrap(rows.first)
        _ = try await service.image(runtimeID: record.descriptor.runtimeID, width: 64, height: 64)
        XCTAssertTrue(transport.requests().allSatisfy { $0 <= 65_536 })
        XCTAssertFalse(transport.requests().isEmpty)
        XCTAssertEqual(try controller.container.viewContext.count(for: NSFetchRequest<NSManagedObject>(entityName: "Asset")), 0)
    }

    func testRealMultilingualRemotePreviewAndCanonicalConfirmation() async throws {
        guard let resources = Bundle.main.resourceURL,
              FileManager.default.fileExists(atPath: resources.appendingPathComponent("text_model_int8.onnx").path) else { throw XCTSkip("Requires isolated verified model fixtures") }
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture(paths: ["family/abstract.png"])
        let media = RemoteMediaService(controller: controller, adapterFactory: { _ in fixture })
        _ = try await media.index(sourceID: sourceID)
        let frameID = try await FrameManager(controller: controller).createFrame(name: "Remote AI", storageMode: .remote)
        let context = controller.container.viewContext
        let folders = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Collection"))
        let folderID = try XCTUnwrap(folders.first { $0.value(forKey: "rel_path") as? String == "family" }?.value(forKey: "id") as? String)
        let service = AIFrameService(controller: controller, media: media)
        let manager = await service.models
        let models = try XCTUnwrap(manager)
        let catalog = await models.catalog
        let model = try XCTUnwrap(catalog.models.first { $0.runtime == "onnx" })
        let directory = await models.directory(for: model)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        for file in model.files {
            let source = resources.appendingPathComponent(file.name)
            XCTAssertTrue(try AIModelManager.verify(source, file: file))
            try FileManager.default.copyItem(at: source, to: directory.appendingPathComponent(file.name))
        }
        let marker = model.files.map { "\($0.name):\($0.sha256)" }.joined(separator: "\n")
        try marker.write(to: directory.appendingPathComponent("verified"), atomically: true, encoding: .utf8)
        for category in ["a colorful abstract image", "彩色抽象图片"] {
            let config = AIFrameConfiguration(frameID: frameID, modelID: model.id, modelRevision: model.revision, collectionIDs: [folderID], rule: AIFrameRule(category: category, threshold: 0))
            let matches = try await service.preview(configuration: config, progress: { _ in })
            XCTAssertEqual(matches.count, 1)
            XCTAssertTrue(matches.first?.score.isFinite == true)
            XCTAssertTrue(matches.first?.assetID.hasPrefix("remote:") == true)
            try await service.savePreview(frameID: frameID, configuration: config, matches: matches)
        }
        context.reset()
        XCTAssertEqual(try context.count(for: NSFetchRequest<NSManagedObject>(entityName: "Asset")), 0)
        let selections = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection"))
        XCTAssertNotNil(selections.first?.value(forKey: "sourceAsset"))
        XCTAssertNil(selections.first?.value(forKey: "asset"))
        let localFile = controller.storeURL.deletingLastPathComponent().appendingPathComponent("local-reference-fixture.png")
        try RemoteIndexFixture.pixels.write(to: localFile)
        let localSource = try await media.references.connectFiles(root: localFile)
        let localFolders = try await AssetRepository(controller: controller).collections().filter { $0.sourceID == localSource }
        let localFrame = try await FrameManager(controller: controller).createFrame(name: "Local reference AI", storageMode: .local)
        let localConfiguration = AIFrameConfiguration(frameID: localFrame, modelID: model.id, modelRevision: model.revision, collectionIDs: localFolders.map(\.id), rule: AIFrameRule(category: "a colorful abstract image", threshold: 0))
        let localMatches = try await service.preview(configuration: localConfiguration, progress: { _ in })
        XCTAssertEqual(localMatches.count, 1)
        XCTAssertTrue(localMatches.first?.score.isFinite == true)
        try await service.savePreview(frameID: localFrame, configuration: localConfiguration, matches: localMatches)
        let localResolution = try await FrameRepository(controller: controller).resolve(frameID: localFrame)
        XCTAssertEqual(localResolution.playable, localMatches.map(\.assetID))
        context.reset()
        XCTAssertEqual(try context.count(for: NSFetchRequest<NSFetchRequestResult>(entityName: "Asset")), 0)
        XCTAssertEqual(try Data(contentsOf: localFile), RemoteIndexFixture.pixels)
    }

    func testRemoteCoverPreviewClearsImmediatelyOnAuthorityRevocation() async throws {
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture(paths: ["family/own.png"])
        let media = RemoteMediaService(controller: controller, adapterFactory: { _ in fixture })
        _ = try await media.index(sourceID: sourceID)
        let records = try await media.repository.records(sourceID: sourceID)
        let record = try XCTUnwrap(records.first)
        let environment = AppEnvironment(persistence: controller, remoteMedia: media)
        let preview = CoverPreviewViewController(environment: environment, relPath: record.descriptor.runtimeID, frameName: "Family", photoCount: 1, onConfirm: {})
        preview.loadViewIfNeeded()
        func images(_ view: UIView) -> [UIImageView] { (view as? UIImageView).map { [$0] } ?? view.subviews.flatMap(images) }
        let views = images(preview.view)
        for _ in 0..<100 where views.allSatisfy({ $0.image == nil }) { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertTrue(views.contains { $0.image != nil })
        NotificationCenter.default.post(name: .remoteMediaChanged, object: nil, userInfo: ["revoked": true])
        XCTAssertTrue(views.allSatisfy { $0.image == nil })
    }

    func testPlayerSkipsPermanentlyUnavailableNextPhotoAndDisplaysFollowingPhoto() async throws {
        let (controller, sourceID) = try library()
        let fixture = RemoteIndexFixture(paths: ["family/a.png", "family/b.png", "family/c.png"], failing: ["family/b.png"])
        let media = RemoteMediaService(controller: controller, adapterFactory: { _ in fixture })
        _ = try await media.index(sourceID: sourceID)
        let rows = try await media.repository.records(sourceID: sourceID).sorted { $0.descriptor.remoteKey < $1.descriptor.remoteKey }
        let manager = FrameManager(controller: controller)
        let frameID = try await manager.createFrame(name: "Skip unavailable", storageMode: .remote)
        try await manager.updateOrderMode(frameID: frameID, orderMode: "manual")
        _ = try await FrameRepository(controller: controller).save(frameID: frameID, baseVersion: 0,
            addIncludes: rows.map { MediaReference(runtimeID: $0.descriptor.runtimeID).selection }, manualOrderRefs: rows.map { $0.descriptor.runtimeID })
        let saved = try await manager.frameDetail(frameID: frameID)
        _ = try await FrameRepository(controller: controller).save(frameID: frameID, baseVersion: saved.selectionVersion, manualOrderRefs: rows.map { $0.descriptor.runtimeID })
        let expected = rows.map { $0.descriptor.runtimeID }
        let actual = try await FrameRepository(controller: controller).buildPlayQueue(frameID: frameID)
        XCTAssertEqual(actual, expected)
        try await manager.updateSettingsJSON(frameID: frameID, settingsJSON: "{\"mediaStorageMode\":\"remote\",\"holdSeconds\":3,\"transition\":\"none\"}")
        let player = PlayerViewController(environment: AppEnvironment(persistence: controller, remoteMedia: media), frameID: frameID)
        player.loadViewIfNeeded()
        player.viewWillAppear(false)
        defer { PlayerViewController.dismissActiveIfAny() }
        var shownFollowing = false
        for _ in 0..<1400 {
            let values = Array(Mirror(reflecting: player).children)
            let queue = values.first { $0.label == "queue" }?.value as? [String] ?? []
            let index = values.first { $0.label == "index" }?.value as? Int ?? 0
            if queue.indices.contains(index), queue[index] == rows[2].descriptor.runtimeID { shownFollowing = true; break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(shownFollowing)
        let requests = await fixture.requested
        XCTAssertEqual(requests.filter { $0 == "family/b.png" }.count, 1)
        XCTAssertTrue(requests.contains("family/c.png"))
    }

    func testPreviouslyIndexedVideoCannotBecomeRemotePlayable() async throws {
        let (controller, sourceID) = try library()
        let context = controller.container.viewContext
        let source = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Source", id: sourceID))
        let row = NSEntityDescription.insertNewObject(forEntityName: "SourceAsset", into: context)
        for (key, attribute) in row.entity.attributesByName where !attribute.isOptional {
            row.setValue(attribute.defaultValue ?? (attribute.attributeType == .stringAttributeType ? "" : 0), forKey: key)
        }
        row.setValue("legacy-video", forKey: "id")
        row.setValue(source, forKey: "source")
        row.setValue("old/movie.mp4", forKey: "remote_key")
        row.setValue("v1", forKey: "revision")
        row.setValue("accessible", forKey: "access_state")
        try context.save()
        let record = try await RemoteMediaRepository(controller: controller).record(id: "legacy-video")
        XCTAssertNil(record)
    }

    func testWebDAVHrefPreservesEncodedSlashAndUnicodeByteBoundaries() {
        let path = WebDavMultistatusParser.relativePath(href: "/root/family%2Fsecret/cat.jpg", basePath: "/root")
        XCTAssertFalse(RemoteMediaPath.contains(folder: "family", remoteKey: path, recursive: true))
        let differentBase = WebDavMultistatusParser.relativePath(href: "/cafe\u{301}/private.jpg", basePath: "/caf\u{E9}")
        XCTAssertNotEqual(differentBase, "private.jpg")
    }

    func testWebDAVUnknownAndUnderstatedLengthRejectBeforeWritingPastReservation() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RemoteHTTPProtocol.self]
        let session = URLSession(configuration: configuration)
        let client = WebDavClient(config: .init(baseURL: URL(string: "https://nas.invalid/photos")!, username: "", password: "", cleartextPermitted: false), session: session)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        for length in [nil, "4"] as [String?] {
            let fixture = RemoteHTTPFixture(body: Data(repeating: 42, count: 131_072), contentLength: length)
            RemoteHTTPProtocol.fixture = fixture
            let file = root.appendingPathComponent(UUID().uuidString)
            do {
                try await client.downloadBounded(path: "cat.jpg", to: file, maximumBytes: 32)
                XCTFail("Over-limit data must not be retained")
            } catch { XCTAssertEqual(error as? RemoteMediaError, .tooLarge) }
            XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
            XCTAssertEqual(fixture.methods(), ["GET"])
        }
    }
}
