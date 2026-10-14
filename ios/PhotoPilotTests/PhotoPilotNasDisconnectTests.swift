import CoreData
import XCTest
@testable import PhotoPilot

final class PhotoPilotNasDisconnectTests: XCTestCase {
    var controller: PersistenceController!
    var repository: AssetRepository!
    var sources: SourceRepository!

    private var originalsRoot: URL {
        controller.storeURL.deletingLastPathComponent()
            .appendingPathComponent("originals", isDirectory: true)
    }

    override func setUp() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        controller = PersistenceController(
            storeURL: directory.appendingPathComponent("library.db")
        )
        repository = AssetRepository(controller: controller)
        sources = SourceRepository(container: controller.container)
    }

    @discardableResult
    private func insert(_ entityName: String, _ values: [String: Any?]) -> NSManagedObject {
        let object = NSEntityDescription.insertNewObject(
            forEntityName: entityName,
            into: controller.container.viewContext
        )
        for (key, value) in values {
            if let value {
                object.setValue(value, forKey: key)
            }
        }
        return object
    }

    private func seedNasSource() throws {
        let context = controller.container.viewContext
        let source = insert("Source", [
            "id": "sw1",
            "type": "webdav",
            "display_name": "nas.local",
            "endpoint_key": "webdav://nas.local:443/photos",
            "credential_ref": "credential",
            "capability_flags": 83,
            "retention_policy": "user_managed",
            "status": "active",
            "epoch": 1,
            "settings_json": "{\"directories\":[\"photos\"],\"recursive\":false}",
            "created_at": 0,
            "updated_at": 0,
        ])
        let sourceAsset = insert("SourceAsset", [
            "id": "sa1",
            "remote_key": "photos/a.jpg",
            "revision": "etag-1",
            "byte_size": 5,
            "last_seen_at": 0,
            "access_state": "accessible",
            "created_at": 0,
            "updated_at": 0,
        ])
        sourceAsset.setValue(source, forKey: "source")
        let asset = insert("Asset", [
            "id": "a1",
            "content_hash": "sha256:test",
            "byte_size": 5,
            "format": "jpeg",
            "pixel_width": 1,
            "pixel_height": 1,
            "exif_orientation": 1,
            "orientation_class": "square",
            "captured_at_source": "unknown",
            "imported_at": 0,
            "original_rel_path": "a1.jpg",
            "readiness": "ready",
            "privacy_excluded": false,
            "created_at": 0,
            "updated_at": 0,
        ])
        let origin = insert("AssetOrigin", [
            "imported_at": 0,
            "policy_state": "allowed",
            "origin_state": "active",
            "created_at": 0,
            "updated_at": 0,
        ])
        origin.setValue(asset, forKey: "asset")
        origin.setValue(sourceAsset, forKey: "sourceAsset")
        let job = insert("ImportJob", [
            "id": "j1",
            "scope_json": "{}",
            "epoch_at_create": 1,
            "counts_json": "{}",
            "state": "running",
            "created_at": 0,
            "updated_at": 0,
        ])
        job.setValue(source, forKey: "source")
        try FileManager.default.createDirectory(at: originalsRoot, withIntermediateDirectories: true)
        try Data([1, 2, 3, 4, 5]).write(to: originalsRoot.appendingPathComponent("a1.jpg"))
        try context.save()
    }

    private func fetch(_ entity: String, _ key: String, _ value: String) throws -> NSManagedObject? {
        let context = controller.container.viewContext
        context.refreshAllObjects()
        let request = NSFetchRequest<NSManagedObject>(entityName: entity)
        request.predicate = NSPredicate(format: "id == %@", value)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private var fixtureRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/smoke-v1")
    }

    func testSourceAssetUpsertIsFetchFirst() async throws {
        try seedNasSource()
        let adapter = LocalFilesFixtureAdapter(rootURL: fixtureRoot)
        let coordinator = ImportCoordinator(controller: controller, adapter: adapter)
        let countRequest = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
        let seededCount = try controller.container.viewContext.count(for: countRequest)
        let first = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: first.id)
        controller.container.viewContext.refreshAllObjects()
        let afterFirst = try controller.container.viewContext.count(for: countRequest)
        let second = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: second.id)
        controller.container.viewContext.refreshAllObjects()
        let afterSecond = try controller.container.viewContext.count(for: countRequest)
        XCTAssertGreaterThan(afterFirst, seededCount)
        XCTAssertEqual(afterSecond, afterFirst)
    }

    func testDeltaSyncSkipsUnchangedItems() async throws {
        try seedNasSource()
        let adapter = LocalFilesFixtureAdapter(rootURL: fixtureRoot)
        let coordinator = ImportCoordinator(controller: controller, adapter: adapter)
        let first = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: first.id)
        let second = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: second.id)
        let context = controller.container.viewContext
        context.refreshAllObjects()
        let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
        request.predicate = NSPredicate(format: "job.id == %@", second.id)
        XCTAssertEqual(try context.count(for: request), 0)
    }

    func testMirrorEnableResetsMissingCounts() throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let sourceAsset = try XCTUnwrap(fetch("SourceAsset", "id", "sa1"))
        sourceAsset.setValue(2, forKey: "missing_scan_count")
        sourceAsset.setValue("missing", forKey: "access_state")
        try context.save()

        try sources.setWebdavMirrorDelete(sourceID: "sw1", enabled: true)

        context.refreshAllObjects()
        let updated = try XCTUnwrap(fetch("SourceAsset", "id", "sa1"))
        XCTAssertEqual(updated.value(forKey: "missing_scan_count") as? Int, 0)
        XCTAssertEqual(updated.value(forKey: "access_state") as? String, "missing")
        XCTAssertTrue(try sources.webdavMirrorDeleteEnabled(sourceID: "sw1"))

        try sources.setWebdavMirrorDelete(sourceID: "sw1", enabled: false)
        context.refreshAllObjects()
        let afterDisable = try XCTUnwrap(fetch("SourceAsset", "id", "sa1"))
        XCTAssertEqual(afterDisable.value(forKey: "missing_scan_count") as? Int, 0)
    }

    func testDisconnectKeepCopiesThenReconnect() async throws {
        try seedNasSource()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true)

        let source = try XCTUnwrap(fetch("Source", "id", "sw1"))
        XCTAssertEqual(source.value(forKey: "status") as? String, "disconnected")
        XCTAssertNotNil(try fetch("SourceAsset", "id", "sa1"))
        let asset = try XCTUnwrap(fetch("Asset", "id", "a1"))
        XCTAssertEqual(asset.value(forKey: "readiness") as? String, "ready")
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: originalsRoot.appendingPathComponent("a1.jpg").path
            )
        )
        let context = controller.container.viewContext
        let originRequest = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        originRequest.predicate = NSPredicate(format: "asset.id == %@", "a1")
        let origin = try XCTUnwrap(context.fetch(originRequest).first)
        XCTAssertEqual(origin.value(forKey: "origin_state") as? String, "detached")
        XCTAssertNotNil(origin.value(forKey: "sourceAsset"))
        XCTAssertNil(try fetch("ImportJob", "id", "j1"))

        let revived = try sources.ensureWebdavSource(
            endpointKey: "webdav://nas.local:443/photos",
            displayName: "nas.local",
            recursive: true,
            directories: ["photos"]
        )
        XCTAssertEqual(revived, "sw1")
        let revivedSource = try XCTUnwrap(fetch("Source", "id", "sw1"))
        XCTAssertEqual(revivedSource.value(forKey: "status") as? String, "active")
        XCTAssertEqual(
            revivedSource.value(forKey: "settings_json") as? String,
            "{\"directories\":[\"photos\"],\"recursive\":true}"
        )
        let allSources = NSFetchRequest<NSManagedObject>(entityName: "Source")
        XCTAssertEqual(try context.count(for: allSources), 1)
    }

    func testDisconnectDeleteCopies() async throws {
        try seedNasSource()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: false)

        XCTAssertNil(try fetch("Source", "id", "sw1"))
        XCTAssertNil(try fetch("SourceAsset", "id", "sa1"))
        XCTAssertNil(try fetch("Asset", "id", "a1"))
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: originalsRoot.appendingPathComponent("a1.jpg").path
            )
        )
        let context = controller.container.viewContext
        let tombstones = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
        tombstones.predicate = NSPredicate(format: "content_hash == %@", "sha256:test")
        let tombstone = try XCTUnwrap(context.fetch(tombstones).first)
        XCTAssertEqual(tombstone.value(forKey: "reason") as? String, "user_delete")
        XCTAssertEqual(
            tombstone.value(forKey: "source_fingerprint") as? String,
            "webdav://nas.local:443/photos"
        )
        XCTAssertEqual(tombstone.value(forKey: "remote_id") as? String, "photos/a.jpg")
    }
}
