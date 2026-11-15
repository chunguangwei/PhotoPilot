import CoreData
import Photos
import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import PhotoPilot

@MainActor
final class LocalReferenceTests: XCTestCase {
    private func environment() -> AppEnvironment {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        return AppEnvironment(persistence: PersistenceController(storeURL: root.appendingPathComponent("library.db")))
    }

    func testFilesPickerDefaultIndexesWithoutImportingOriginal() async throws {
        let environment = environment()
        let input = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { context in
            UIColor.orange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        try XCTUnwrap(image.pngData()).write(to: input)
        let screen = SourcesListViewController(environment: environment)
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = UINavigationController(rootViewController: screen)
        window.makeKeyAndVisible()
        UIView.setAnimationsEnabled(false)
        defer {
            window.isHidden = true
            UIView.setAnimationsEnabled(true)
        }
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false)
        screen.documentPicker(picker, didPickDocumentsAt: [input])
        let context = environment.persistence.container.newBackgroundContext()
        for _ in 0..<50 {
            let count = try await context.perform { try context.count(for: NSFetchRequest<NSFetchRequestResult>(entityName: "SourceAsset")) }
            if count > 0 { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        try await Task.sleep(nanoseconds: 700_000_000)
        let counts = try await context.perform {
            try ["Asset", "AssetOrigin", "ImportJob"].map { try context.count(for: NSFetchRequest<NSFetchRequestResult>(entityName: $0)) }
        }
        XCTAssertEqual(counts, [0, 0, 0])
        let items = try await environment.assets.libraryItems()
        XCTAssertEqual(items.count, 1)
        let item = try XCTUnwrap(items.first)
        XCTAssertTrue(item.id.hasPrefix("remote:"))
        let loaded = try await environment.remoteMedia.image(runtimeID: item.id, mode: .local, width: 32, height: 32)
        XCTAssertNotNil(loaded.cgImage)
        let originalRoot = environment.originalsRootURL
        XCTAssertFalse(FileManager.default.fileExists(atPath: originalRoot.path))
    }
}

private actor ReferenceFixtureProvider: LocalReferenceProvider {
    private var revision = "v1"
    private var revoked = false
    private(set) var bodyReads = 0

    func changeRevision() { revision = "v2" }
    func useUnknownRevision() { revision = "unknown:fixture" }
    func revoke() { revoked = true }

    func snapshot(source: LocalReferenceSource) async throws -> LocalReferenceSnapshot {
        guard !revoked else { throw RemoteMediaError.revoked }
        return LocalReferenceSnapshot(items: [LocalReferenceItem(key: "photo/fixture", revision: revision, takenAt: 17)], folders: [
            LocalReferenceFolder(key: "album-one", name: "First album", members: ["photo/fixture"]),
            LocalReferenceFolder(key: "album-two", name: "Second album", members: ["photo/fixture"]),
        ])
    }

    func image(source: LocalReferenceSource, key: String, revision: String, width: Int, height: Int) async throws -> UIImage {
        guard !revoked, self.revision == revision else { throw RemoteMediaError.revoked }
        bodyReads += 1
        return try XCTUnwrap(UIImage(data: RemoteIndexFixture.pixels))
    }

    func authorized(source: LocalReferenceSource, key: String, revision: String) async -> Bool { !revoked && self.revision == revision }
}

extension LocalReferenceTests {
    func testPhotoMetadataAlbumsRestartAuthorityAndNASLocalFrameDoNotCreateCopies() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let fixture = ReferenceFixtureProvider()
        let media = RemoteMediaService(controller: controller, referenceProvider: fixture)
        let environment = AppEnvironment(persistence: controller, remoteMedia: media)
        let legacyID = try environment.sources.ensureLocalPhotosSource()
        let coordinator = ImportCoordinator(controller: controller, adapter: PhotoLibrarySourceAdapter())
        let oldJob = try await coordinator.createJob(sourceID: legacyID, scopeJSON: "{}")
        let sourceID = try await environment.connectLocalPhotoReferences(albumIDs: ["album-one", "album-two"])
        let canceledJob = try await coordinator.fetchJob(id: oldJob.id)
        XCTAssertEqual(canceledJob?.state, ImportJobState.canceled.rawValue)
        let metadataReads = await fixture.bodyReads
        XCTAssertEqual(metadataReads, 0)
        let context = controller.container.newBackgroundContext()
        let counts = try await context.perform {
            try ["SourceAsset", "Asset", "AssetOrigin"].map { try context.count(for: NSFetchRequest<NSFetchRequestResult>(entityName: $0)) }
        }
        XCTAssertEqual(counts, [1, 0, 0])
        let folders = try await environment.assets.collections().filter { $0.sourceID == sourceID }
        XCTAssertEqual(folders.count, 2)
        XCTAssertEqual(folders.map(\.memberCount), [1, 1])
        let library = try await environment.assets.libraryItems()
        let item = try XCTUnwrap(library.first)
        let frameID = try await environment.frameManager.createFrame(name: "Device photos", storageMode: .local)
        let detail = try await environment.frameManager.frameDetail(frameID: frameID)
        _ = try await environment.frames.save(frameID: frameID, baseVersion: detail.selectionVersion, addIncludes: [MediaReference(runtimeID: item.id).selection])
        let resolution = try await environment.frames.resolve(frameID: frameID)
        XCTAssertEqual(resolution.playable, [item.id])
        let state = AIFrameConfiguration(frameID: frameID, modelID: "fixture", modelRevision: "v1", collectionIDs: folders.map(\.id), rule: AIFrameRule(category: "cat"))
        let candidates = try await AIFrameStore(controller: controller).candidates(configuration: state)
        XCTAssertEqual(candidates.map(\.id), [item.id])
        let restarted = RemoteMediaService(controller: controller, referenceProvider: fixture)
        let loaded = try await restarted.image(runtimeID: item.id, mode: .local, width: 64, height: 64)
        XCTAssertNotNil(loaded.cgImage)
        try await environment.frameStorage.setMode(frameID: frameID, mode: .local)
        await environment.frameStorage.wait(frameID: frameID)
        let bodies = await fixture.bodyReads
        XCTAssertEqual(bodies, 1)
        await fixture.changeRevision()
        let revisionAllowed = await restarted.authorized(runtimeID: item.id)
        XCTAssertFalse(revisionAllowed)
        _ = try await restarted.references.index(sourceID: sourceID)
        let refreshedAllowed = await restarted.authorized(runtimeID: item.id)
        XCTAssertTrue(refreshedAllowed)
        await fixture.revoke()
        let revokedAllowed = await restarted.authorized(runtimeID: item.id)
        XCTAssertFalse(revokedAllowed)
        do { _ = try await restarted.references.index(sourceID: sourceID); XCTFail("Revoked source indexed") }
        catch { }
        let revokedItems = try await environment.assets.libraryItems()
        XCTAssertTrue(revokedItems.isEmpty)
        do { _ = try await coordinator.createJob(sourceID: legacyID, scopeJSON: "{}"); XCTFail("Legacy copying resumed") }
        catch { }
    }
}

extension LocalReferenceTests {
    func testLegacyPhotosPrivacyDeletionAndManualExclusionApplyToReferences() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let fixture = ReferenceFixtureProvider()
        let media = RemoteMediaService(controller: controller, referenceProvider: fixture)
        let environment = AppEnvironment(persistence: controller, remoteMedia: media)
        _ = try environment.sources.ensureLocalPhotosSource()
        let context = controller.container.viewContext
        func insert(_ entity: String, _ values: [String: Any]) -> NSManagedObject {
            let row = NSEntityDescription.insertNewObject(forEntityName: entity, into: context)
            for (key, value) in values { row.setValue(value, forKey: key) }
            return row
        }
        let oldSource = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Source", id: SourceRepository.localPhotosID))
        let oldPhoto = insert("SourceAsset", ["id": "legacy.photo", "remote_key": "photo/fixture", "revision": "old", "access_state": "accessible", "last_seen_at": 0, "created_at": 0, "updated_at": 0])
        oldPhoto.setValue(oldSource, forKey: "source")
        let oldAsset = insert("Asset", ["id": "legacy.asset", "content_hash": "legacy-hash", "byte_size": RemoteIndexFixture.pixels.count, "format": "png", "pixel_width": 8, "pixel_height": 8, "exif_orientation": 1, "orientation_class": "square", "captured_at_source": "unknown", "imported_at": 0, "original_rel_path": "legacy.png", "readiness": "ready", "privacy_excluded": true, "created_at": 0, "updated_at": 0])
        let origin = insert("AssetOrigin", ["imported_at": 0, "policy_state": "allowed", "origin_state": "active", "created_at": 0, "updated_at": 0])
        origin.setValue(oldPhoto, forKey: "sourceAsset")
        origin.setValue(oldAsset, forKey: "asset")
        try context.save()
        try FileManager.default.createDirectory(at: environment.originalsRootURL, withIntermediateDirectories: true)
        try RemoteIndexFixture.pixels.write(to: environment.originalsRootURL.appendingPathComponent("legacy.png"))
        _ = try await environment.connectLocalPhotoReferences(albumIDs: [])
        let records = try await media.repository.records()
        let record = try XCTUnwrap(records.first)
        XCTAssertTrue(record.blocked)
        oldAsset.setValue(false, forKey: "privacy_excluded")
        oldAsset.setValue(17, forKey: "deleted_at")
        try context.save()
        let deleted = try await media.repository.record(id: record.descriptor.sourceAssetID)
        XCTAssertTrue(try XCTUnwrap(deleted).blocked)
        oldAsset.setValue(nil, forKey: "deleted_at")
        try context.save()
        let frameID = try await environment.frameManager.createFrame(name: "Excluded reference", storageMode: .local)
        let detail = try await environment.frameManager.frameDetail(frameID: frameID)
        _ = try await environment.frames.save(frameID: frameID, baseVersion: detail.selectionVersion, addIncludes: [MediaReference(runtimeID: record.descriptor.runtimeID).selection], addExcludes: [SelectionRefEdit(assetId: "legacy.asset", sourceAssetId: nil)])
        let excluded = try await environment.frames.resolve(frameID: frameID)
        XCTAssertTrue(excluded.playable.isEmpty)
        let reverseFrame = try await environment.frameManager.createFrame(name: "Excluded private copy", storageMode: .local)
        let reverseDetail = try await environment.frameManager.frameDetail(frameID: reverseFrame)
        try await environment.frameManager.setCover(frameID: reverseFrame, assetID: "legacy.asset")
        _ = try await environment.frames.save(frameID: reverseFrame, baseVersion: reverseDetail.selectionVersion, addIncludes: [SelectionRefEdit(assetId: "legacy.asset", sourceAssetId: nil)], addExcludes: [MediaReference(runtimeID: record.descriptor.runtimeID).selection])
        let reverse = try await environment.frames.resolve(frameID: reverseFrame)
        XCTAssertTrue(reverse.allowed.isEmpty)
        XCTAssertTrue(reverse.playable.isEmpty)
        let afterExclusion = try await environment.frameManager.frameDetail(frameID: reverseFrame)
        XCTAssertNil(afterExclusion.coverAssetId)
        let referenceFolders = try await environment.assets.collections().filter { $0.sourceID == record.descriptor.sourceID }
        let aiStore = AIFrameStore(controller: controller)
        let aiConfiguration = try await aiStore.configure(AIFrameConfiguration(frameID: reverseFrame, modelID: "fixture", modelRevision: "v1", collectionIDs: referenceFolders.map(\.id), rule: AIFrameRule(category: "cat")))
        let aiCandidates = try await aiStore.candidates(configuration: aiConfiguration)
        let candidate = try XCTUnwrap(aiCandidates.first)
        let (_, token) = try await aiStore.begin(frameID: reverseFrame, configuration: aiConfiguration, total: 1, skipped: 0)
        try await aiStore.record(frameID: reverseFrame, token: token, candidate: candidate, version: "fixture", match: AIFrameMatch(assetID: candidate.id, score: 1, decision: .matched, themes: [], contentVersion: candidate.contentHash), apply: true)
        try await aiStore.finish(frameID: reverseFrame, token: token, validIDs: [candidate.id], apply: true)
        let afterClassification = try await environment.frames.resolve(frameID: reverseFrame)
        XCTAssertTrue(afterClassification.allowed.isEmpty)
        XCTAssertTrue(afterClassification.playable.isEmpty)
        do { try await environment.frameManager.setCover(frameID: reverseFrame, assetID: "legacy.asset"); XCTFail("Excluded alias became cover") }
        catch { }
        let lease = try await media.acquire(runtimeID: "legacy.asset")
        XCTAssertEqual(lease.fileURL.lastPathComponent, "legacy.png")
        await lease.release()
    }

