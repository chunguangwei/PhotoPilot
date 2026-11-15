import CoreData
import Foundation
import Photos
import UIKit

struct LocalReferenceSource: Sendable {
    let id: String
    let type: String
    let settings: String
    let epoch: Int
}

struct LocalReferenceItem: Sendable {
    let key: String
    let revision: String
    let takenAt: Int?
}

struct LocalReferenceFolder: Sendable {
    let key: String
    let name: String
    let members: [String]
}

struct LocalReferenceSnapshot: Sendable {
    let items: [LocalReferenceItem]
    let folders: [LocalReferenceFolder]
}

struct LocalReferenceMembership: Codable {
    let version: Int
    let sourceID: String
    let epoch: Int
    let sourceAssetIDs: [String]
}

protocol LocalReferenceProvider: Sendable {
    func snapshot(source: LocalReferenceSource) async throws -> LocalReferenceSnapshot
    func image(source: LocalReferenceSource, key: String, revision: String, width: Int, height: Int) async throws -> UIImage
    func authorized(source: LocalReferenceSource, key: String, revision: String) async -> Bool
}

private final class LocalPhotoImageRequest: @unchecked Sendable {
    private let lock = NSLock()
    private var requestID = PHInvalidImageRequestID
    private var continuation: CheckedContinuation<UIImage, Error>?
    private var finished = false

    func begin(_ continuation: CheckedContinuation<UIImage, Error>) -> Bool {
        lock.lock()
        if finished {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return false
        }
        self.continuation = continuation
        lock.unlock()
        return true
    }

    func install(_ id: PHImageRequestID) {
        lock.lock()
        requestID = id
        let cancel = finished
        lock.unlock()
        if cancel { PHImageManager.default().cancelImageRequest(id) }
    }

    func complete(_ result: Result<UIImage, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let callback = continuation
        continuation = nil
        lock.unlock()
        callback?.resume(with: result)
    }

    func cancel() {
        lock.lock()
        let id = requestID
        lock.unlock()
        complete(.failure(CancellationError()))
        if id != PHInvalidImageRequestID { PHImageManager.default().cancelImageRequest(id) }
    }
}

