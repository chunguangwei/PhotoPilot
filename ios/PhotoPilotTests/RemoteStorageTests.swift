import CoreData
import Foundation
import XCTest
@testable import PhotoPilot

@MainActor
final class RemoteStorageTests: XCTestCase {
    private func setupFrame() async throws -> (PersistenceController, RemoteMediaService, FrameStorageService, RemoteIndexFixture, String, RemoteMediaRecord) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let sourceID = try SourceRepository(container: controller.container).ensureWebdavSource(
            endpointKey: "webdav://nas.invalid:443/photos", displayName: "NAS", recursive: true, directories: [""])
        let fixture = RemoteIndexFixture()
        let media = RemoteMediaService(controller: controller, cacheRoot: root.appendingPathComponent("cache"), adapterFactory: { _ in fixture })
        _ = try await media.index(sourceID: sourceID)
        let records = try await media.repository.records(sourceID: sourceID)
        let record = try XCTUnwrap(records.first { $0.descriptor.remoteKey == "family/cat.jpg" } ?? records.first)
        let manager = FrameManager(controller: controller)
        let id = try await manager.createFrame(name: "Online", storageMode: .remote)
        let detail = try await manager.frameDetail(frameID: id)
        _ = try await FrameRepository(controller: controller).save(frameID: id, baseVersion: detail.selectionVersion,
            addIncludes: [SelectionRefEdit(assetId: nil, sourceAssetId: record.descriptor.sourceAssetID)])
        return (controller, media, FrameStorageService(controller: controller, media: media), fixture, id, record)
    }

    func testOnlySelectedPhotoSyncsAndCanReleaseThenRehydrateSameAssetIdentity() async throws {
        let (controller, media, storage, fixture, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let cards = try await FrameManager(controller: controller).frameCards()
        XCTAssertEqual(cards.first { $0.id == frameID }?.storageMode, .local)
        let state = try await storage.state(frameID: frameID)
        XCTAssertEqual(state.status, "complete")
        XCTAssertEqual(state.completed, 1)
        let downloaded = await fixture.downloads
        XCTAssertEqual(downloaded, 1)
        let context = controller.container.viewContext
        context.reset()
        let assets = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset"))
        XCTAssertEqual(assets.count, 1)
        let asset = try XCTUnwrap(assets.first)
        let assetID = try XCTUnwrap(asset.value(forKey: "id") as? String)
        let relative = try XCTUnwrap(asset.value(forKey: "original_rel_path") as? String)
        let file = controller.storeURL.deletingLastPathComponent().appendingPathComponent("originals").appendingPathComponent(relative)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        let selections = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection"))
        XCTAssertEqual(selections.first?.value(forKeyPath: "sourceAsset.id") as? String, record.descriptor.sourceAssetID)
        XCTAssertNil(selections.first?.value(forKey: "asset"))
        try await storage.setMode(frameID: frameID, mode: .remote)
        let onlineCards = try await FrameManager(controller: controller).frameCards()
        XCTAssertEqual(onlineCards.first { $0.id == frameID }?.storageMode, .remote)
        let release = try await storage.releasable(frameID: frameID)
        XCTAssertEqual(release.bytes, RemoteIndexFixture.pixels.count)
        XCTAssertEqual(release.assetIDs, [assetID])
        try await storage.releaseCopies(frameID: frameID, verifyLegacy: false)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        context.reset()
        let retained = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset")).first)
        XCTAssertEqual(retained.value(forKey: "id") as? String, assetID)
        XCTAssertEqual(retained.value(forKey: "readiness") as? String, "pending")
        XCTAssertEqual(try context.count(for: NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")), 0)
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let restored = try await media.repository.record(id: record.descriptor.sourceAssetID)
        XCTAssertEqual(restored?.localAssetID, assetID)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertEqual(try Data(contentsOf: file), RemoteIndexFixture.pixels)
        let count = await fixture.downloads
        XCTAssertEqual(count, 2)
    }

    func testDisconnectKeepsAuthorizedLocalCopyAndReconnectRestoresActiveOrigin() async throws {
        let (controller, media, storage, _, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        try await AssetRepository(controller: controller).disconnectSource(sourceID: record.descriptor.sourceID, keepCopies: true)
        let kept = await media.authorized(runtimeID: record.descriptor.runtimeID, mode: .local)
        let remote = await media.authorized(runtimeID: record.descriptor.runtimeID, mode: .remote)
        XCTAssertTrue(kept)
        XCTAssertFalse(remote)
        let localQueue = try await FrameRepository(controller: controller).buildPlayQueue(frameID: frameID)
        XCTAssertEqual(localQueue.count, 1)
        _ = try SourceRepository(container: controller.container).ensureWebdavSource(endpointKey: "webdav://nas.invalid:443/photos", displayName: "NAS", recursive: true, directories: [""])
        _ = try await media.index(sourceID: record.descriptor.sourceID)
        await storage.start(frameID: frameID)
        await storage.wait(frameID: frameID)
        let context = controller.container.viewContext
        context.reset()
        let origins = try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin"))
        XCTAssertEqual(origins.first?.value(forKey: "origin_state") as? String, "active")
        let restored = await media.authorized(runtimeID: record.descriptor.runtimeID, mode: .local)
        XCTAssertTrue(restored)
    }

    func testPrivacyHideRemovesRemoteFeatureAndPreviewAliases() async throws {
        let (controller, media, storage, _, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let localRecord = try await media.repository.record(id: record.descriptor.sourceAssetID)
        let local = try XCTUnwrap(localRecord)
        let assetID = try XCTUnwrap(local.localAssetID)
        let store = AIFrameStore(controller: controller)
        try await store.cacheVector([1, 0], assetID: record.descriptor.runtimeID, version: SHA256Hex.digest(data: Data(record.descriptor.identity.utf8)) + ":test")
        try await AssetRepository(controller: controller).hideAssetGlobally(id: assetID)
        let context = controller.container.viewContext
        context.reset()
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key BEGINSWITH %@", AIFrameStore.cachePrefix + record.descriptor.runtimeID + ".")
        XCTAssertEqual(try context.count(for: request), 0)
        let allowed = await media.authorized(runtimeID: record.descriptor.runtimeID, mode: .local)
        XCTAssertFalse(allowed)
    }

    func testLegacyNASAssetSelectionBecomesCanonicalWithoutLosingAIOwnership() async throws {
        let (controller, media, storage, _, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let localRecord = try await media.repository.record(id: record.descriptor.sourceAssetID)
        let assetID = try XCTUnwrap(localRecord?.localAssetID)
        let context = controller.container.viewContext
        context.reset()
        let asset = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Asset", id: assetID))
        let frame = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Frame", id: frameID))
        frame.setValue("{}", forKey: "settings_json")
        frame.setValue(asset, forKey: "coverAsset")
        let selection = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")).first)
        selection.setValue(nil, forKey: "sourceAsset")
        selection.setValue(asset, forKey: "asset")
        selection.setValue(assetID, forKey: "ref_key")
        let folderID = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Collection")).first?.value(forKey: "id") as? String)
        var state = AIFrameState(configuration: AIFrameConfiguration(frameID: frameID, modelID: "fixture", modelRevision: "v1", collectionIDs: [folderID], rule: AIFrameRule(category: "cat")))
        state.ownedAssetIDs = [assetID]
        let row = NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
        row.setValue(AIFrameStore.prefix + frameID, forKey: "key")
        row.setValue(String(decoding: try JSONEncoder().encode(state), as: UTF8.self), forKey: "value_json")
        row.setValue(0, forKey: "created_at")
        row.setValue(0, forKey: "updated_at")
        try context.save()
        try await storage.setMode(frameID: frameID, mode: .remote)
        context.reset()
        let canonical = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")).first)
        XCTAssertNil(canonical.value(forKey: "asset"))
        let migratedFrame = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Frame", id: frameID))
        XCTAssertNil(migratedFrame.value(forKey: "coverAsset"))
        let settings = try XCTUnwrap(JSONSerialization.jsonObject(with: Data((migratedFrame.value(forKey: "settings_json") as? String ?? "{}").utf8)) as? [String: Any])
        XCTAssertEqual(settings["remoteCoverRef"] as? String, record.descriptor.runtimeID)
        XCTAssertEqual(canonical.value(forKeyPath: "sourceAsset.id") as? String, record.descriptor.sourceAssetID)
        let saved = try await AIFrameStore(controller: controller).state(frameID: frameID)
        XCTAssertEqual(saved?.ownedAssetIDs, [record.descriptor.runtimeID])
        try await storage.releaseCopies(frameID: frameID, verifyLegacy: false)
        let queue = try await FrameRepository(controller: controller).buildPlayQueue(frameID: frameID)
        XCTAssertEqual(queue, [record.descriptor.runtimeID])
    }

    func testAssetExclusionCannotBeBypassedByRemoteDirectoryAlias() async throws {
        let (controller, media, storage, _, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let localRecord = try await media.repository.record(id: record.descriptor.sourceAssetID)
        let assetID = try XCTUnwrap(localRecord?.localAssetID)
        let context = controller.container.viewContext
        context.reset()
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        let folder = try XCTUnwrap(context.fetch(request).first { $0.value(forKey: "rel_path") as? String == "family" })
        let folderID = try XCTUnwrap(folder.value(forKey: "id") as? String)
        let detail = try await FrameManager(controller: controller).frameDetail(frameID: frameID)
        _ = try await FrameRepository(controller: controller).save(frameID: frameID, baseVersion: detail.selectionVersion,
            removeRefs: [MediaReference(runtimeID: record.descriptor.runtimeID).selection],
            addExcludes: [SelectionRefEdit(assetId: assetID, sourceAssetId: nil)], ruleChanges: [RuleChange(collectionId: folderID, recursive: true, includeFuture: true, confirmed: true, members: [], addedAt: 0)])
        let result = try await FrameRepository(controller: controller).resolve(frameID: frameID)
        XCTAssertFalse(result.playable.contains(record.descriptor.runtimeID))
        let store = AIFrameStore(controller: controller)
        let config = try await store.configure(AIFrameConfiguration(frameID: frameID, modelID: "fixture", modelRevision: "v1", collectionIDs: [folderID], rule: AIFrameRule(category: "cat")))
        let candidates = try await store.candidates(configuration: config)
        let candidate = try XCTUnwrap(candidates.first { $0.id == record.descriptor.runtimeID })
        try await store.applyPreview(frameID: frameID, configuration: config, matches: [AIFrameMatch(assetID: candidate.id, score: 1, decision: .matched, themes: [], contentVersion: candidate.contentHash)])
        context.reset()
        let sourceIncludes = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        sourceIncludes.predicate = NSPredicate(format: "frame.id == %@ AND sourceAsset.id == %@ AND mode == %@", frameID, record.descriptor.sourceAssetID, "include")
        XCTAssertEqual(try context.count(for: sourceIncludes), 0)
    }

    func testAIAddedCanonicalMemberTriggersOnlyItsLocalSynchronization() async throws {
        let (controller, media, storage, fixture, frameID, initial) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        await media.startObserving()
        await storage.startObserving()
        let records = try await media.repository.records()
        let next = try XCTUnwrap(records.first { $0.descriptor.remoteKey == "family/holiday/dog.jpg" })
        XCTAssertNotEqual(initial.descriptor.sourceAssetID, next.descriptor.sourceAssetID)
        let context = controller.container.viewContext
        context.reset()
        let folder = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Collection")).first { $0.value(forKey: "rel_path") as? String == "family" })
        let folderID = try XCTUnwrap(folder.value(forKey: "id") as? String)
        let store = AIFrameStore(controller: controller)
        let config = try await store.configure(AIFrameConfiguration(frameID: frameID, modelID: "fixture", modelRevision: "v1", collectionIDs: [folderID], rule: AIFrameRule(category: "cat")))
        let candidates = try await store.candidates(configuration: config)
        let candidate = try XCTUnwrap(candidates.first { $0.id == next.descriptor.runtimeID })
        try await store.applyPreview(frameID: frameID, configuration: config, matches: [AIFrameMatch(assetID: candidate.id, score: 1, decision: .matched, themes: [], contentVersion: candidate.contentHash)])
        var ready = false
        for _ in 0..<100 {
            if try await media.repository.record(id: next.descriptor.sourceAssetID)?.localReady == true { ready = true; break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(ready)
        let downloads = await fixture.downloads
        XCTAssertEqual(downloads, 2)
    }

    func testPlaybackSettingsSavePreservesFreshStorageAndUnknownFields() async throws {
        let (controller, _, storage, _, frameID, _) = try await setupFrame()
        let manager = FrameManager(controller: controller)
        let before = try await manager.framePlaybackBundle(frameID: frameID)
        try await manager.updateSettingsJSON(frameID: frameID, settingsJSON: "{\"mediaStorageMode\":\"remote\",\"futureKey\":17,\"holdSeconds\":10}")
        try await manager.updateSettingsJSON(frameID: frameID, settingsJSON: before.settingsJSON, preservingMediaSettings: true)
        let after = try await manager.framePlaybackBundle(frameID: frameID)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(after.settingsJSON.utf8)) as? [String: Any])
        XCTAssertEqual(object["mediaStorageMode"] as? String, "remote")
        XCTAssertEqual(object["futureKey"] as? Int, 17)
        await storage.stopAll()
    }

    func testPreparedCleanupIntentRestoresRenamedOriginal() async throws {
        let (controller, _, storage, _, frameID, _) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let context = controller.container.viewContext
        context.reset()
        let asset = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset")).first)
        let id = try XCTUnwrap(asset.value(forKey: "id") as? String)
        let path = try XCTUnwrap(asset.value(forKey: "original_rel_path") as? String)
        let root = controller.storeURL.deletingLastPathComponent()
        let intent = RemoteCleanupIntent(id: UUID().uuidString, committed: false,
            files: [RemoteCleanupIntent.File(assetID: id, relativePath: path, trashName: "original")])
        let trash = root.appendingPathComponent("remote-trash/" + intent.id)
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        let original = root.appendingPathComponent("originals").appendingPathComponent(path)
        try FileManager.default.moveItem(at: original, to: trash.appendingPathComponent("original"))
        let row = NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
        row.setValue(FrameStorageService.cleanupPrefix + intent.id, forKey: "key")
        row.setValue(String(decoding: try JSONEncoder().encode(intent), as: UTF8.self), forKey: "value_json")
        row.setValue(0, forKey: "created_at")
        row.setValue(0, forKey: "updated_at")
        try context.save()
        try await storage.recoverCleanup()
        XCTAssertEqual(try Data(contentsOf: original), RemoteIndexFixture.pixels)
        XCTAssertFalse(FileManager.default.fileExists(atPath: trash.path))
    }

    func testOtherLocalFrameProtectsSharedOriginal() async throws {
        let (controller, _, storage, _, frameID, record) = try await setupFrame()
        try await storage.setMode(frameID: frameID, mode: .local)
        await storage.wait(frameID: frameID)
        let manager = FrameManager(controller: controller)
        let other = try await manager.createFrame(name: "Keep offline", storageMode: .local)
        _ = try await FrameRepository(controller: controller).save(frameID: other, baseVersion: 0,
            addIncludes: [SelectionRefEdit(assetId: nil, sourceAssetId: record.descriptor.sourceAssetID)])
        try await storage.setMode(frameID: frameID, mode: .remote)
        let release = try await storage.releasable(frameID: frameID)
        XCTAssertEqual(release.bytes, 0)
        XCTAssertTrue(release.assetIDs.isEmpty)
    }
}