    func testUnknownMetadataRevisionsExpireAtEachRefreshGeneration() {
        let first = SystemLocalReferenceProvider.indexedRevision("unknown")
        let second = SystemLocalReferenceProvider.indexedRevision("unknown")
        XCTAssertNotEqual(first, second)
        XCTAssertTrue(SystemLocalReferenceProvider.matchesRevision("unknown", first))
        XCTAssertFalse(SystemLocalReferenceProvider.matchesRevision("changed:17", first))
    }
}

extension LocalReferenceTests {
    func testFolderMetadataRestartSourceRemovalAndGlobalHideKeepOriginals() async throws {
        let environment = environment()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let folder = root.appendingPathComponent("nested")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("fixture.png")
        try RemoteIndexFixture.pixels.write(to: file)
        let id = try await environment.connectLocalFileReferences(root: root)
        let collections = try await environment.assets.collections().filter { $0.sourceID == id }
        XCTAssertEqual(collections.count, 2)
        let all = try await environment.assets.libraryItems()
        let item = try XCTUnwrap(all.first)
        XCTAssertEqual(all.count, 1)
        let restarted = RemoteMediaService(controller: environment.persistence)
        let image = try await restarted.image(runtimeID: item.id, mode: .local, width: 64, height: 64)
        XCTAssertNotNil(image.cgImage)
        try await environment.assets.hideAssetGlobally(id: item.id)
        _ = try await restarted.references.index(sourceID: id)
        let hidden = try await environment.assets.libraryItems()
        XCTAssertTrue(hidden.isEmpty)
        let hiddenAllowed = await restarted.authorized(runtimeID: item.id)
        XCTAssertFalse(hiddenAllowed)
        try await environment.disconnectSource(sourceID: id, keepCopies: true, removeConfiguration: true)
        let sources = try environment.sources.referenceSources()
        XCTAssertFalse(sources.contains { $0.id == id })
        XCTAssertEqual(try Data(contentsOf: file), RemoteIndexFixture.pixels)
        XCTAssertFalse(FileManager.default.fileExists(atPath: environment.originalsRootURL.path))
    }
}