struct SystemLocalReferenceProvider: LocalReferenceProvider {
    var networkPolicy: NasNetworkPolicy?
    static func settings(_ source: LocalReferenceSource) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(source.settings.utf8)) as? [String: Any]) ?? [:]
    }

    static func photoRevision(_ asset: PHAsset) -> String {
        guard let modified = asset.modificationDate else { return "unknown" }
        return "\(modified.timeIntervalSince1970):\(asset.pixelWidth)x\(asset.pixelHeight)"
    }

    static func indexedRevision(_ value: String) -> String { value == "unknown" ? "unknown:" + UUID().uuidString : value }
    static func matchesRevision(_ actual: String, _ expected: String) -> Bool { actual == expected || (actual == "unknown" && expected.hasPrefix("unknown:")) }

    static func photo(_ key: String) throws -> PHAsset {
        guard [.authorized, .limited].contains(PhotoLibrarySourceAdapter.currentAccess()),
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [key], options: nil).firstObject,
              asset.mediaType == .image else { throw RemoteMediaError.revoked }
        return asset
    }

    static func withFile<T>(_ source: LocalReferenceSource, key: String?, operation: (URL) throws -> T) throws -> T {
        guard let value = settings(source)["bookmark"] as? String, let data = Data(base64Encoded: value) else { throw RemoteMediaError.revoked }
        var stale = false
        let root = try URL(resolvingBookmarkData: data, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
        guard !stale else { throw RemoteMediaError.revoked }
        let scoped = root.startAccessingSecurityScopedResource()
        defer { if scoped { root.stopAccessingSecurityScopedResource() } }
        let isDirectory = (try root.resourceValues(forKeys: [.isDirectoryKey])).isDirectory == true
        let url: URL
        if let key, isDirectory {
            guard let normalized = RemoteMediaPath.normalized(key), !normalized.isEmpty else { throw RemoteMediaError.revoked }
            url = root.appendingPathComponent(normalized)
            guard url.resolvingSymlinksInPath().path.hasPrefix(root.resolvingSymlinksInPath().path + "/") else { throw RemoteMediaError.revoked }
        } else { url = root }
        var result: Result<T, Error>?
        var coordinationError: NSError?
        NSFileCoordinator().coordinate(readingItemAt: url, options: .withoutChanges, error: &coordinationError) { coordinated in
            result = Result { try operation(coordinated) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw RemoteMediaError.unavailable }
        return try result.get()
    }

    static func fileRevision(_ url: URL) throws -> String {
        let values = try url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey, .isDirectoryKey])
        guard values.isRegularFile != false, values.isDirectory != true else { throw RemoteMediaError.revoked }
        guard let modified = values.contentModificationDate, let size = values.fileSize else { return "unknown" }
        return "\(modified.timeIntervalSince1970):\(size)"
    }

    func snapshot(source: LocalReferenceSource) async throws -> LocalReferenceSnapshot {
        if source.type == "local_photos" {
            guard [.authorized, .limited].contains(PhotoLibrarySourceAdapter.currentAccess()) else { throw RemoteMediaError.revoked }
            let selected = Self.settings(source)["albumIDs"] as? [String] ?? []
            var items: [String: LocalReferenceItem] = [:]
            var folders: [LocalReferenceFolder] = []
            func add(_ result: PHFetchResult<PHAsset>, key: String, name: String) {
                var members: [String] = []
                result.enumerateObjects { asset, _, _ in
                    guard asset.mediaType == .image else { return }
                    members.append(asset.localIdentifier)
                    items[asset.localIdentifier] = LocalReferenceItem(key: asset.localIdentifier, revision: Self.indexedRevision(Self.photoRevision(asset)), takenAt: asset.creationDate.map { Int($0.timeIntervalSince1970 * 1000) })
                }
                folders.append(LocalReferenceFolder(key: key, name: name, members: members))
            }
            if selected.isEmpty {
                let options = PHFetchOptions()
                options.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
                add(PHAsset.fetchAssets(with: options), key: "all", name: AIText.value("All accessible photos", "所有可访问照片"))
            }
            let collections = selected.isEmpty
                ? PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
                : PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: selected, options: nil)
            collections.enumerateObjects { collection, _, _ in
                add(PHAsset.fetchAssets(in: collection, options: nil), key: collection.localIdentifier, name: collection.localizedTitle ?? AIText.value("Album", "相册"))
            }
            return LocalReferenceSnapshot(items: Array(items.values), folders: folders)
        }
        return try Self.withFile(source, key: nil) { root in
            let directory = (try root.resourceValues(forKeys: [.isDirectoryKey])).isDirectory == true
            let urls: [URL]
            if directory {
                let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey], options: [.skipsHiddenFiles])
                urls = (enumerator?.allObjects as? [URL] ?? []).filter {
                    (try? $0.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true
                }
            } else { urls = [root] }
            var items: [LocalReferenceItem] = []
            var folders: [String: [String]] = ["": []]
            for url in urls where LocalFilesFixtureAdapter.mediaExtensions.contains(url.pathExtension.lowercased()) {
                try Task.checkCancellation()
                guard (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) != false else { continue }
                let key = directory ? String(url.path.dropFirst(root.path.count + 1)) : "file"
                items.append(LocalReferenceItem(key: key, revision: Self.indexedRevision(try Self.fileRevision(url)), takenAt: nil))
                let parent = (key as NSString).deletingLastPathComponent
                folders[parent, default: []].append(key)
                var ancestor = parent
                while !ancestor.isEmpty {
                    ancestor = (ancestor as NSString).deletingLastPathComponent
                    if folders[ancestor] == nil { folders[ancestor] = [] }
                }
            }
            return LocalReferenceSnapshot(items: items, folders: folders.map { path, members in
                LocalReferenceFolder(key: path, name: path.isEmpty ? root.lastPathComponent : (path as NSString).lastPathComponent, members: members)
            })
        }
    }

    func authorized(source: LocalReferenceSource, key: String, revision: String) async -> Bool {
        if source.type == "local_photos" {
            guard let current = try? Self.photoRevision(Self.photo(key)) else { return false }
            return Self.matchesRevision(current, revision)
        }
        guard let current = try? Self.withFile(source, key: key, operation: { try Self.fileRevision($0) }) else { return false }
        return Self.matchesRevision(current, revision)
    }

    func image(source: LocalReferenceSource, key: String, revision: String, width: Int, height: Int) async throws -> UIImage {
        try Task.checkCancellation()
        let result: UIImage?
        if source.type == "local_photos" {
            let asset = try Self.photo(key)
            guard Self.matchesRevision(Self.photoRevision(asset), revision) else { throw RemoteMediaError.changed }
            let request = LocalPhotoImageRequest()
            let networkAllowed = networkPolicy?.allowsTransfer ?? false
            result = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    guard request.begin(continuation) else { return }
                    let options = PHImageRequestOptions()
                    options.deliveryMode = .highQualityFormat
                    options.resizeMode = .exact
                    options.version = .current
                    options.isNetworkAccessAllowed = networkAllowed
                    let id = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: max(1, width), height: max(1, height)), contentMode: .aspectFit, options: options) { image, info in
                        guard (info?[PHImageResultIsDegradedKey] as? Bool) != true else { return }
                        if let error = info?[PHImageErrorKey] as? Error { request.complete(.failure(error)) }
                        else if let image { request.complete(.success(image)) }
                        else { request.complete(.failure(RemoteMediaError.unavailable)) }
                    }
                    request.install(id)
                }
            } onCancel: { request.cancel() }
        } else {
            result = try Self.withFile(source, key: key) { url in
                guard try Self.matchesRevision(Self.fileRevision(url), revision) else { throw RemoteMediaError.changed }
                return ImageRenderer.thumbnail(fileURL: url, maxPixelWidth: max(1, width), maxPixelHeight: max(1, height)).map { UIImage(cgImage: $0) }
            }
        }
        try Task.checkCancellation()
        guard await authorized(source: source, key: key, revision: revision) else { throw RemoteMediaError.revoked }
        guard let image = result, let input = image.cgImage,
              let context = CGContext(data: nil, width: input.width, height: input.height, bitsPerComponent: 8, bytesPerRow: input.width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { throw RemoteMediaError.invalidFile }
        context.draw(input, in: CGRect(x: 0, y: 0, width: input.width, height: input.height))
        guard let decoded = context.makeImage() else { throw RemoteMediaError.invalidFile }
        return UIImage(cgImage: decoded)
    }
}

