import CoreData
import XCTest
import UIKit
@testable import PhotoPilot

@MainActor
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
        let request = NSFetchRequest<NSManagedObject>(entityName: entity)
        request.predicate = NSPredicate(format: "id == %@", value)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private func fetchFresh(_ entity: String, _ key: String, _ value: String) throws -> [String: Any]? {
        let context = controller.container.newBackgroundContext()
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: entity)
            request.predicate = NSPredicate(format: "id == %@", value)
            request.fetchLimit = 1
            guard let object = try context.fetch(request).first else { return nil }
            var snapshot: [String: Any] = [:]
            for name in object.entity.attributesByName.keys {
                if let attributeValue = object.value(forKey: name) {
                    snapshot[name] = attributeValue
                }
            }
            for name in object.entity.relationshipsByName.keys {
                snapshot["rel:\(name)"] = object.value(forKey: name) != nil
            }
            return snapshot
        }
    }

    private func freshCount(_ entity: String, predicate: NSPredicate? = nil) throws -> Int {
        let context = controller.container.newBackgroundContext()
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: entity)
            request.predicate = predicate
            return try context.count(for: request)
        }
    }

    private var fixtureRoot: URL {
        Bundle(for: Self.self).resourceURL!.appendingPathComponent("smoke-v1")
    }

    func testSourceAssetUpsertIsFetchFirst() async throws {
        try seedNasSource()
        let adapter = LocalFilesFixtureAdapter(rootURL: fixtureRoot)
        let coordinator = ImportCoordinator(controller: controller, adapter: adapter)
        let seededCount = try freshCount("SourceAsset")
        let first = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: first.id)
        let afterFirst = try freshCount("SourceAsset")
        let second = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: second.id)
        let afterSecond = try freshCount("SourceAsset")
        XCTAssertGreaterThan(afterFirst, seededCount)
        XCTAssertEqual(afterSecond, afterFirst)
    }

    func testDeltaSyncSkipsUnchangedItems() async throws {
        try seedNasSource()
        let adapter = LocalFilesFixtureAdapter(rootURL: fixtureRoot)
        let coordinator = ImportCoordinator(controller: controller, adapter: adapter)
        let first = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: first.id)

        let context = controller.container.viewContext
        let sourceAssetIDs: [NSManagedObjectID] = {
            let fresh = controller.container.newBackgroundContext()
            return fresh.performAndWait {
                ((try? fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "SourceAsset"))) ?? [])
                    .map(\.objectID)
            }
        }()
        for (index, objectID) in sourceAssetIDs.enumerated() {
            let sourceAsset = context.object(with: objectID)
            let asset = insert("Asset", [
                "id": "committed-\(index)",
                "content_hash": "sha256:committed-\(index)",
                "byte_size": 5,
                "format": "jpeg",
                "pixel_width": 1,
                "pixel_height": 1,
                "exif_orientation": 1,
                "orientation_class": "square",
                "captured_at_source": "unknown",
                "imported_at": 0,
                "original_rel_path": "committed-\(index).jpg",
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
        }
        try context.save()

        let second = try await coordinator.createJob(sourceID: "sw1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: second.id)
        XCTAssertEqual(
            try freshCount("ImportItem", predicate: NSPredicate(format: "job.id == %@", second.id)),
            0
        )
    }

    func testMirrorEnableResetsMissingCounts() throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let sourceAsset = try XCTUnwrap(fetch("SourceAsset", "id", "sa1"))
        sourceAsset.setValue(2, forKey: "missing_scan_count")
        sourceAsset.setValue("missing", forKey: "access_state")
        try context.save()

        try sources.setWebdavMirrorDelete(sourceID: "sw1", enabled: true)

        let updated = try XCTUnwrap(fetchFresh("SourceAsset", "id", "sa1"))
        XCTAssertEqual(updated["missing_scan_count"] as? Int, 0)
        XCTAssertEqual(updated["access_state"] as? String, "missing")
        XCTAssertTrue(try sources.webdavMirrorDeleteEnabled(sourceID: "sw1"))

        try sources.setWebdavMirrorDelete(sourceID: "sw1", enabled: false)
        let afterDisable = try XCTUnwrap(fetchFresh("SourceAsset", "id", "sa1"))
        XCTAssertEqual(afterDisable["missing_scan_count"] as? Int, 0)
    }

    func testDisconnectKeepCopiesThenReconnect() async throws {
        try seedNasSource()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true)

        let source = try XCTUnwrap(fetchFresh("Source", "id", "sw1"))
        XCTAssertEqual(source["status"] as? String, "disconnected")
        XCTAssertNotNil(try fetchFresh("SourceAsset", "id", "sa1"))
        let asset = try XCTUnwrap(fetchFresh("Asset", "id", "a1"))
        XCTAssertEqual(asset["readiness"] as? String, "ready")
        XCTAssertTrue(
            FileManager.default.fileExists(
                atPath: originalsRoot.appendingPathComponent("a1.jpg").path
            )
        )
        let fresh = controller.container.newBackgroundContext()
        let originSnapshot: [String: Any]? = try fresh.performAndWait {
            let originRequest = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
            originRequest.predicate = NSPredicate(format: "asset.id == %@", "a1")
            guard let row = try fresh.fetch(originRequest).first else { return nil }
            return [
                "origin_state": row.value(forKey: "origin_state") as? String ?? "",
                "hasSourceAsset": row.value(forKey: "sourceAsset") != nil,
            ] as [String: Any]
        }
        let origin = try XCTUnwrap(originSnapshot)
        XCTAssertEqual(origin["origin_state"] as? String, "detached")
        XCTAssertEqual(origin["hasSourceAsset"] as? Bool, true)
        XCTAssertNil(try fetchFresh("ImportJob", "id", "j1"))

        let revived = try sources.ensureWebdavSource(
            endpointKey: "webdav://nas.local:443/photos",
            displayName: "nas.local",
            recursive: true,
            directories: ["photos"]
        )
        XCTAssertEqual(revived, "sw1")
        let revivedSource = try XCTUnwrap(fetchFresh("Source", "id", "sw1"))
        XCTAssertEqual(revivedSource["status"] as? String, "active")
        XCTAssertEqual(
            revivedSource["settings_json"] as? String,
            "{\"directories\":[\"photos\"],\"recursive\":true}"
        )
        XCTAssertEqual(try freshCount("Source"), 1)
    }

    func testRemoveDisconnectedConfigurationKeepsCopiesAndSelection() async throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let frame = insert("Frame", ["id": "f1", "name": "Frame", "order_mode": "captured_asc", "settings_json": "{}", "created_at": 0, "updated_at": 0])
        let selection = insert("FrameSelection", ["ref_key": "sa1", "mode": "include", "added_at": 0, "created_at": 0, "updated_at": 0])
        selection.setValue(frame, forKey: "frame")
        selection.setValue(try fetch("SourceAsset", "id", "sa1"), forKey: "sourceAsset")
        try context.save()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true)
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true, removeConfiguration: true)
        XCTAssertNil(try fetchFresh("Source", "id", "sw1"))
        XCTAssertNotNil(try fetchFresh("Asset", "id", "a1"))
        XCTAssertEqual(try freshCount("AssetOrigin"), 1)
        let fresh = controller.container.newBackgroundContext()
        let references = try fresh.performAndWait {
            try fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")).map {
                $0.value(forKeyPath: "asset.id") as? String ?? ""
            }
        }
        XCTAssertEqual(references, ["a1"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: originalsRoot.appendingPathComponent("a1.jpg").path))
    }

    func testRemovingConfigurationRetainsPrivateCopiesAndFolderMemberships() async throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let source = try XCTUnwrap(fetch("Source", "id", "sw1"))
        let asset = try XCTUnwrap(fetch("Asset", "id", "a1"))
        asset.setValue(true, forKey: "privacy_excluded")
        let parent = insert("Collection", ["id": "parent", "name": "NAS", "type": "source_folder", "rel_path": "", "sort_key": "", "created_at": 0, "updated_at": 0])
        parent.setValue(source, forKey: "source")
        let folder = insert("Collection", ["id": "folder", "name": "Photos", "type": "source_folder", "rel_path": "photos", "sort_key": "photos", "created_at": 0, "updated_at": 0])
        folder.setValue(source, forKey: "source")
        folder.setValue(parent, forKey: "parent")
        try context.save()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true, removeConfiguration: true)
        XCTAssertNil(try fetchFresh("Source", "id", "sw1"))
        XCTAssertEqual(try fetchFresh("Asset", "id", "a1")?["privacy_excluded"] as? Bool, true)
        XCTAssertEqual(try fetchFresh("Collection", "id", "folder")?["type"] as? String, "user_folder")
        XCTAssertEqual(try freshCount("CollectionAsset"), 1)
        let fresh = controller.container.newBackgroundContext()
        let types = try fresh.performAndWait {
            try fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")).map { $0.value(forKeyPath: "sourceAsset.source.type") as? String ?? "" }
        }
        XCTAssertEqual(types, ["local_files"])
    }

    func testRemovingConfigurationMapsOnlyCurrentCopyAndFolderMembership() async throws {
        try await verifyCurrentCopyMapping(versioned: true)
    }

    func testRemovingLegacyConfigurationUsesUniqueCommittedFolderCopy() async throws {
        try await verifyCurrentCopyMapping(versioned: false)
    }

    private func verifyCurrentCopyMapping(versioned: Bool) async throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let source = try XCTUnwrap(fetch("Source", "id", "sw1"))
        let sourceAsset = try XCTUnwrap(fetch("SourceAsset", "id", "sa1"))
        let old = try XCTUnwrap(fetch("Asset", "id", "a1"))
        if versioned { try RemoteMediaRepository.writeProvenance(context: context, asset: old, sourceAsset: sourceAsset, frameScoped: false) }
        sourceAsset.setValue("etag-2", forKey: "revision")
        let current = insert("Asset", old.dictionaryWithValues(forKeys: Array(old.entity.attributesByName.keys)))
        current.setValue("a2", forKey: "id")
        current.setValue("sha256:current", forKey: "content_hash")
        current.setValue("a2.jpg", forKey: "original_rel_path")
        let origin = insert("AssetOrigin", ["imported_at": 1, "policy_state": "allowed", "origin_state": "active", "created_at": 1, "updated_at": 1])
        origin.setValue(current, forKey: "asset")
        origin.setValue(sourceAsset, forKey: "sourceAsset")
        if versioned { try RemoteMediaRepository.writeProvenance(context: context, asset: current, sourceAsset: sourceAsset, frameScoped: false) }
        try Data([5, 4, 3, 2, 1]).write(to: originalsRoot.appendingPathComponent("a2.jpg"))
        let folder = insert("Collection", ["id": "folder", "name": "Photos", "type": "source_folder", "rel_path": "photos", "sort_key": "photos", "created_at": 0, "updated_at": 0])
        folder.setValue(source, forKey: "source")
        let stale = insert("CollectionAsset", ["created_at": 0, "updated_at": 0])
        stale.setValue(folder, forKey: "collection")
        stale.setValue(versioned ? old : current, forKey: "asset")
        let frame = insert("Frame", ["id": "f1", "name": "Frame", "order_mode": "captured_asc", "settings_json": "{\"remoteCoverRef\":\"remote:sa1\"}", "created_at": 0, "updated_at": 0])
        let selection = insert("FrameSelection", ["ref_key": "sa1", "mode": "include", "added_at": 0, "created_at": 0, "updated_at": 0])
        selection.setValue(frame, forKey: "frame")
        selection.setValue(sourceAsset, forKey: "sourceAsset")
        var aiState = AIFrameState(configuration: AIFrameConfiguration(frameID: "f1", modelID: "multilingual-clip-vitb32-lite", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cats")))
        aiState.ownedAssetIDs = ["remote:sa1"]
        _ = insert("SettingsKV", ["key": AIFrameStore.prefix + "f1", "value_json": String(decoding: try JSONEncoder().encode(aiState), as: UTF8.self), "created_at": 0, "updated_at": 0])
        try context.save()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true)
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true, removeConfiguration: true)
        let fresh = controller.container.newBackgroundContext()
        try fresh.performAndWait {
            let selections = try fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "FrameSelection"))
            XCTAssertEqual(selections.map { $0.value(forKeyPath: "asset.id") as? String }, ["a2"])
            let members = try fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset"))
            XCTAssertEqual(members.map { $0.value(forKeyPath: "asset.id") as? String }, ["a2"])
            let frames = try fresh.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame"))
            XCTAssertEqual(frames.first?.value(forKeyPath: "coverAsset.id") as? String, "a2")
        }
        let remappedAI = try await AIFrameStore(controller: controller).state(frameID: "f1")
        XCTAssertEqual(remappedAI?.ownedAssetIDs, ["a2"])
        XCTAssertEqual(try freshCount("Asset"), 2)
        XCTAssertEqual(try freshCount("AssetOrigin"), 2)
        for name in ["a1.jpg", "a2.jpg"] { XCTAssertTrue(FileManager.default.fileExists(atPath: originalsRoot.appendingPathComponent(name).path)) }
    }

    func testRemoveConfigurationWithoutLocalFileClearsRemoteSelectionAndKeepsFrame() async throws {
        try seedNasSource()
        let context = controller.container.viewContext
        let frame = insert("Frame", ["id": "empty", "name": "Empty", "order_mode": "captured_asc", "settings_json": "{\"remoteCoverRef\":\"remote:sa1\"}", "created_at": 0, "updated_at": 0])
        let selection = insert("FrameSelection", ["ref_key": "sa1", "mode": "include", "added_at": 0, "created_at": 0, "updated_at": 0])
        selection.setValue(frame, forKey: "frame")
        selection.setValue(try fetch("SourceAsset", "id", "sa1"), forKey: "sourceAsset")
        try context.save()
        try FileManager.default.removeItem(at: originalsRoot.appendingPathComponent("a1.jpg"))
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true, removeConfiguration: true)
        XCTAssertNil(try fetchFresh("Source", "id", "sw1"))
        XCTAssertEqual(try freshCount("FrameSelection"), 0)
        let keptFrame = try XCTUnwrap(fetchFresh("Frame", "id", "empty"))
        XCTAssertEqual(keptFrame["settings_json"] as? String, "{}")
    }

    func testSourceStorageChoicePersistsAndInvalidatesEpoch() throws {
        let id = try sources.ensureWebdavSource(endpointKey: "webdav_https://fixture.invalid:443/photos", displayName: "Fixture NAS", recursive: true, directories: ["photos"], catalogOnly: true)
        XCTAssertTrue(try XCTUnwrap(sources.webdavSources().first { $0.id == id }).catalogOnly)
        _ = try sources.ensureWebdavSource(endpointKey: "webdav_https://fixture.invalid:443/photos", displayName: "Fixture NAS", recursive: true, directories: ["photos"], catalogOnly: false)
        XCTAssertFalse(try XCTUnwrap(sources.webdavSources().first { $0.id == id }).catalogOnly)
        XCTAssertEqual(try fetchFresh("Source", "id", id)?["epoch"] as? Int, 2)
        _ = try sources.ensureWebdavSource(endpointKey: "webdav_https://fixture.invalid:443/photos", displayName: "Fixture NAS", recursive: true, directories: ["photos"], catalogOnly: true)
        XCTAssertTrue(try XCTUnwrap(sources.webdavSources().first { $0.id == id }).catalogOnly)
        XCTAssertEqual(try fetchFresh("Source", "id", id)?["epoch"] as? Int, 3)
    }

    @MainActor
    func testConnectedSourceActionsFitPhoneWidthAtLargestText() async throws {
        try seedNasSource()
        let environment = AppEnvironment(persistence: controller)
        let previousLanguage = AppLanguage.overrideValue
        defer { AppLanguage.configure(value: previousLanguage) }
        func descendants(_ view: UIView) -> [UIView] { [view] + view.subviews.flatMap(descendants) }
        for language in [AppLanguage.enValue, AppLanguage.zhValue] {
            AppLanguage.configure(value: language)
            for width in [CGFloat(320), CGFloat(390)] {
                let screen = SourcesListViewController(environment: environment)
                if #available(iOS 17.0, *) {
                    screen.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge
                }
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 844))
                window.rootViewController = screen
                window.makeKeyAndVisible()
                defer { window.isHidden = true }
                try await Task.sleep(nanoseconds: 150_000_000)
                window.layoutIfNeeded()
                screen.view.layoutIfNeeded()
                let buttons = descendants(screen.view).compactMap { $0 as? ActionButton }.filter {
                    $0.accessibilityIdentifier == "sw1" && !$0.isHidden
                }
                XCTAssertEqual(buttons.count, 2)
                for button in buttons {
                    let row = try XCTUnwrap(button.superview?.superview?.superview)
                    XCTAssertTrue(row.bounds.contains(button.convert(button.bounds, to: row)))
                    XCTAssertGreaterThanOrEqual(button.bounds.height, 44)
                    XCTAssertGreaterThanOrEqual(button.bounds.width, width - 65)
                    for label in descendants(button).compactMap({ $0 as? UILabel }) {
                        XCTAssertGreaterThanOrEqual(label.bounds.height + 1, label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height)
                    }
                }
            }
        }
    }

    @MainActor
    func testDisconnectedSourceDeleteAndStorageChoicesAreReachable() async throws {
        try seedNasSource()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: true)
        controller.container.viewContext.reset()
        let environment = AppEnvironment(persistence: controller)
        let sourceScreen = SourcesListViewController(environment: environment)
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = sourceScreen
        window.makeKeyAndVisible()
        sourceScreen.viewWillAppear(false)
        sourceScreen.view.layoutIfNeeded()
        func buttons(in view: UIView) -> [ActionButton] {
            (view as? ActionButton).map { [$0] } ?? view.subviews.flatMap { buttons(in: $0) }
        }
        let delete = try XCTUnwrap(buttons(in: sourceScreen.view).first { $0.accessibilityIdentifier == "sw1" && !$0.isHidden })
        XCTAssertGreaterThanOrEqual(delete.bounds.height, 44)
        XCTAssertFalse(delete.isHidden)
        let sourceImage = UIGraphicsImageRenderer(bounds: sourceScreen.view.bounds).image { _ in
            sourceScreen.view.drawHierarchy(in: sourceScreen.view.bounds, afterScreenUpdates: true)
        }
        let sourceAttachment = XCTAttachment(image: sourceImage)
        sourceAttachment.name = "ios-disconnected-source-delete"
        sourceAttachment.lifetime = .keepAlways
        add(sourceAttachment)
        delete.sendActions(for: .touchUpInside)
        XCTAssertTrue(sourceScreen.presentedViewController is UIAlertController)
        try await Task.sleep(nanoseconds: 700_000_000)
        await withCheckedContinuation { continuation in
            sourceScreen.dismiss(animated: false) { continuation.resume() }
        }
        let form = NasAddViewController(environment: environment, prefillCatalogOnly: false)
        window.rootViewController = form
        window.layoutIfNeeded()
        form.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 100_000_000)
        form.view.layoutIfNeeded()
        let choices = buttons(in: form.view)
        let online = try XCTUnwrap(choices.first { $0.accessibilityIdentifier == "nas.storage.online" })
        let local = try XCTUnwrap(choices.first { $0.accessibilityIdentifier == "nas.storage.local" })
        XCTAssertTrue(local.isSelected)
        online.sendActions(for: .touchUpInside)
        XCTAssertTrue(online.isSelected)
        XCTAssertFalse(local.isSelected)
        var ancestor = local.superview
        while let current = ancestor {
            if let scroll = current as? UIScrollView {
                scroll.layoutIfNeeded()
                scroll.setContentOffset(CGPoint(x: 0, y: max(0, scroll.contentSize.height - scroll.bounds.height)), animated: false)
                break
            }
            ancestor = current.superview
        }
        form.view.layoutIfNeeded()
        let formImage = UIGraphicsImageRenderer(bounds: form.view.bounds).image { _ in
            form.view.drawHierarchy(in: form.view.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: formImage)
        attachment.name = "ios-nas-storage-choices"
        attachment.lifetime = .keepAlways
        add(attachment)
        form.overrideUserInterfaceStyle = .dark
        if #available(iOS 17.0, *) { form.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge }
        form.view.setNeedsLayout()
        form.view.layoutIfNeeded()
        try await Task.sleep(nanoseconds: 100_000_000)
        ancestor = local.superview
        while let current = ancestor {
            if let scroll = current as? UIScrollView {
                scroll.layoutIfNeeded()
                let start = online.convert(online.bounds, to: scroll).minY
                scroll.setContentOffset(CGPoint(x: 0, y: max(0, start - 16)), animated: false)
                break
            }
            ancestor = current.superview
        }
        let dark = UIGraphicsImageRenderer(bounds: form.view.bounds).image { _ in
            form.view.drawHierarchy(in: form.view.bounds, afterScreenUpdates: true)
        }
        let darkAttachment = XCTAttachment(image: dark)
        darkAttachment.name = "ios-nas-storage-choices-dark-large"
        darkAttachment.lifetime = .keepAlways
        add(darkAttachment)
        window.isHidden = true
    }

    func testDisconnectDeleteCopies() async throws {
        try seedNasSource()
        try await repository.disconnectSource(sourceID: "sw1", keepCopies: false)

        XCTAssertNil(try fetchFresh("Source", "id", "sw1"))
        XCTAssertNil(try fetchFresh("SourceAsset", "id", "sa1"))
        XCTAssertNil(try fetchFresh("Asset", "id", "a1"))
        XCTAssertFalse(
            FileManager.default.fileExists(
                atPath: originalsRoot.appendingPathComponent("a1.jpg").path
            )
        )
        let fresh = controller.container.newBackgroundContext()
        let tombstoneSnapshot: [String: String]? = try fresh.performAndWait {
            let tombstones = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            tombstones.predicate = NSPredicate(format: "content_hash == %@", "sha256:test")
            guard let row = try fresh.fetch(tombstones).first else { return nil }
            return [
                "reason": row.value(forKey: "reason") as? String ?? "",
                "source_fingerprint": row.value(forKey: "source_fingerprint") as? String ?? "",
                "remote_id": row.value(forKey: "remote_id") as? String ?? "",
            ]
        }
        let tombstone = try XCTUnwrap(tombstoneSnapshot)
        XCTAssertEqual(tombstone["reason"] as? String, "user_delete")
        XCTAssertEqual(
            tombstone["source_fingerprint"] as? String,
            "webdav://nas.local:443/photos"
        )
        XCTAssertEqual(tombstone["remote_id"] as? String, "photos/a.jpg")
    }
}