extension LocalReferenceTests {
    func testUnknownReferenceAcquisitionsAndEmbeddingCacheAlwaysReadCurrentData() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let fixture = ReferenceFixtureProvider()
        await fixture.useUnknownRevision()
        let media = RemoteMediaService(controller: controller, referenceProvider: fixture)
        _ = try await media.references.connectPhotos()
        let records = try await media.repository.records()
        let record = try XCTUnwrap(records.first)
        let first = try await media.acquire(runtimeID: record.descriptor.runtimeID)
        let firstURL = first.fileURL
        await first.release()
        XCTAssertFalse(FileManager.default.fileExists(atPath: firstURL.path))
        let second = try await media.acquire(runtimeID: record.descriptor.runtimeID)
        XCTAssertNotEqual(firstURL, second.fileURL)
        await second.release()
        let reads = await fixture.bodyReads
        XCTAssertEqual(reads, 2)
        let store = AIFrameStore(controller: controller)
        let version = SHA256Hex.digest(data: Data(record.descriptor.identity.utf8)) + ":fixture"
        try await store.cacheVector([1, 0], assetID: record.descriptor.runtimeID, version: version)
        let cached = try await store.cachedVector(assetID: record.descriptor.runtimeID, version: version, expectedDimension: 2)
        XCTAssertNil(cached)
    }
}

