import CoreData
import XCTest
@testable import PhotoPilot

@MainActor
final class AIFrameStoreTests: XCTestCase {
    private func library() throws -> PersistenceController {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        let context = controller.container.viewContext
        func insert(_ entity: String, _ values: [String: Any] = [:]) -> NSManagedObject {
            let row = NSEntityDescription.insertNewObject(forEntityName: entity, into: context)
            for (key, attribute) in row.entity.attributesByName where !attribute.isOptional && attribute.defaultValue == nil {
                if attribute.attributeType == .stringAttributeType { row.setValue("", forKey: key) }
                else { row.setValue(0, forKey: key) }
            }
            for (key, value) in values { row.setValue(value, forKey: key) }
            return row
        }
        let source = insert("Source", ["id": "source", "type": "local_files", "status": "active"])
        let sourceAsset = insert("SourceAsset", ["id": "sourceAsset", "source": source, "remote_key": "cat.jpg", "revision": "r1", "access_state": "available"])
        let asset = insert("Asset", ["id": "asset", "content_hash": "hash", "readiness": "ready", "media_kind": "image", "original_rel_path": "cat.jpg"])
        _ = insert("AssetOrigin", ["asset": asset, "sourceAsset": sourceAsset, "policy_state": "allowed", "origin_state": "active"])
        let folder = insert("Collection", ["id": "folder", "name": "Folder", "type": "directory", "source": source])
        _ = insert("CollectionAsset", ["collection": folder, "asset": asset])
        _ = insert("Frame", ["id": "frame", "name": "My frame", "settings_json": "{}", "order_mode": "captured_asc"])
        try context.save()
        return controller
    }

    private var config: AIFrameConfiguration {
        AIFrameConfiguration(frameID: "frame", modelID: "multilingual-clip-vitb32-lite", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cats"))
    }

    private func savedConfiguration(_ store: AIFrameStore) async throws -> AIFrameConfiguration {
        let state = try await store.state(frameID: "frame")
        return try XCTUnwrap(state).configuration
    }

    private func selections(_ controller: PersistenceController) throws -> [NSManagedObject] {
        let context = controller.container.viewContext
        context.reset()
        return try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection"))
    }

    func testSavedStateSurvivesASecondStoreInstance() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let loaded = try await AIFrameStore(controller: controller).state(frameID: "frame")
        XCTAssertEqual(loaded?.configuration.rule.category, "cats")
        XCTAssertFalse(try XCTUnwrap(loaded).configuration.automatic)
        let candidates = try await store.candidates(configuration: config)
        XCTAssertEqual(candidates.map(\.id), ["asset"])
    }

