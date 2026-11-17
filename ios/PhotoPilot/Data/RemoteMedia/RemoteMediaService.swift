import CoreData
import Foundation
import UIKit

extension Notification.Name {
    static let remoteMediaChanged = Notification.Name("PhotoPilot.remoteMediaChanged")
}

struct RemoteSourceConnection: Sendable {
    let id: String
    let type: String
    let endpointKey: String
    let displayName: String
    let directories: [String]
    let recursive: Bool
    let catalogOnly: Bool
}

private final class RemoteMediaObservation: @unchecked Sendable {
    private var tokens: [NSObjectProtocol] = []
    private let lock = NSLock()
    private var sensitiveContexts: Set<ObjectIdentifier> = []

    init(controller: PersistenceController, changed: @escaping @Sendable (Bool) -> Void) {
        let coordinator = controller.container.persistentStoreCoordinator
        let center = NotificationCenter.default
        tokens.append(center.addObserver(forName: .NSManagedObjectContextObjectsDidChange, object: nil, queue: nil) { [weak self] notification in
            guard let context = notification.object as? NSManagedObjectContext, context.persistentStoreCoordinator === coordinator else { return }
            let rows = (notification.userInfo?[NSUpdatedObjectsKey] as? Set<NSManagedObject> ?? [])
                .union(notification.userInfo?[NSInsertedObjectsKey] as? Set<NSManagedObject> ?? [])
                .union(notification.userInfo?[NSDeletedObjectsKey] as? Set<NSManagedObject> ?? [])
            let keys = Set(["privacy_excluded", "deleted_at", "revision", "access_state", "epoch", "status", "policy_state", "origin_state"])
            let sensitive = rows.contains { row in
                row.entity.name == "LocalTombstone" || (["Source", "SourceAsset", "Asset", "AssetOrigin"].contains(row.entity.name ?? "") &&
                    (row.isDeleted || !keys.isDisjoint(with: row.changedValues().keys)))
            }
            if sensitive { self?.mark(context) }
        })
        tokens.append(center.addObserver(forName: .NSManagedObjectContextDidSave, object: nil, queue: nil) { [weak self] notification in
            guard let context = notification.object as? NSManagedObjectContext, context.persistentStoreCoordinator === coordinator else { return }
            let sensitive = self?.take(context) ?? false
            let rows = (notification.userInfo?[NSInsertedObjectsKey] as? Set<NSManagedObject> ?? [])
                .union(notification.userInfo?[NSUpdatedObjectsKey] as? Set<NSManagedObject> ?? [])
                .union(notification.userInfo?[NSDeletedObjectsKey] as? Set<NSManagedObject> ?? [])
            if sensitive || rows.contains(where: { ["Frame", "FrameSelection", "FrameDirectoryRule", "Source", "SourceAsset", "LocalTombstone"].contains($0.entity.name ?? "") }) { changed(sensitive) }
        })
    }

    private func mark(_ context: NSManagedObjectContext) {
        lock.lock()
        sensitiveContexts.insert(ObjectIdentifier(context))
        lock.unlock()
    }

    private func take(_ context: NSManagedObjectContext) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return sensitiveContexts.remove(ObjectIdentifier(context)) != nil
    }

    deinit { for token in tokens { NotificationCenter.default.removeObserver(token) } }
}

private enum RemoteMediaServices {
    static let lock = NSLock()
    nonisolated(unsafe) static var values: [String: RemoteMediaService] = [:]

    static func forget(storeURL: URL) {
        lock.lock()
        values.removeValue(forKey: storeURL.path)
        lock.unlock()
    }

    static func service(controller: PersistenceController) -> RemoteMediaService {
        lock.lock()
        defer { lock.unlock() }
        let key = controller.storeURL.path
        if let value = values[key] { return value }
        let value = RemoteMediaService(controller: controller)
        values[key] = value
        return value
    }
}

private enum ReferenceTransientDirectory {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var prepared: Set<String> = []

    static func root(storeURL: URL) -> URL {
        let root = storeURL.deletingLastPathComponent().appendingPathComponent("cache/reference-transient", isDirectory: true)
        lock.lock()
        defer { lock.unlock() }
        if prepared.insert(root.path).inserted { try? FileManager.default.removeItem(at: root) }
        return root
    }
}