extension LocalReferenceTests {
    func testNativeSourceReferenceScreenshots() async throws {
        let previousLanguage = AppLanguage.overrideValue
        defer { AppLanguage.configure(value: previousLanguage) }
        let environment = environment()
        let input = FileManager.default.temporaryDirectory.appendingPathComponent("Reference fixtures " + UUID().uuidString)
        try FileManager.default.createDirectory(at: input, withIntermediateDirectories: true)
        for index in 1...4 { try RemoteIndexFixture.pixels.write(to: input.appendingPathComponent("fixture-\(index).png")) }
        _ = try await environment.connectLocalFileReferences(root: input)
        let endpoint = NasAddress.sourceFingerprint(scheme: "webdav", host: "fixture.invalid", port: 443, path: "photos")
        let sourceID = try environment.sources.ensureWebdavSource(endpointKey: endpoint, displayName: "Fixture NAS", recursive: true, directories: [""])
        try await environment.assets.disconnectSource(sourceID: sourceID, keepCopies: true)
        let items = try await environment.assets.libraryItems()
        let item = try XCTUnwrap(items.first)
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let screen = UIScreen.main.bounds.size
        let normalSize = isPad ? CGSize(width: max(screen.width, screen.height), height: min(screen.width, screen.height)) : screen
        let configurations: [(String, CGSize, Bool)] = isPad
            ? [("ipad-landscape", normalSize, false), ("ipad-landscape-dark-AXXXL", normalSize, true)]
            : [("iphone-portrait", normalSize, false), ("iphone-narrow-dark-AXXXL", CGSize(width: 320, height: 800), true)]
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        for language in ["en", "zh"] {
            AppLanguage.configure(value: language)
            for (name, size, large) in configurations {
                let window = UIWindow(frame: CGRect(origin: .zero, size: size))
                window.overrideUserInterfaceStyle = large ? .dark : .light
                window.makeKeyAndVisible()
                func prepare(_ controller: UIViewController) {
                    controller.overrideUserInterfaceStyle = large ? .dark : .light
                    window.rootViewController = controller
                    if #available(iOS 17.0, *), large { controller.traitOverrides.preferredContentSizeCategory = .accessibilityExtraExtraExtraLarge }
                    window.layoutIfNeeded()
                    controller.view.layoutIfNeeded()
                }
                func capture(_ controller: UIViewController, _ page: String) {
                    let image = UIGraphicsImageRenderer(bounds: controller.view.bounds).image { _ in
                        controller.view.drawHierarchy(in: controller.view.bounds, afterScreenUpdates: true)
                    }
                    let attachment = XCTAttachment(image: image)
                    attachment.name = language + "-" + name + "-" + page
                    attachment.lifetime = .keepAlways
                    add(attachment)
                }
                let sources = SourcesListViewController(environment: environment)
                prepare(sources)
                sources.viewWillAppear(false)
                sources.view.layoutIfNeeded()
                if let scroll = sources.view.subviews.first(where: { $0 is UIScrollView }) as? UIScrollView {
                    scroll.layoutIfNeeded()
                    scroll.setContentOffset(CGPoint(x: 0, y: max(0, scroll.contentSize.height - scroll.bounds.height)), animated: false)
                }
                capture(sources, "sources-delete")
                let form = NasAddViewController(environment: environment, prefillCatalogOnly: false)
                prepare(form)
                try await Task.sleep(nanoseconds: 100_000_000)
                form.view.layoutIfNeeded()
                func descendants(_ view: UIView) -> [UIView] { view.subviews.flatMap { [$0] + descendants($0) } }
                let online = try XCTUnwrap(descendants(form.view).first { $0.accessibilityIdentifier == "nas.storage.online" })
                let local = try XCTUnwrap(descendants(form.view).first { $0.accessibilityIdentifier == "nas.storage.local" })
                let scroll = try XCTUnwrap(descendants(form.view).compactMap { $0 as? UIScrollView }.first)
                scroll.layoutIfNeeded()
                let start = online.convert(online.bounds, to: scroll).minY
                scroll.setContentOffset(CGPoint(x: 0, y: max(0, start - 16)), animated: false)
                let visible = scroll.convert(scroll.bounds, to: form.view)
                let firstChoice = online.convert(online.bounds, to: form.view)
                let secondChoice = local.convert(local.bounds, to: form.view)
                XCTAssertTrue(visible.contains(firstChoice), "Online choice is clipped in \(name)")
                capture(form, "nas-storage")
                if !visible.contains(secondChoice) {
                    let localStart = local.convert(local.bounds, to: scroll).minY
                    scroll.setContentOffset(CGPoint(x: 0, y: max(0, localStart - 16)), animated: false)
                    let localVisible = scroll.convert(scroll.bounds, to: form.view)
                    XCTAssertTrue(localVisible.contains(local.convert(local.bounds, to: form.view)), "Local choice cannot be scrolled fully into view in \(name)")
                    capture(form, "nas-storage-local")
                }
                let library = LibraryViewController(environment: environment)
                prepare(library)
                try await Task.sleep(nanoseconds: 300_000_000)
                library.view.layoutIfNeeded()
                capture(library, "reference-library")
                let detail = PhotoDetailViewController(environment: environment, assetID: item.id)
                prepare(detail)
                try await Task.sleep(nanoseconds: 300_000_000)
                detail.view.layoutIfNeeded()
                capture(detail, "reference-detail")
                if large, let body = descendants(detail.view).compactMap({ $0 as? UIScrollView }).first {
                    body.layoutIfNeeded()
                    body.setContentOffset(CGPoint(x: 0, y: max(0, body.contentSize.height - body.bounds.height)), animated: false)
                    let hide = try XCTUnwrap(descendants(detail.view).first { $0.accessibilityLabel == L10n.string("delete.hide_globally") })
                    XCTAssertTrue(body.convert(body.bounds, to: detail.view).contains(hide.convert(hide.bounds, to: detail.view)))
                    capture(detail, "reference-detail-actions")
                }
                window.isHidden = true
            }
        }
    }
}


extension LocalReferenceTests {
    func testPrepareDedicatedSyntheticPhotoAlbum() async throws {
        guard ["PhotoPilot Local References QA", "PhotoPilot Local References QA iPad"].contains(ProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] ?? "") else { throw XCTSkip("Requires the dedicated synthetic PhotoKit simulator") }
        guard PhotoLibrarySourceAdapter.currentAccess() == .authorized else { throw XCTSkip("Requires Photos access on the dedicated synthetic simulator") }
        let expected: Set<String> = ["seed-01.png", "seed-02.png", "seed-03.png"]
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        var synthetic: [PHAsset] = []
        PHAsset.fetchAssets(with: options).enumerateObjects { asset, _, _ in
            if PHAssetResource.assetResources(for: asset).contains(where: { expected.contains($0.originalFilename) }) { synthetic.append(asset) }
        }
        XCTAssertEqual(synthetic.count, 3)
        let title = "PhotoPilot Synthetic References"
        let albums = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        var existing: PHAssetCollection?
        albums.enumerateObjects { album, _, _ in if album.localizedTitle == title { existing = album } }
        if existing == nil {
            let selection = synthetic
            try await Task.detached {
                try PHPhotoLibrary.shared().performChangesAndWait {
                    let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: title)
                    request.addAssets(selection as NSArray)
                }
            }.value
        }
    }
}


extension LocalReferenceTests {
    func testLocalModeReferenceCardsUseNeutralPhotoCounts() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let fixture = ReferenceFixtureProvider()
        let media = RemoteMediaService(controller: controller, referenceProvider: fixture)
        _ = try await media.references.connectPhotos()
        let records = try await media.repository.records()
        let record = try XCTUnwrap(records.first)
        let manager = FrameManager(controller: controller)
        let frameID = try await manager.createFrame(name: "Mixed references", storageMode: .local)
        let detail = try await manager.frameDetail(frameID: frameID)
        _ = try await FrameRepository(controller: controller).save(frameID: frameID, baseVersion: detail.selectionVersion, addIncludes: [MediaReference(runtimeID: record.descriptor.runtimeID).selection])
        let privateFrame = try await manager.createFrame(name: "Private copies", storageMode: .local)
        let cards = try await manager.frameCards()
        let referenceCard = try XCTUnwrap(cards.first { $0.id == frameID })
        let privateCard = try XCTUnwrap(cards.first { $0.id == privateFrame })
        XCTAssertTrue(referenceCard.hasReferencedDevicePhotos)
        XCTAssertEqual(FramesListViewController.playableCountText(for: referenceCard), AIText.value("0 photos", "0 张照片"))
        XCTAssertFalse(privateCard.hasReferencedDevicePhotos)
        XCTAssertEqual(FramesListViewController.playableCountText(for: privateCard), String(format: L10n.string("frames.playable_count"), 0))
    }
}