private final class LocalPhotoLibraryObservation: NSObject, PHPhotoLibraryChangeObserver, @unchecked Sendable {
    private let changed: @Sendable () -> Void

    init(changed: @escaping @Sendable () -> Void) {
        self.changed = changed
        super.init()
        PHPhotoLibrary.shared().register(self)
    }

    func photoLibraryDidChange(_ changeInstance: PHChange) { changed() }
    deinit { PHPhotoLibrary.shared().unregisterChangeObserver(self) }
}

actor LocalReferenceService {
    let controller: PersistenceController
    let provider: any LocalReferenceProvider
    private var photoObservation: LocalPhotoLibraryObservation?
    private var indexTasks: [String: Task<Int, Error>] = [:]
    private var indexTokens: [String: UUID] = [:]
    private var activeIndexes: [UUID: (String, Task<Int, Error>)] = [:]
    private var erasing = false
    private var indexed: (@Sendable () async -> Void)?

    func setIndexCompletion(_ completion: @escaping @Sendable () async -> Void) { indexed = completion }

    func startObserving() {
        guard photoObservation == nil, [.authorized, .limited].contains(PhotoLibrarySourceAdapter.currentAccess()) else { return }
        photoObservation = LocalPhotoLibraryObservation { [weak self] in
            Task { await self?.refreshAll() }
        }
    }

    init(controller: PersistenceController, provider: any LocalReferenceProvider = SystemLocalReferenceProvider()) {
        self.controller = controller
        self.provider = provider
    }

    static func isReference(_ source: NSManagedObject) -> Bool {
        guard ["local_photos", "local_files"].contains(source.value(forKey: "type") as? String ?? ""),
              let json = source.value(forKey: "settings_json") as? String,
              let settings = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { return false }
        return settings["referenceOnly"] as? Bool == true
    }

    func source(id: String) async throws -> LocalReferenceSource {
        guard !erasing else { throw CancellationError() }
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard let row = try RemoteMediaRepository.fetch(context, entity: "Source", id: id), Self.isReference(row), row.value(forKey: "status") as? String != "disconnected" else { throw RemoteMediaError.revoked }
            return LocalReferenceSource(id: id, type: row.value(forKey: "type") as? String ?? "", settings: row.value(forKey: "settings_json") as? String ?? "{}", epoch: row.value(forKey: "epoch") as? Int ?? 1)
        }
    }

    @discardableResult
    func connectPhotos(albumIDs: [String] = []) async throws -> String {
        startObserving()
        return try await connect(id: "source.reference.photos", type: "local_photos", name: AIText.value("Photos on this device", "本机照片"), settings: ["referenceOnly": true, "albumIDs": albumIDs])
    }

    @discardableResult
    func connectFiles(root: URL) async throws -> String {
        let scoped = root.startAccessingSecurityScopedResource()
        defer { if scoped { root.stopAccessingSecurityScopedResource() } }
        let bookmark = try root.bookmarkData(options: [.minimalBookmark], includingResourceValuesForKeys: nil, relativeTo: nil)
        var stale = false
        _ = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
        guard !stale else { throw RemoteMediaError.revoked }
        let id = "source.reference.files." + SHA256Hex.digest(data: Data(root.standardizedFileURL.path.utf8)).prefix(24)
        return try await connect(id: id, type: "local_files", name: root.lastPathComponent, settings: ["referenceOnly": true, "bookmark": bookmark.base64EncodedString()])
    }

    @discardableResult
    func connect(id: String, type: String, name: String, settings: [String: Any]) async throws -> String {
        guard !erasing else { throw CancellationError() }
        let json = String(decoding: try JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys]), as: UTF8.self)
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            let row = try RemoteMediaRepository.fetch(context, entity: "Source", id: id) ?? NSEntityDescription.insertNewObject(forEntityName: "Source", into: context)
            let now = Int(Date().timeIntervalSince1970 * 1000)
            let changed = row.value(forKey: "settings_json") as? String != json
            if row.value(forKey: "id") == nil {
                row.setValue(id, forKey: "id")
                row.setValue(type, forKey: "type")
                row.setValue(name, forKey: "display_name")
                row.setValue(0, forKey: "capability_flags")
                row.setValue("user_managed", forKey: "retention_policy")
                row.setValue(0, forKey: "epoch")
                row.setValue(now, forKey: "created_at")
            }
            if changed { row.setValue((row.value(forKey: "epoch") as? Int ?? 0) + 1, forKey: "epoch") }
            row.setValue("active", forKey: "status")
            row.setValue(json, forKey: "settings_json")
            row.setValue(now, forKey: "updated_at")
            try context.save()
        }
        _ = try await index(sourceID: id)
        return id
    }

    @discardableResult
    func index(sourceID: String) async throws -> Int {
        guard !erasing else { throw CancellationError() }
        let predecessor = indexTasks[sourceID]
        let token = UUID()
        let task = Task {
            if let predecessor { _ = try? await predecessor.value }
            try Task.checkCancellation()
            return try await self.performIndex(sourceID: sourceID)
        }
        indexTasks[sourceID] = task
        indexTokens[sourceID] = token
        activeIndexes[token] = (sourceID, task)
        defer {
            activeIndexes[token] = nil
            if indexTokens[sourceID] == token {
                indexTasks[sourceID] = nil
                indexTokens[sourceID] = nil
            }
        }
        let result = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
        await indexed?()
        return result
    }

    private func performIndex(sourceID: String) async throws -> Int {
        let source = try await source(id: sourceID)
        let snapshot: LocalReferenceSnapshot
        do { snapshot = try await provider.snapshot(source: source) }
        catch {
            if error is CancellationError || Task.isCancelled { throw CancellationError() }
            try await setUnavailable(sourceID: sourceID)
            throw error
        }
        try Task.checkCancellation()
        let context = controller.container.newBackgroundContext()
        try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
            try await context.perform {
                guard let row = try RemoteMediaRepository.fetch(context, entity: "Source", id: sourceID), Self.isReference(row), row.value(forKey: "epoch") as? Int == source.epoch else { throw RemoteMediaError.changed }
                let now = Int(Date().timeIntervalSince1970 * 1000)
                let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
                request.predicate = NSPredicate(format: "source == %@", row)
                var existing = Dictionary(uniqueKeysWithValues: try context.fetch(request).map { ($0.value(forKey: "remote_key") as? String ?? "", $0) })
                var keys: [String: String] = [:]
                for item in snapshot.items {
                    let asset = existing.removeValue(forKey: item.key) ?? NSEntityDescription.insertNewObject(forEntityName: "SourceAsset", into: context)
                    if asset.value(forKey: "id") == nil {
                        asset.setValue("ref." + SHA256Hex.digest(data: Data((sourceID + "\u{0}" + item.key).utf8)), forKey: "id")
                        asset.setValue(row, forKey: "source")
                        asset.setValue(item.key, forKey: "remote_key")
                        asset.setValue(now, forKey: "created_at")
                    }
                    asset.setValue(item.revision, forKey: "revision")
                    asset.setValue(item.takenAt, forKey: "remote_taken_at")
                    asset.setValue("accessible", forKey: "access_state")
                    asset.setValue(now, forKey: "last_seen_at")
                    asset.setValue(now, forKey: "updated_at")
                    keys[item.key] = asset.value(forKey: "id") as? String
                }
                for asset in existing.values { asset.setValue("missing", forKey: "access_state") }
                var collections: [String: NSManagedObject] = [:]
                for folder in snapshot.folders {
                    let id = "ref.collection." + SHA256Hex.digest(data: Data((sourceID + "\u{0}" + folder.key).utf8))
                    let collection = try RemoteMediaRepository.fetch(context, entity: "Collection", id: id) ?? NSEntityDescription.insertNewObject(forEntityName: "Collection", into: context)
                    collection.setValue(id, forKey: "id")
                    collection.setValue(row, forKey: "source")
                    collection.setValue(folder.name, forKey: "name")
                    collection.setValue(source.type == "local_photos" ? "album_map" : "source_folder", forKey: "type")
                    collection.setValue(folder.key, forKey: "rel_path")
                    collection.setValue(folder.key, forKey: "sort_key")
                    if collection.value(forKey: "created_at") == nil { collection.setValue(now, forKey: "created_at") }
                    collection.setValue(now, forKey: "updated_at")
                    collections[folder.key] = collection
                    let membership = LocalReferenceMembership(version: 1, sourceID: sourceID, epoch: source.epoch, sourceAssetIDs: folder.members.compactMap { keys[$0] })
                    let key = "reference.membership." + id
                    let lookup = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
                    lookup.predicate = NSPredicate(format: "key == %@", key)
                    let setting = try context.fetch(lookup).first ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
                    setting.setValue(key, forKey: "key")
                    setting.setValue(String(decoding: try JSONEncoder().encode(membership), as: UTF8.self), forKey: "value_json")
                    if setting.value(forKey: "created_at") == nil { setting.setValue(now, forKey: "created_at") }
                    setting.setValue(now, forKey: "updated_at")
                }
                for collection in row.value(forKey: "collections") as? Set<NSManagedObject> ?? [] {
                    guard let id = collection.value(forKey: "id") as? String, !collections.values.contains(collection) else { continue }
                    let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
                    request.predicate = NSPredicate(format: "key == %@", "reference.membership." + id)
                    if let setting = try context.fetch(request).first {
                        let empty = LocalReferenceMembership(version: 1, sourceID: sourceID, epoch: source.epoch, sourceAssetIDs: [])
                        setting.setValue(String(decoding: try JSONEncoder().encode(empty), as: UTF8.self), forKey: "value_json")
                    }
                }
                if source.type == "local_files" {
                    for (path, collection) in collections where !path.isEmpty { collection.setValue(collections[(path as NSString).deletingLastPathComponent], forKey: "parent") }
                }
                row.setValue("active", forKey: "status")
                row.setValue(now, forKey: "last_sync_at")
                _ = try PersistenceController.bumpLibraryVersionValue(in: context)
                try context.save()
            }
        }
        return snapshot.items.count
    }

    func cancelIndex(sourceID: String) async {
        let tasks = activeIndexes.values.filter { $0.0 == sourceID }.map { $0.1 }
        for task in tasks { task.cancel() }
        for task in tasks { _ = try? await task.value }
    }

    func prepareForErase() async {
        erasing = true
        indexed = nil
        photoObservation = nil
        let tasks = activeIndexes.values.map { $0.1 }
        for task in tasks { task.cancel() }
        for task in tasks { _ = try? await task.value }
        activeIndexes.removeAll()
        indexTasks.removeAll()
        indexTokens.removeAll()
    }

    private func setUnavailable(sourceID: String) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard let row = try RemoteMediaRepository.fetch(context, entity: "Source", id: sourceID) else { return }
            row.setValue("unavailable", forKey: "status")
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
    }

    func refreshAll() async {
        let context = controller.container.newBackgroundContext()
        let ids = (try? await context.perform {
            try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Source")).filter { Self.isReference($0) && $0.value(forKey: "status") as? String != "disconnected" }.compactMap { $0.value(forKey: "id") as? String }
        }) ?? []
        for id in ids { _ = try? await index(sourceID: id) }
    }
}