actor RemoteMediaService {
    typealias AdapterFactory = @Sendable (RemoteSourceConnection) throws -> any RemoteReadableSource
    let repository: RemoteMediaRepository
    let references: LocalReferenceService
    private let transientRoot: URL
    let cache: RemoteMediaCache
    let controller: PersistenceController
    let diskReservations: RemoteDiskReservations
    let transfers = RemoteTransferGate(limit: 2)
    let localTransfers = RemoteTransferGate(limit: 1)
    private let network: NasNetworkPolicy
    private let factory: AdapterFactory?
    private var observation: RemoteMediaObservation?
    private var indexed: (@Sendable () async -> Void)?
    private var indexTasks: [String: Task<Int, Error>] = [:]
    private var erasing = false
    private var learnedHashes: [String: String] = [:]

    static func shared(controller: PersistenceController) -> RemoteMediaService { RemoteMediaServices.service(controller: controller) }

    init(controller: PersistenceController, cacheRoot: URL? = nil, budget: Int64 = RemoteMediaCache.budgetBytes, adapterFactory: AdapterFactory? = nil, referenceProvider: (any LocalReferenceProvider)? = nil) {
        self.controller = controller
        transientRoot = ReferenceTransientDirectory.root(storeURL: controller.storeURL)
        diskReservations = RemoteDiskReservations(root: controller.storeURL.deletingLastPathComponent())
        repository = RemoteMediaRepository(controller: controller)
        let settings = AppSettingsRepository(container: controller.container)
        let policy = NasNetworkPolicy(settings: settings)
        network = policy
        references = LocalReferenceService(controller: controller, provider: referenceProvider ?? SystemLocalReferenceProvider(networkPolicy: policy))
        factory = adapterFactory
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let key = SHA256Hex.digest(data: Data(controller.storeURL.path.utf8))
        cache = RemoteMediaCache(root: cacheRoot ?? base.appendingPathComponent("PhotoPilot/remote-media/" + key), budget: budget)
    }

    func startObserving() {
        Task { await references.startObserving() }
        guard observation == nil else { return }
        observation = RemoteMediaObservation(controller: controller) { [weak self] sensitive in
            Task { await self?.refreshAuthority(sensitive: sensitive) }
        }
    }

    func setIndexCompletion(_ completion: @escaping @Sendable () async -> Void) async {
        indexed = completion
        await references.setIndexCompletion(completion)
    }

    private func refreshAuthority(sensitive: Bool) async {
        guard !erasing else { return }
        if sensitive {
            await cache.clear()
            learnedHashes.removeAll()
            RemoteDecodedImages.shared.clear()
        } else {
            _ = await cache.invalidateUnauthorized { [repository] descriptor in await repository.authorized(descriptor) }
        }
        if sensitive {
            await MainActor.run {
                PlayerImageLoader.clearMemoryCache()
                ThumbnailLoader.clearMemoryCache()
            }
        }
        await MainActor.run {
            NotificationCenter.default.post(name: .remoteMediaChanged, object: nil, userInfo: ["revoked": sensitive])
        }
    }

    func connection(sourceID: String) async throws -> RemoteSourceConnection {
        guard !erasing else { throw CancellationError() }
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard let source = try RemoteMediaRepository.fetch(context, entity: "Source", id: sourceID),
                  source.value(forKey: "status") as? String == "active",
                  let endpoint = source.value(forKey: "endpoint_key") as? String else { throw RemoteMediaError.unavailable }
            let raw = source.value(forKey: "settings_json") as? String ?? "{}"
            let settings = (try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any]) ?? [:]
            return RemoteSourceConnection(id: sourceID, type: source.value(forKey: "type") as? String ?? "", endpointKey: endpoint,
                displayName: source.value(forKey: "display_name") as? String ?? "", directories: settings["directories"] as? [String] ?? [],
                recursive: settings["recursive"] as? Bool ?? true, catalogOnly: settings["catalogOnly"] as? Bool ?? false)
        }
    }

    func adapter(sourceID: String) async throws -> any RemoteReadableSource {
        let info = try await connection(sourceID: sourceID)
        if let factory { return try factory(info) }
        guard let endpoint = NasAddress.parseFingerprint(info.endpointKey) else { throw RemoteMediaError.unavailable }
        let credential = CredentialStore().load(fingerprint: info.endpointKey)
        let pins = TofuPinStore(settings: AppSettingsRepository(container: controller.container))
        let permitted = NasTlsPolicy.isCleartextPermitted(host: endpoint.host, pinStore: pins)
        if info.type == "smb" {
            let split = NasPath.splitShare(credential?.path ?? endpoint.path)
            return SmbSourceAdapter(config: SmbConfig(host: endpoint.host, port: endpoint.port, share: split.share,
                username: credential?.username ?? "", password: credential?.password ?? ""),
                directories: info.directories, recursive: info.recursive, networkPolicy: network, cleartextPermitted: permitted)
        }
        var components = URLComponents()
        components.scheme = endpoint.scheme == "webdav" ? "https" : "http"
        components.host = NasAddress.urlHost(endpoint.host)
        components.port = endpoint.port
        components.path = "/" + endpoint.path
        guard let url = components.url else { throw RemoteMediaError.unavailable }
        let (session, policy) = WebDavClient.makeSession(pinStore: pins)
        let client = WebDavClient(config: WebDavClient.Config(baseURL: url, username: credential?.username ?? "", password: credential?.password ?? "",
            cleartextPermitted: permitted), session: session, tlsPolicy: policy, networkPolicy: network)
        return WebDavSourceAdapter(client: client, scopeJSON: NasScope.scopeJSON(info.directories) ?? "{}", recursive: info.recursive)
    }

    @discardableResult
    func index(sourceID: String) async throws -> Int {
        if (try? await references.source(id: sourceID)) != nil { return try await references.index(sourceID: sourceID) }
        if let active = indexTasks[sourceID] { return try await active.value }
        let adapter = try await adapter(sourceID: sourceID)
        let task = Task { try await repository.index(sourceID: sourceID, adapter: adapter) }
        indexTasks[sourceID] = task
        do {
            let result = try await task.value
            indexTasks[sourceID] = nil
            await indexed?()
            return result
        } catch {
            indexTasks[sourceID] = nil
            throw error
        }
    }

    func acquire(runtimeID: String, mode: FrameStorageMode = .remote, priority: RemoteMediaPriority = .visible) async throws -> RemoteMediaLease {
        try Task.checkCancellation()
        guard !erasing else { throw CancellationError() }
        switch MediaReference(runtimeID: runtimeID) {
        case .asset(let id):
            let context = controller.container.newBackgroundContext()
            let info = try await context.perform { () -> (String, String) in
                guard let asset = try RemoteMediaRepository.fetch(context, entity: "Asset", id: id),
                      asset.value(forKey: "privacy_excluded") as? Bool != true, asset.value(forKey: "deleted_at") == nil,
                      asset.value(forKey: "readiness") as? String == "ready",
                      let path = asset.value(forKey: "original_rel_path") as? String else { throw RemoteMediaError.revoked }
                let origins = asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                guard origins.contains(where: { $0.value(forKey: "policy_state") as? String == "allowed" && $0.value(forKey: "origin_state") as? String == "active" }) else { throw RemoteMediaError.revoked }
                return (path, asset.value(forKey: "content_hash") as? String ?? "")
            }
            let url = repository.originals.appendingPathComponent(info.0)
            guard FileManager.default.fileExists(atPath: url.path) else { throw RemoteMediaError.localCopyMissing }
            return RemoteMediaLease(fileURL: url, descriptor: RemoteMediaDescriptor(sourceID: "", sourceEpoch: 0, sourceAssetID: id,
                revision: info.1, remoteKey: info.0, byteSize: nil, takenAt: nil))
        case .sourceAsset(let id):
            guard let record = try await repository.record(id: id), !record.blocked, record.policyAllowed else { throw RemoteMediaError.revoked }
            if record.referenceOnly {
                guard record.remoteAllowed else { throw RemoteMediaError.revoked }
                if !record.cacheable { return try await acquireTransientReference(record: record, priority: priority) }
                return try await cache.acquire(record.descriptor, priority: priority, fetch: { [references] descriptor, destination, limit in
                    let source = try await references.source(id: descriptor.sourceID)
                    guard source.epoch == descriptor.sourceEpoch else { throw RemoteMediaError.revoked }
                    let image = try await references.provider.image(source: source, key: descriptor.remoteKey, revision: descriptor.revision, width: 2048, height: 2048)
                    guard let data = image.pngData(), data.count <= min(limit, 32 * 1024 * 1024) else { throw RemoteMediaError.tooLarge }
                    try Task.checkCancellation()
                    try data.write(to: destination, options: .atomic)
                }, authorized: { [weak self] descriptor in
                    guard let self else { return false }
                    guard await self.repository.authorized(descriptor) else { return false }
                    return await self.authorized(runtimeID: descriptor.runtimeID)
                })
            }
            guard mode != .remote || record.sourceStatus != "disconnected" else { throw RemoteMediaError.revoked }
            if record.localReady, let path = record.localRelativePath {
                return RemoteMediaLease(fileURL: repository.originals.appendingPathComponent(path), descriptor: record.descriptor)
            }
            guard mode == .remote else { throw RemoteMediaError.localCopyMissing }
            guard record.remoteAllowed else { throw RemoteMediaError.unavailable }
            let lease = try await cache.acquire(record.descriptor, priority: priority, fetch: { [weak self] descriptor, destination, limit in
                guard let self else { throw CancellationError() }
                let transfer = try await self.transfers.acquire(priority: priority)
                do {
                    let space = try await self.diskReservations.acquire(bytes: limit)
                    do {
                        let adapter = try await self.adapter(sourceID: descriptor.sourceID)
                        guard await self.repository.authorized(descriptor) else { throw RemoteMediaError.revoked }
                        try await adapter.downloadRemote(remoteID: descriptor.remoteKey, to: destination, maximumBytes: limit)
                        await space.release()
                    } catch {
                        await space.release()
                        throw error
                    }
                    await transfer.release()
                } catch {
                    await transfer.release()
                    throw error
                }
            }, authorized: { [repository] descriptor in await repository.authorized(descriptor) })
            do {
                try Task.checkCancellation()
                let hash = try await Task.detached { try AssetFileProber.contentHash(of: lease.fileURL) }.value
                guard await repository.authorized(record.descriptor), !(try await blockedHash(hash)) else { throw RemoteMediaError.revoked }
                learnedHashes[record.descriptor.identity] = hash
                return lease
            } catch {
                await lease.release()
                if error as? RemoteMediaError == .revoked { await cache.clear() }
                throw error
            }
        }
    }

    private func acquireTransientReference(record: RemoteMediaRecord, priority: RemoteMediaPriority) async throws -> RemoteMediaLease {
        let transfer = try await transfers.acquire(priority: priority)
        let maximum: Int64 = 32 * 1024 * 1024
        let cacheToken: UUID
        do { cacheToken = try await cache.reserveTransient(bytes: maximum) }
        catch { await transfer.release(); throw error }
        let space: RemoteSpaceLease
        do { space = try await diskReservations.acquire(bytes: maximum) }
        catch {
            await cache.releaseTransient(cacheToken)
            await transfer.release()
            throw error
        }
        let url = transientRoot.appendingPathComponent(UUID().uuidString + ".png")
        do {
            let source = try await references.source(id: record.descriptor.sourceID)
            guard source.epoch == record.descriptor.sourceEpoch, await authorized(runtimeID: record.descriptor.runtimeID) else { throw RemoteMediaError.revoked }
            let image = try await references.provider.image(source: source, key: record.descriptor.remoteKey, revision: record.descriptor.revision, width: 2048, height: 2048)
            guard let data = image.pngData(), data.count <= maximum else { throw RemoteMediaError.tooLarge }
            try Task.checkCancellation()
            try FileManager.default.createDirectory(at: transientRoot, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var directory = transientRoot
            try directory.setResourceValues(values)
            try data.write(to: url, options: .atomic)
            guard await repository.authorized(record.descriptor), await authorized(runtimeID: record.descriptor.runtimeID) else { throw RemoteMediaError.revoked }
            return RemoteMediaLease(fileURL: url, descriptor: record.descriptor, cleanup: { [cache] in
                try? FileManager.default.removeItem(at: url)
                await cache.releaseTransient(cacheToken)
                await space.release()
                await transfer.release()
            })
        } catch {
            try? FileManager.default.removeItem(at: url)
            await cache.releaseTransient(cacheToken)
            await space.release()
            await transfer.release()
            throw error
        }
    }

    func authorized(runtimeID: String, mode: FrameStorageMode = .remote) async -> Bool {
        switch MediaReference(runtimeID: runtimeID) {
        case .asset:
            do { let lease = try await acquire(runtimeID: runtimeID, mode: mode); await lease.release(); return true }
            catch { return false }
        case .sourceAsset(let id):
            guard let record = try? await repository.record(id: id), !record.blocked, record.policyAllowed else { return false }
            if record.referenceOnly {
                guard record.remoteAllowed, let source = try? await references.source(id: record.descriptor.sourceID) else { return false }
                return await references.provider.authorized(source: source, key: record.descriptor.remoteKey, revision: record.descriptor.revision)
            }
            if let hash = learnedHashes[record.descriptor.identity], (try? await blockedHash(hash)) != false { return false }
            if mode == .remote && record.sourceStatus == "disconnected" { return false }
            return record.localReady || (mode == .remote && record.remoteAllowed)
        }
    }

    func image(runtimeID: String, mode: FrameStorageMode = .remote, width: Int, height: Int, priority: RemoteMediaPriority = .visible) async throws -> UIImage {
        try Task.checkCancellation()
        guard !erasing else { throw CancellationError() }
        if case .sourceAsset(let id) = MediaReference(runtimeID: runtimeID), let record = try await repository.record(id: id), record.referenceOnly {
            guard record.remoteAllowed else { throw RemoteMediaError.revoked }
            let source = try await references.source(id: record.descriptor.sourceID)
            guard source.epoch == record.descriptor.sourceEpoch else { throw RemoteMediaError.revoked }
            let image = try await references.provider.image(source: source, key: record.descriptor.remoteKey, revision: record.descriptor.revision, width: width, height: height)
            guard await repository.authorized(record.descriptor), await authorized(runtimeID: runtimeID, mode: mode) else { throw RemoteMediaError.revoked }
            return image
        }
        if case .sourceAsset(let id) = MediaReference(runtimeID: runtimeID), let record = try await repository.record(id: id) {
            let key = SHA256Hex.digest(data: Data(record.descriptor.identity.utf8)) + ":\(width)x\(height)"
            let generation = RemoteDecodedImages.shared.generation()
            if let image = RemoteDecodedImages.shared.image(key: key) {
                try Task.checkCancellation()
                guard await authorized(runtimeID: runtimeID, mode: mode), await repository.authorized(record.descriptor, allowLocal: true), !erasing, generation == RemoteDecodedImages.shared.generation() else { throw RemoteMediaError.revoked }
                return image
            }
        }
        if width <= 512, height <= 512,
           case .sourceAsset(let id) = MediaReference(runtimeID: runtimeID),
           let record = try await repository.record(id: id),
           let preview = try await cache.cachedPreview(record.descriptor, authorized: { [repository] in await repository.authorized($0, allowLocal: true) }) {
            do {
                guard let hash = preview.contentHash, !(try await blockedHash(hash)) else { throw RemoteMediaError.revoked }
                learnedHashes[record.descriptor.identity] = hash
                let generation = RemoteDecodedImages.shared.generation()
                let image = await Task.detached {
                    ImageRenderer.thumbnail(fileURL: preview.fileURL, maxPixelWidth: width, maxPixelHeight: height).map { UIImage(cgImage: $0) }
                }.value
                await preview.release()
                try Task.checkCancellation()
                guard generation == RemoteDecodedImages.shared.generation(), await authorized(runtimeID: runtimeID, mode: mode), await repository.authorized(record.descriptor, allowLocal: true) else { throw RemoteMediaError.revoked }
                if let image {
                    let key = SHA256Hex.digest(data: Data(record.descriptor.identity.utf8)) + ":\(width)x\(height)"
                    RemoteDecodedImages.shared.store(image, key: key, generation: generation)
                    return image
                }
            } catch {
                await preview.release()
                throw error
            }
        }
        let lease = try await acquire(runtimeID: runtimeID, mode: mode, priority: priority)
        let descriptor = lease.descriptor
        let cacheKey = SHA256Hex.digest(data: Data(descriptor.identity.utf8)) + ":\(width)x\(height)"
        let generation = RemoteDecodedImages.shared.generation()
        if let cached = RemoteDecodedImages.shared.image(key: cacheKey) {
            await lease.release()
            guard await authorized(runtimeID: runtimeID, mode: mode), await repository.authorized(descriptor, allowLocal: true) else { throw RemoteMediaError.revoked }
            return cached
        }
        let image = await Task.detached { () -> UIImage? in
            guard let thumbnail = ImageRenderer.thumbnail(fileURL: lease.fileURL, maxPixelWidth: width, maxPixelHeight: height),
                  let context = CGContext(data: nil, width: thumbnail.width, height: thumbnail.height,
                    bitsPerComponent: 8, bytesPerRow: thumbnail.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            context.draw(thumbnail, in: CGRect(x: 0, y: 0, width: thumbnail.width, height: thumbnail.height))
            return context.makeImage().map { UIImage(cgImage: $0) }
        }.value
        if image != nil { await cachePreview(lease: lease) }
        await lease.release()
        try Task.checkCancellation()
        guard generation == RemoteDecodedImages.shared.generation(), await authorized(runtimeID: runtimeID, mode: mode), await repository.authorized(descriptor, allowLocal: true) else { throw RemoteMediaError.revoked }
        guard let image else { throw RemoteMediaError.invalidFile }
        RemoteDecodedImages.shared.store(image, key: cacheKey, generation: generation)
        return image
    }

    func cachePreview(lease: RemoteMediaLease) async {
        guard !erasing, !Task.isCancelled,
              let record = try? await repository.record(id: lease.descriptor.sourceAssetID),
              !record.referenceOnly, record.descriptor == lease.descriptor,
              await repository.authorized(lease.descriptor, allowLocal: true) else { return }
        let generation = RemoteDecodedImages.shared.generation()
        let data = await Task.detached {
            ImageRenderer.thumbnail(fileURL: lease.fileURL, maxPixelWidth: 512, maxPixelHeight: 512)
                .flatMap { UIImage(cgImage: $0).jpegData(compressionQuality: 0.78) }
        }.value
        guard let data, !Task.isCancelled, generation == RemoteDecodedImages.shared.generation() else { return }
        let hash: String
        if let known = learnedHashes[lease.descriptor.identity] { hash = known }
        else {
            guard let computed = try? await Task.detached(operation: { try AssetFileProber.contentHash(of: lease.fileURL) }).value else { return }
            hash = computed
        }
        guard generation == RemoteDecodedImages.shared.generation(), (try? await blockedHash(hash)) == false else { return }
        try? await cache.storePreview(data, descriptor: lease.descriptor, contentHash: hash, authorized: { [weak self] descriptor in
            guard let self, await self.repository.authorized(descriptor, allowLocal: true), (try? await self.blockedHash(hash)) == false else { return false }
            return generation == RemoteDecodedImages.shared.generation()
        })
    }

    func cancelIndex(sourceID: String) async {
        await references.cancelIndex(sourceID: sourceID)
        let task = indexTasks[sourceID]
        task?.cancel()
        _ = try? await task?.value
        indexTasks[sourceID] = nil
    }

    func clearCache() async { await cache.clear(); RemoteDecodedImages.shared.clear() }

    func prepareForErase() async {
        erasing = true
        await references.prepareForErase()
        indexed = nil
        observation = nil
        RemoteMediaServices.forget(storeURL: controller.storeURL)
        for task in indexTasks.values { task.cancel() }
        for task in indexTasks.values { _ = try? await task.value }
        indexTasks.removeAll()
        await cache.clear()
        learnedHashes.removeAll()
    }

    private func blockedHash(_ hash: String) async throws -> Bool {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            let assets = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            assets.predicate = NSPredicate(format: "content_hash == %@ AND (privacy_excluded == YES OR deleted_at != nil)", hash)
            if try context.count(for: assets) > 0 { return true }
            let tombstones = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            tombstones.predicate = NSPredicate(format: "content_hash == %@", hash)
            return try context.count(for: tombstones) > 0
        }
    }
}