extension LocalReferenceTests {
    func testVisibleDefaultGridAndReferenceDetailClearAfterAuthorityRevocation() async throws {
        let environment = environment()
        await environment.remoteMedia.startObserving()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for name in ["first.png", "second.png"] { try RemoteIndexFixture.pixels.write(to: folder.appendingPathComponent(name)) }
        let sourceID = try await environment.connectLocalFileReferences(root: folder)
        let items = try await environment.assets.libraryItems()
        XCTAssertEqual(items.count, 2)
        let window = UIWindow(frame: UIScreen.main.bounds)
        let sourceScreen = SourcesListViewController(environment: environment)
        window.rootViewController = sourceScreen
        window.makeKeyAndVisible()
        sourceScreen.viewWillAppear(false)
        sourceScreen.view.layoutIfNeeded()
        defer { window.isHidden = true }
        func descendants(_ view: UIView) -> [UIView] { view.subviews.flatMap { [$0] + descendants($0) } }
        let reselect = try XCTUnwrap(descendants(sourceScreen.view).compactMap { $0 as? ActionButton }.first { $0.accessibilityIdentifier == sourceID + ".reselect" })
        XCTAssertFalse(reselect.isHidden)
        XCTAssertTrue(reselect.isEnabled)
        XCTAssertEqual(reselect.accessibilityLabel, AIText.value("Choose again", "重新选择"))
        XCTAssertGreaterThanOrEqual(reselect.bounds.height, 44)
        XCTAssertEqual(reselect.actions(forTarget: sourceScreen, forControlEvent: .touchUpInside), ["handleLocalSource:"])
        let grid = AssetGridViewController(environment: environment)
        window.rootViewController = grid
        for _ in 0..<50 {
            grid.view.layoutIfNeeded()
            if descendants(grid.view).contains(where: { ($0 as? AssetGridCell)?.assetID == items[0].id }) { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let collection = try XCTUnwrap(descendants(grid.view).compactMap { $0 as? UICollectionView }.first)
        XCTAssertTrue(collection.visibleCells.contains { ($0 as? AssetGridCell)?.assetID == items[0].id })
        try await environment.assets.hideAssetGlobally(id: items[0].id)
        for _ in 0..<50 {
            collection.layoutIfNeeded()
            if !collection.visibleCells.contains(where: { ($0 as? AssetGridCell)?.assetID == items[0].id }) { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertFalse(collection.visibleCells.contains { ($0 as? AssetGridCell)?.assetID == items[0].id })
        let detail = PhotoDetailViewController(environment: environment, assetID: items[1].id)
        window.rootViewController = detail
        detail.view.layoutIfNeeded()
        for _ in 0..<50 {
            if descendants(detail.view).compactMap({ $0 as? UIImageView }).contains(where: { $0.image != nil && !$0.isHidden }) { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertTrue(descendants(detail.view).compactMap { $0 as? UIImageView }.contains { $0.image != nil && !$0.isHidden })
        try await environment.assets.hideAssetGlobally(id: items[1].id)
        for _ in 0..<50 {
            if !descendants(detail.view).compactMap({ $0 as? UIImageView }).contains(where: { $0.image != nil && !$0.isHidden }) { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertFalse(descendants(detail.view).compactMap { $0 as? UIImageView }.contains { $0.image != nil && !$0.isHidden })
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("first.png")), RemoteIndexFixture.pixels)
        XCTAssertEqual(try Data(contentsOf: folder.appendingPathComponent("second.png")), RemoteIndexFixture.pixels)
    }
}

extension LocalReferenceTests {
    private func flowImage() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        try RemoteIndexFixture.pixels.write(to: url)
        return url
    }

    private func flowViews(_ view: UIView) -> [UIView] {
        [view] + view.subviews.flatMap(flowViews)
    }

    private func flowButton(_ controller: UIViewController, _ title: String) throws -> ActionButton {
        try XCTUnwrap(flowViews(controller.view).compactMap { $0 as? ActionButton }.first { $0.accessibilityLabel == title })
    }

    private func flowWindow(_ controller: UIViewController) -> UIWindow {
        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
            window.frame = scene.coordinateSpace.bounds
        } else { window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852)) }
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.loadViewIfNeeded()
        window.layoutIfNeeded()
        return window
    }

    private func flowCapture(_ window: UIWindow, name: String) {
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testConfirmedFrameCreationAndMetadataSaveAreAtomicOnFailure() async throws {
        let environment = environment()
        do {
            _ = try await environment.frames.save(frameID: UUID().uuidString, baseVersion: 0,
                addIncludes: [MediaReference(runtimeID: "remote:missing").selection], newFrameName: "Failed draft", enableRemoteMedia: true)
            XCTFail("Missing reference must fail")
        } catch { }
        let afterFailure = try await environment.frameManager.frameCards()
        XCTAssertTrue(afterFailure.isEmpty)
        let frameID = try await environment.frameManager.createFrame(name: "Original")
        do {
            _ = try await environment.frames.save(frameID: frameID, baseVersion: 0, frameName: "Unsaved name", coverAssetID: "remote:missing")
            XCTFail("Unavailable cover must fail")
        } catch { }
        let detail = try await environment.frameManager.frameDetail(frameID: frameID)
        XCTAssertEqual(detail.name, "Original")
        XCTAssertEqual(detail.selectionVersion, 0)
        let sourceID = try await environment.connectLocalFileReferences(root: flowImage())
        let items = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: sourceID)
        let item = try XCTUnwrap(items.first)
        let saved = try await environment.frames.save(frameID: UUID().uuidString, baseVersion: 0,
            addIncludes: [MediaReference(runtimeID: item.id).selection], newFrameName: "Retry", enableRemoteMedia: true)
        guard case .ok = saved else { return XCTFail("Retry must save") }
        let cards = try await environment.frameManager.frameCards()
        XCTAssertEqual(cards.count, 2)
    }

    func testFirstUseMultiFileScopeConfirmsOnceAndPlaysWithoutSettings() async throws {
        let previous = AppLanguage.overrideValue
        AppLanguage.configure(value: AppLanguage.enValue)
        defer { AppLanguage.configure(value: previous) }
        let environment = environment()
        _ = try await environment.connectLocalFileReferences(root: flowImage())
        let files = try [flowImage(), flowImage()]
        let sources = SourcesListViewController(environment: environment)
        let navigation = UINavigationController(rootViewController: sources)
        let window = flowWindow(navigation)
        defer { window.isHidden = true }
        sources.documentPicker(UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false), didPickDocumentsAt: files)
        for _ in 0..<50 {
            if navigation.topViewController is FrameEditViewController { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let editor = try XCTUnwrap(navigation.topViewController as? FrameEditViewController)
        try await Task.sleep(nanoseconds: 700_000_000)
        let draftCards = try await environment.frameManager.frameCards()
        XCTAssertTrue(draftCards.isEmpty)
        XCTAssertTrue(flowViews(editor.view).compactMap { $0 as? UITextField }.allSatisfy(\.isHidden))
        let grid = try XCTUnwrap(editor.children.compactMap { $0 as? AssetGridViewController }.first)
        window.layoutIfNeeded()
        grid.view.layoutIfNeeded()
        let visible = flowViews(grid.view).compactMap { $0 as? AssetGridCell }.compactMap(\.assetID)
        XCTAssertEqual(Set(visible).count, 2)
        let selected = Set(visible)
        XCTAssertEqual(grid.selectedAssetIDs, selected)
        flowCapture(window, name: "photo-flow-exact-files-confirmation")
        let save = try flowButton(editor, "Confirm and play")
        save.sendActions(for: .touchUpInside)
        save.sendActions(for: .touchUpInside)
        for _ in 0..<50 {
            if window.rootViewController is MainContainerViewController { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let main = try XCTUnwrap(window.rootViewController as? MainContainerViewController)
        let cards = try await environment.frameManager.frameCards()
        XCTAssertEqual(cards.count, 1)
        let detail = try await environment.frameManager.frameDetail(frameID: XCTUnwrap(cards.first).id)
        XCTAssertEqual(Set(detail.includeSourceAssetRefs.map { "remote:" + $0 }), selected)
        for _ in 0..<20 {
            if main.presentedViewController is PlayerViewController { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(main.presentedViewController is PlayerViewController)
        XCTAssertFalse(main.presentedViewController is PlaybackSettingsViewController)
    }

    func testExistingFrameAddSourceKeepsDraftAndAppendsConfirmedPhotos() async throws {
        let environment = environment()
        let first = try await environment.connectLocalFileReferences(root: flowImage())
        let second = try await environment.connectLocalFileReferences(root: flowImage())
        let firstItems = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: first)
        let firstID = try XCTUnwrap(firstItems.first?.id)
        let secondItems = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: second)
        let secondID = try XCTUnwrap(secondItems.first?.id)
        let frameID = try await environment.frameManager.createFrame(name: "Original")
        _ = try await environment.frames.save(frameID: frameID, baseVersion: 0, addIncludes: [MediaReference(runtimeID: firstID).selection])
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
        let window = flowWindow(editor)
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 500_000_000)
        let field = try XCTUnwrap(flowViews(editor.view).compactMap { $0 as? UITextField }.first)
        field.text = "Draft name"
        field.sendActions(for: .editingChanged)
        let add = try flowButton(editor, AIText.value("Add photos from a source", "从来源添加照片"))
        add.sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 500_000_000)
        let navigation = try XCTUnwrap(editor.presentedViewController as? UINavigationController)
        let sources = try XCTUnwrap(navigation.topViewController as? SourcesListViewController)
        sources.onConnected?([second], [])
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertEqual(field.text, "Draft name")
        flowCapture(window, name: "photo-flow-existing-frame-source-draft")
        let grid = try XCTUnwrap(editor.children.compactMap { $0 as? AssetGridViewController }.first)
        XCTAssertEqual(grid.selectedAssetIDs, [firstID])
        grid.onSelectionChange?([firstID, secondID])
        try flowButton(editor, L10n.string("common.save")).sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 700_000_000)
        let detail = try await environment.frameManager.frameDetail(frameID: frameID)
        XCTAssertEqual(detail.name, "Draft name")
        XCTAssertEqual(Set(detail.includeSourceAssetRefs.map { "remote:" + $0 }), [firstID, secondID])
        let savedCards = try await environment.frameManager.frameCards()
        XCTAssertEqual(savedCards.count, 1)
    }

    func testManagementConnectionReturnsWithoutCreatingFrame() async throws {
        let environment = environment()
        _ = try await environment.frameManager.createFrame(name: "Existing")
        let source = SourcesListViewController(environment: environment)
        let navigation = UINavigationController(rootViewController: source)
        let window = flowWindow(navigation)
        defer { window.isHidden = true }
        source.documentPicker(UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false), didPickDocumentsAt: [try flowImage()])
        try await Task.sleep(nanoseconds: 800_000_000)
        XCTAssertTrue(navigation.topViewController === source)
        XCTAssertEqual((source.presentedViewController as? UIAlertController)?.title, AIText.value("Source connected", "来源已连接"))
        let savedCards = try await environment.frameManager.frameCards()
        XCTAssertEqual(savedCards.count, 1)
    }

    func testPushedEditorBackPopsWithoutCreatingEmptyFrame() async throws {
        let environment = environment()
        let parent = UIViewController()
        let navigation = UINavigationController(rootViewController: parent)
        let window = flowWindow(navigation)
        defer { window.isHidden = true }
        let editor = FrameEditViewController(environment: environment, frameID: nil, routeToMainAfterSave: true)
        navigation.pushViewController(editor, animated: false)
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(navigation.isNavigationBarHidden)
        XCTAssertFalse(navigation.interactivePopGestureRecognizer?.isEnabled ?? true)
        try flowButton(editor, L10n.string("common.back")).sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 400_000_000)
        XCTAssertTrue(navigation.topViewController === parent)
        let draftCards = try await environment.frameManager.frameCards()
        XCTAssertTrue(draftCards.isEmpty)
    }