    func testOldConfigurationCannotBeginAfterEdit() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let old = try await savedConfiguration(store)
        var changed = old
        changed.rule.category = "beach"
        try await store.configure(changed)
        do {
            _ = try await store.begin(frameID: "frame", configuration: old, total: 1)
            XCTFail("An old worker must not receive a new rule's token")
        } catch { XCTAssertEqual(error as? AIFrameError, .staleRun) }
        let latest = try await store.state(frameID: "frame")
        XCTAssertEqual(latest?.configuration.rule.category, "beach")
        XCTAssertEqual(latest?.status, "idle")
    }

    func testOldWorkerFailureCannotOverwriteEditedConfiguration() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let old = try await savedConfiguration(store)
        var changed = old
        changed.rule.category = "beach"
        try await store.configure(changed)
        try await store.stop(frameID: "frame", status: "failed", expectedConfiguration: old, lastError: "old worker")
        let state = try await store.state(frameID: "frame")
        XCTAssertEqual(state?.configuration.rule.category, "beach")
        XCTAssertEqual(state?.status, "idle")
        XCTAssertNil(state?.lastError)
    }

    func testReviewRemovesOnlyAIOwnedMembership() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        let matched = AIFrameMatch(assetID: "asset", score: 0.4, decision: .matched, themes: ["pets"])
        try await store.applyPreview(frameID: "frame", configuration: configuration, matches: [matched])
        XCTAssertEqual(try selections(controller).count, 1)
        let (state, token) = try await store.begin(frameID: "frame", configuration: configuration, total: 1)
        let candidate = AIFrameCandidate(id: "asset", contentHash: "hash", relativePath: "cat.jpg")
        try await store.record(frameID: "frame", token: token, candidate: candidate, version: "v1",
                               match: AIFrameMatch(assetID: "asset", score: 0.18, decision: .review, themes: []), apply: true)
        XCTAssertTrue(try selections(controller).isEmpty)
        XCTAssertEqual(state.ownedAssetIDs, ["asset"])
    }

    func testExplicitReviewConfirmationRestoresExcludedAsManual() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        let match = AIFrameMatch(assetID: "asset", score: 0.18, decision: .matched, themes: [])
        try await store.applyPreview(frameID: "frame", configuration: configuration, matches: [match], manualExcludes: ["asset"])
        XCTAssertEqual(try selections(controller).first?.value(forKey: "mode") as? String, "exclude")
        try await store.applyPreview(frameID: "frame", configuration: configuration, matches: [match], manualIncludes: ["asset"])
        XCTAssertEqual(try selections(controller).first?.value(forKey: "mode") as? String, "include")
        let fetchedState = try await store.state(frameID: "frame")
        let state = try XCTUnwrap(fetchedState)
        XCTAssertFalse(state.ownedAssetIDs.contains("asset"))
        XCTAssertFalse(state.manualExcludedAssetIDs.contains("asset"))
        let (_, token) = try await store.begin(frameID: "frame", configuration: configuration, total: 1)
        try await store.record(frameID: "frame", token: token, candidate: AIFrameCandidate(id: "asset", contentHash: "hash", relativePath: "cat.jpg"),
                               version: "v2", match: AIFrameMatch(assetID: "asset", score: 0, decision: .rejected, themes: []), apply: true)
        XCTAssertEqual(try selections(controller).first?.value(forKey: "mode") as? String, "include")
    }

    func testCancelledRunCannotCommit() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        let (_, token) = try await store.begin(frameID: "frame", configuration: configuration, total: 1)
        try await store.stop(frameID: "frame")
        do {
            try await store.record(frameID: "frame", token: token, candidate: AIFrameCandidate(id: "asset", contentHash: "hash", relativePath: "cat.jpg"),
                                   version: "v", match: AIFrameMatch(assetID: "asset", score: 1, decision: .matched, themes: []), apply: true)
            XCTFail("Cancelled work must never write membership")
        } catch { XCTAssertEqual(error as? AIFrameError, .staleRun) }
        XCTAssertTrue(try selections(controller).isEmpty)
    }

    func testCorruptedFeatureCacheIsTreatedAsAMiss() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.cacheVector([1, 0], assetID: "asset", version: "v")
        let context = controller.container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key BEGINSWITH %@", AIFrameStore.cachePrefix)
        let row = try XCTUnwrap(context.fetch(request).first)
        row.setValue("[]", forKey: "value_json")
        try context.save()
        let cached = try await store.cachedVector(assetID: "asset", version: "v")
        XCTAssertNil(cached)
    }

    func testPrivacyAndDeletionExcludeCandidatesAndFeatures() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.cacheVector([1, 0], assetID: "asset", version: "version")
        let cached = try await store.cachedVector(assetID: "asset", version: "version")
        XCTAssertEqual(cached, [1, 0])
        let context = controller.container.viewContext
        let asset = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset")).first)
        let frame = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")).first)
        frame.setValue(asset, forKey: "coverAsset")
        asset.setValue(true, forKey: "privacy_excluded")
        try AIFrameStore.removeFeatures(context: context, assetID: "asset")
        try context.save()
        let candidates = try await store.candidates(configuration: config)
        let deletedCache = try await store.cachedVector(assetID: "asset", version: "version")
        XCTAssertTrue(candidates.isEmpty)
        XCTAssertNil(deletedCache)
        XCTAssertNil(frame.value(forKey: "coverAsset"))
    }

    #if !SWIFT_PACKAGE
    func testConcurrentEditCannotAuthorizeOldPreviewResults() async throws {
        let controller = try library()
        let service = AIFrameService(controller: controller)
        let catalog = try await service.catalog()
        let descriptor = try XCTUnwrap(catalog.first { $0.id == "multilingual-clip-vitb32-lite" })
        var configuration = config
        configuration.modelRevision = descriptor.revision
        let latch = AIFrameInterleavingLatch()
        let observer = NotificationCenter.default.addObserver(forName: .NSManagedObjectContextDidSave, object: nil, queue: nil) { notification in
            guard let savedContext = notification.object as? NSManagedObjectContext,
                  savedContext.persistentStoreCoordinator === controller.container.persistentStoreCoordinator else { return }
            let inserted = notification.userInfo?[NSInsertedObjectsKey] as? Set<NSManagedObject> ?? []
            let updated = notification.userInfo?[NSUpdatedObjectsKey] as? Set<NSManagedObject> ?? []
            let hasConfigurationA = inserted.union(updated).contains { row in
                guard row.entity.name == "SettingsKV", row.value(forKey: "key") as? String == AIFrameStore.prefix + "frame",
                      let string = row.value(forKey: "value_json") as? String,
                      let data = string.data(using: .utf8), let state = try? AIFrameState.decode(data) else { return false }
                return state.configuration.rule.category == "cats"
            }
            guard hasConfigurationA, latch.claim() else { return }
            // DidSave runs after A's SQLite commit and before its async caller resumes.
            // Commit B synchronously at this barrier to exercise the exact await gap.
            let contextB = controller.container.newBackgroundContext()
            do {
                try contextB.performAndWait {
                    let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
                    request.predicate = NSPredicate(format: "key == %@", AIFrameStore.prefix + "frame")
                    let row = try XCTUnwrap(contextB.fetch(request).first)
                    let string = try XCTUnwrap(row.value(forKey: "value_json") as? String)
                    var state = try AIFrameState.decode(Data(string.utf8))
                    state.configuration.rule.category = "beach"
                    state.configuration.revision += 1
                    state.status = "idle"
                    row.setValue(String(data: try JSONEncoder().encode(state), encoding: .utf8), forKey: "value_json")
                    try contextB.save()
                }
            } catch { XCTFail("Unable to commit the concurrent edit: \(error)") }
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        var caught: Error?
        do {
            try await service.savePreview(frameID: "frame", configuration: configuration,
                                          matches: [AIFrameMatch(assetID: "asset", score: 0.9, decision: .matched, themes: ["pets"])])
        } catch { caught = error }
        XCTAssertTrue(latch.didInterleave)
        XCTAssertEqual(caught as? AIFrameError, .staleRun)
        let state = try await service.store.state(frameID: "frame")
        XCTAssertEqual(state?.configuration.rule.category, "beach")
        XCTAssertTrue(state?.matches.isEmpty ?? false)
        XCTAssertTrue(try selections(controller).isEmpty)
    }

    func testRealPrivacyHideRemovesTagsFeaturesAndFrameCover() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        try await store.applyPreview(frameID: "frame", configuration: configuration,
                                     matches: [AIFrameMatch(assetID: "asset", score: 0.8, decision: .matched, themes: ["pets"])])
        try await store.cacheVector([1, 0], assetID: "asset", version: "version")
        let context = controller.container.viewContext
        context.reset()
        let asset = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset")).first)
        let frame = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")).first)
        frame.setValue(asset, forKey: "coverAsset")
        try context.save()
        try await AssetRepository(controller: controller).hideAssetGlobally(id: "asset")
        let cached = try await store.cachedVector(assetID: "asset", version: "version")
        let state = try await store.state(frameID: "frame")
        let cards = try await FrameManager(controller: controller).frameCards()
        XCTAssertNil(cached)
        XCTAssertNil(state?.matches["asset"])
        XCTAssertFalse(state?.ownedAssetIDs.contains("asset") ?? true)
        XCTAssertNil(cards.first?.coverRelPath)
    }
    #endif

    func testVideoUnreadyAndDisallowedOriginsAreNeverCandidates() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        let context = controller.container.viewContext
        let asset = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Asset")).first)
        let origin = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")).first)
        asset.setValue("video", forKey: "media_kind")
        try context.save()
        var candidates = try await store.candidates(configuration: config)
        XCTAssertTrue(candidates.isEmpty)
        asset.setValue("image", forKey: "media_kind")
        asset.setValue("pending", forKey: "readiness")
        try context.save()
        candidates = try await store.candidates(configuration: config)
        XCTAssertTrue(candidates.isEmpty)
        asset.setValue("ready", forKey: "readiness")
        origin.setValue("blocked", forKey: "policy_state")
        try context.save()
        candidates = try await store.candidates(configuration: config)
        XCTAssertTrue(candidates.isEmpty)
    }

    func testDeletedSourceFolderCannotReceiveInFlightMatch() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        let (_, token) = try await store.begin(frameID: "frame", configuration: configuration, total: 1)
        let context = controller.container.viewContext
        context.reset()
        let folder = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Collection")).first)
        context.delete(folder)
        try context.save()
        try await store.record(frameID: "frame", token: token,
                               candidate: AIFrameCandidate(id: "asset", contentHash: "hash", relativePath: "cat.jpg"),
                               version: "v", match: AIFrameMatch(assetID: "asset", score: 1, decision: .matched, themes: []), apply: true)
        XCTAssertTrue(try selections(controller).isEmpty)
        let state = try await store.state(frameID: "frame")
        XCTAssertEqual(state?.skipped, 1)
    }

    func testAutomaticTogglePreservesExistingOwnedContentAndProcessedVersions() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let configuration = try await savedConfiguration(store)
        let (_, token) = try await store.begin(frameID: "frame", configuration: configuration, total: 1)
        try await store.record(frameID: "frame", token: token,
                               candidate: AIFrameCandidate(id: "asset", contentHash: "hash", relativePath: "cat.jpg"),
                               version: "v", match: AIFrameMatch(assetID: "asset", score: 1, decision: .matched, themes: []), apply: true)
        var automatic = configuration
        automatic.automatic = true
        try await store.configure(automatic)
        let state = try await store.state(frameID: "frame")
        XCTAssertEqual(state?.completedContentVersions["asset"], "v")
        XCTAssertEqual(state?.ownedAssetIDs, ["asset"])
        XCTAssertTrue(state?.configuration.automatic ?? false)
    }

    func testFailedPreviewCleanupPreservesAConcurrentEdit() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        var edited = try await savedConfiguration(store)
        edited.rule.category = "beach"
        try await store.configure(edited)
        try await store.discardEmptyDraft(frameID: "frame", name: "My frame", configuration: config)
        let state = try await store.state(frameID: "frame")
        XCTAssertEqual(state?.configuration.rule.category, "beach")
        XCTAssertEqual(try controller.container.viewContext.count(for: NSFetchRequest<NSManagedObject>(entityName: "Frame")), 1)
    }

    func testFailedPreviewCleanupRemovesOnlyUntouchedEmptyDraft() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        try await store.discardEmptyDraft(frameID: "frame", name: "My frame", configuration: config)
        let state = try await store.state(frameID: "frame")
        XCTAssertNil(state)
        controller.container.viewContext.reset()
        XCTAssertEqual(try controller.container.viewContext.count(for: NSFetchRequest<NSManagedObject>(entityName: "Frame")), 0)
    }

    func testDeleteFrameRemovesStateInSameTransaction() async throws {
        let controller = try library()
        let store = AIFrameStore(controller: controller)
        try await store.configure(config)
        let context = controller.container.viewContext
        try AIFrameStore.removeState(context: context, frameID: "frame")
        let frame = try XCTUnwrap(context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")).first)
        context.delete(frame)
        try context.save()
        let state = try await store.state(frameID: "frame")
        let allStates = try await store.states()
        XCTAssertNil(state)
        XCTAssertTrue(allStates.isEmpty)
    }
}

#if !SWIFT_PACKAGE
private final class AIFrameInterleavingLatch: @unchecked Sendable {
    private let lock = NSLock()
    private var claimed = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !claimed else { return false }
        claimed = true
        return true
    }
    var didInterleave: Bool {
        lock.lock()
        defer { lock.unlock() }
        return claimed
    }
}
#endif