    func testEmptyConfirmationAndDirtyAIKeepDraft() async throws {
        let environment = environment()
        let frameID = try await environment.frameManager.createFrame(name: "Original")
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
        let window = flowWindow(editor)
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 300_000_000)
        let field = try XCTUnwrap(flowViews(editor.view).compactMap { $0 as? UITextField }.first)
        field.text = "Unsaved draft"
        field.sendActions(for: .editingChanged)
        let ai = try flowButton(editor, AIText.value("AI classification settings", "AI 分类设置"))
        ai.sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 300_000_000)
        let alert = try XCTUnwrap(editor.presentedViewController as? UIAlertController)
        XCTAssertEqual(alert.title, L10n.string("playback.unsaved_title"))
        XCTAssertEqual(field.text, "Unsaved draft")
        let persisted = try await environment.frameManager.frameDetail(frameID: frameID)
        XCTAssertEqual(persisted.name, "Original")
        editor.dismiss(animated: false)
        try await Task.sleep(nanoseconds: 100_000_000)
        try flowButton(editor, L10n.string("common.save")).sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertTrue(editor.presentedViewController is UIAlertController)
        let savedCards = try await environment.frameManager.frameCards()
        XCTAssertEqual(savedCards.count, 1)
    }
}

extension LocalReferenceTests {
    func testReauthorizationChooseContentReturnsToRetainedTargetDraft() async throws {
        UIView.setAnimationsEnabled(false)
        defer { UIView.setAnimationsEnabled(true) }
        let environment = environment()
        let file = try flowImage()
        let sourceID = try await environment.connectLocalFileReferences(root: file)
        let items = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: sourceID)
        let selected = Set(items.map(\.id))
        let frameID = try await environment.frameManager.createFrame(name: "Original")
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
        let window = flowWindow(editor)
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 300_000_000)
        let name = try XCTUnwrap(flowViews(editor.view).compactMap { $0 as? UITextField }.first)
        name.text = "Retained draft"
        name.sendActions(for: .editingChanged)
        let grid = try XCTUnwrap(editor.children.compactMap { $0 as? AssetGridViewController }.first)
        grid.onSelectionChange?(selected)
        let add = try flowButton(editor, AIText.value("Add photos from a source", "从来源添加照片"))
        add.sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 400_000_000)
        let navigation = try XCTUnwrap(editor.presentedViewController as? UINavigationController)
        let sources = try XCTUnwrap(navigation.topViewController as? SourcesListViewController)
        let reselect = try XCTUnwrap(flowViews(sources.view).compactMap { $0 as? ActionButton }.first { $0.accessibilityIdentifier == sourceID + ".reselect" })
        reselect.sendActions(for: .touchUpInside)
        try await Task.sleep(nanoseconds: 400_000_000)
        let sheet = try XCTUnwrap(navigation.presentedViewController ?? sources.presentedViewController)
        sheet.dismiss(animated: false)
        try await Task.sleep(nanoseconds: 200_000_000)
        sources.documentPicker(UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false), didPickDocumentsAt: [file])
        try await Task.sleep(nanoseconds: 500_000_000)
        let result = try XCTUnwrap((navigation.presentedViewController ?? sources.presentedViewController) as? UIAlertController)
        XCTAssertEqual(result.title, AIText.value("Source connected", "来源已连接"))
        XCTAssertTrue(navigation.topViewController === sources)
        result.dismiss(animated: false)
        try await Task.sleep(nanoseconds: 200_000_000)
        sources.chooseContent(sourceIDs: [sourceID], initialSelection: selected)
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertNil(editor.presentedViewController)
        XCTAssertTrue(window.rootViewController === editor)
        XCTAssertEqual(name.text, "Retained draft")
        XCTAssertEqual(grid.selectedAssetIDs, selected)
        let cards = try await environment.frameManager.frameCards()
        XCTAssertEqual(cards.count, 1)
    }

    func testPickedDirectoryAndSpecificFilesKeepDistinctDraftSemantics() async throws {
        let environment = environment()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let first = folder.appendingPathComponent("one.png")
        let second = folder.appendingPathComponent("two.png")
        try RemoteIndexFixture.pixels.write(to: first)
        try RemoteIndexFixture.pixels.write(to: second)
        let sources = SourcesListViewController(environment: environment)
        let window = flowWindow(sources)
        defer { window.isHidden = true }
        var sourceIDs: [String] = []
        var selection: Set<String> = []
        var completed = false
        sources.onConnected = { ids, selected in
            sourceIDs = ids
            selection = selected
            completed = true
        }
        sources.documentPicker(UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false), didPickDocumentsAt: [folder])
        for _ in 0..<30 {
            if completed { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(completed)
        XCTAssertEqual(sourceIDs.count, 1)
        XCTAssertTrue(selection.isEmpty)
        completed = false
        sources.documentPicker(UIDocumentPickerViewController(forOpeningContentTypes: [.image], asCopy: false), didPickDocumentsAt: [first, second])
        for _ in 0..<30 {
            if completed { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTAssertTrue(completed)
        XCTAssertEqual(sourceIDs.count, 2)
        XCTAssertEqual(selection.count, 2)
        let cards = try await environment.frameManager.frameCards()
        XCTAssertTrue(cards.isEmpty)
    }

    func testConflictRetryAdoptsUntouchedConcurrentMetadataAndRetainsExplicitEdits() async throws {
        for editsMetadata in [false, true] {
            let environment = environment()
            var ids: [String] = []
            for _ in 0..<3 {
                let source = try await environment.connectLocalFileReferences(root: flowImage())
                let items = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: source)
                ids.append(try XCTUnwrap(items.first?.id))
            }
            let frameID = try await environment.frameManager.createFrame(name: "Original")
            _ = try await environment.frames.save(frameID: frameID, baseVersion: 0, addIncludes: [MediaReference(runtimeID: ids[0]).selection], coverAssetID: ids[0])
            let editor = FrameEditViewController(environment: environment, frameID: frameID)
            let window = flowWindow(editor)
            defer { window.isHidden = true }
            try await Task.sleep(nanoseconds: 300_000_000)
            let field = try XCTUnwrap(flowViews(editor.view).compactMap { $0 as? UITextField }.first)
            let grid = try XCTUnwrap(editor.children.compactMap { $0 as? AssetGridViewController }.first)
            grid.onSelectionChange?(Set(ids.prefix(2)))
            if editsMetadata {
                field.text = "Explicit draft"
                field.sendActions(for: .editingChanged)
                grid.onCoverStarTap?(ids[1])
                try await Task.sleep(nanoseconds: 300_000_000)
                let preview = try XCTUnwrap(editor.presentedViewController as? CoverPreviewViewController)
                try flowButton(preview, L10n.string("frames.set_cover")).sendActions(for: .touchUpInside)
                try await Task.sleep(nanoseconds: 600_000_000)
            }
            _ = try await environment.frames.save(frameID: frameID, baseVersion: 1, addIncludes: [MediaReference(runtimeID: ids[2]).selection], frameName: "Concurrent", coverAssetID: ids[2])
            try flowButton(editor, L10n.string("common.save")).sendActions(for: .touchUpInside)
            for _ in 0..<15 {
                if editor.presentedViewController is UIAlertController { break }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            XCTAssertTrue(editor.presentedViewController is UIAlertController, "Metadata edited: \(editsMetadata)")
            editor.dismiss(animated: false)
            editor.retryConflict()
            try await Task.sleep(nanoseconds: 300_000_000)
            XCTAssertEqual(field.text, editsMetadata ? "Explicit draft" : "Concurrent")
            XCTAssertEqual(grid.coverAssetID, editsMetadata ? ids[1] : ids[2])
            try flowButton(editor, L10n.string("common.save")).sendActions(for: .touchUpInside)
            try await Task.sleep(nanoseconds: 400_000_000)
            let saved = try await environment.frameManager.frameDetail(frameID: frameID)
            XCTAssertEqual(saved.name, editsMetadata ? "Explicit draft" : "Concurrent")
            XCTAssertEqual(saved.coverAssetId, editsMetadata ? ids[1] : ids[2])
            XCTAssertEqual(Set(saved.includeSourceAssetRefs.map { "remote:" + $0 }), Set(ids))
            window.isHidden = true
        }
    }
}

extension LocalReferenceTests {
    func testIPadPhotoFlowLandscapeLargeTextAndNarrowLayouts() async throws {
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "Dedicated iPad layout verification")
        let previousLanguage = AppLanguage.overrideValue
        UIView.setAnimationsEnabled(false)
        defer {
            AppLanguage.configure(value: previousLanguage)
            UIView.setAnimationsEnabled(true)
        }
        let environment = environment()
        let sourceID = try await environment.connectLocalFileReferences(root: flowImage())
        let items = try await environment.assets.libraryItems(includeRemote: true, remoteSourceID: sourceID)
        let ids = Set(items.map(\.id))
        let frameID = try await environment.frameManager.createFrame(name: "Frame")
        _ = try await environment.frames.save(frameID: frameID, baseVersion: 0, addIncludes: ids.map { MediaReference(runtimeID: $0).selection })
        let configurations: [(String, CGSize, Bool)] = [
            ("landscape", CGSize(width: 1180, height: 820), false),
            ("landscape-AXXXL", CGSize(width: 1180, height: 820), true),
            ("portrait-AXXXL", CGSize(width: 820, height: 1180), true),
            ("narrow-AXXXL", CGSize(width: 320, height: 800), true),
        ]
        for language in [AppLanguage.enValue, AppLanguage.zhValue] {
            AppLanguage.configure(value: language)
            for (configuration, size, large) in configurations {
                for existing in [false, true] {
                    let editor = FrameEditViewController(environment: environment, frameID: existing ? frameID : nil,
                        routeToMainAfterSave: !existing, sourceIDs: [sourceID], initialSelection: existing ? [] : ids)
                    let window = UIWindow(frame: CGRect(origin: .zero, size: size))
                    window.rootViewController = editor
                    if #available(iOS 17.0, *) {
                        editor.traitOverrides.preferredContentSizeCategory = large ? .accessibilityExtraExtraExtraLarge : .large
                        editor.traitOverrides.horizontalSizeClass = size.width < 600 ? .compact : .regular
                    }
                    window.makeKeyAndVisible()
                    editor.loadViewIfNeeded()
                    try await Task.sleep(nanoseconds: 200_000_000)
                    window.layoutIfNeeded()
                    editor.view.layoutIfNeeded()
                    let tag = language + "-" + configuration + (existing ? "-existing" : "-first")
                    let image = UIGraphicsImageRenderer(bounds: editor.view.bounds).image { context in
                        editor.view.layer.render(in: context.cgContext)
                    }
                    let attachment = XCTAttachment(image: image)
                    attachment.name = "photo-flow-ipad-" + tag
                    attachment.lifetime = .keepAlways
                    add(attachment)
                    let save = try flowButton(editor, existing ? L10n.string("common.save") : AIText.value("Confirm and play", "确认并播放"))
                    let add = try flowButton(editor, AIText.value("Add photos from a source", "从来源添加照片"))
                    let grid = try XCTUnwrap(editor.children.compactMap { $0 as? AssetGridViewController }.first)
                    let saveRect = save.convert(save.bounds, to: editor.view)
                    let addRect = add.convert(add.bounds, to: editor.view)
                    let gridRect = grid.view.convert(grid.view.bounds, to: editor.view)
                    print("PHOTO_FLOW_LAYOUT", tag, "size", size, "safe", editor.view.safeAreaInsets, "header", editor.view.subviews.first(where: { $0.accessibilityIdentifier == "frame.editor.header" })?.frame as Any, "grid", gridRect, "save", saveRect, "add", addRect)
                    XCTAssertTrue(editor.view.bounds.contains(saveRect), tag + " save clipped")
                    let header = try XCTUnwrap(flowViews(editor.view).compactMap { $0 as? UIScrollView }.first { $0.accessibilityIdentifier == "frame.editor.header" })
                    let addContentRect = add.convert(add.bounds, to: header)
                    header.scrollRectToVisible(addContentRect, animated: false)
                    editor.view.layoutIfNeeded()
                    XCTAssertTrue(header.bounds.contains(add.convert(add.bounds, to: header)), tag + " add-source cannot scroll fully into view")
                    let addPoint = add.convert(CGPoint(x: add.bounds.midX, y: add.bounds.midY), to: window)
                    let addHit = window.hitTest(addPoint, with: nil)
                    XCTAssertTrue(addHit === add || addHit?.isDescendant(of: add) == true, tag + " add-source unreachable")
                    if size.width < 600 {
                        let scrolledImage = UIGraphicsImageRenderer(bounds: editor.view.bounds).image { context in
                            editor.view.layer.render(in: context.cgContext)
                        }
                        let scrolledAttachment = XCTAttachment(image: scrolledImage)
                        scrolledAttachment.name = "photo-flow-ipad-" + tag + "-add-source-scrolled"
                        scrolledAttachment.lifetime = .keepAlways
                        self.add(scrolledAttachment)
                    }
                    let back = try flowButton(editor, L10n.string("common.back"))
                    header.scrollRectToVisible(back.convert(back.bounds, to: header), animated: false)
                    XCTAssertTrue(header.bounds.contains(back.convert(back.bounds, to: header)), tag + " back cannot scroll fully into view")
                    XCTAssertGreaterThanOrEqual(gridRect.height, 120, tag + " grid crowded")
                    XCTAssertGreaterThanOrEqual(gridRect.width, 44, tag + " grid width")
                    let point = save.convert(CGPoint(x: save.bounds.midX, y: save.bounds.midY), to: window)
                    let hit = window.hitTest(point, with: nil)
                    XCTAssertTrue(hit === save || hit?.isDescendant(of: save) == true, tag + " save unreachable")
                    window.isHidden = true
                }
            }
        }
    }
}
