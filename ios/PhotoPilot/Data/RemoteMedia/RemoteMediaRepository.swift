import CoreData
import Foundation

struct RemoteMediaRecord: Sendable {
    let descriptor: RemoteMediaDescriptor
    let sourceName: String
    let sourceStatus: String
    let accessState: String
    let policyAllowed: Bool
    let blocked: Bool
    let localAssetID: String?
    let localRelativePath: String?
    let localContentHash: String?
    let indexedAt: Int64
    var referenceOnly = false
    var cacheable: Bool { !referenceOnly || (!descriptor.revision.isEmpty && !descriptor.revision.hasPrefix("unknown:")) }

    var remoteAllowed: Bool { !blocked && policyAllowed && sourceStatus == "active" && accessState == "accessible" }
    var localReady: Bool { !blocked && policyAllowed && localAssetID != nil && localRelativePath != nil }
}

actor RemoteMediaRepository {
    let controller: PersistenceController
    let originals: URL

    init(controller: PersistenceController) {
        self.controller = controller
        originals = controller.storeURL.deletingLastPathComponent().appendingPathComponent("originals", isDirectory: true)
    }

    func records(sourceID: String? = nil, collectionID: String? = nil, recursive: Bool = true) async throws -> [RemoteMediaRecord] {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            try Self.records(context: context, originals: self.originals, sourceID: sourceID, collectionID: collectionID, recursive: recursive)
        }
    }

    func record(id: String) async throws -> RemoteMediaRecord? {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return nil }
            return try Self.record(context: context, row: row, originals: self.originals)
        }
    }

    func authorized(_ descriptor: RemoteMediaDescriptor, allowLocal: Bool = false) async -> Bool {
        guard let current = try? await record(id: descriptor.sourceAssetID), current.descriptor == descriptor else { return false }
        return current.remoteAllowed || (allowLocal && current.localReady)
    }

    func index(sourceID: String, adapter: any SourceAdapter) async throws -> Int {
        let started = Int(Date().timeIntervalSince1970 * 1000)
        let context = controller.container.newBackgroundContext()
        let epoch = try await context.perform {
            guard let source = try Self.fetch(context, entity: "Source", id: sourceID), source.value(forKey: "status") as? String == "active" else { throw RemoteMediaError.revoked }
            return source.value(forKey: "epoch") as? Int ?? 1
        }
        var cursor: String?
        var count = 0
        repeat {
            try Task.checkCancellation()
            let page = try await adapter.enumerateAssets(cursor: cursor)
            try Task.checkCancellation()
            try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
            try await context.perform {
                guard let source = try Self.fetch(context, entity: "Source", id: sourceID),
                      source.value(forKey: "status") as? String == "active", source.value(forKey: "epoch") as? Int == epoch else { throw RemoteMediaError.revoked }
                let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
                request.predicate = NSPredicate(format: "source.id == %@", sourceID)
                var existing = Dictionary(uniqueKeysWithValues: try context.fetch(request).map { (Data(($0.value(forKey: "remote_key") as? String ?? "").utf8), $0) })
                for item in page.assets {
                    guard let path = RemoteMediaPath.normalized(item.remoteID), !path.isEmpty,
                          !AssetFileProber.videoExtensions.contains((path as NSString).pathExtension.lowercased()) else { continue }
                    let key = Data(path.utf8)
                    let row = existing[key] ?? NSEntityDescription.insertNewObject(forEntityName: "SourceAsset", into: context)
                    if existing[key] == nil {
                        row.setValue(UUID().uuidString, forKey: "id")
                        row.setValue(source, forKey: "source")
                        row.setValue(path, forKey: "remote_key")
                        row.setValue(started, forKey: "created_at")
                        existing[key] = row
                    }
                    row.setValue(item.revision, forKey: "revision")
                    row.setValue(item.byteSize, forKey: "byte_size")
                    row.setValue(item.remoteTakenAtMillis, forKey: "remote_taken_at")
                    row.setValue(started, forKey: "last_seen_at")
                    row.setValue("accessible", forKey: "access_state")
                    row.setValue(0, forKey: "missing_scan_count")
                    row.setValue(started, forKey: "updated_at")
                    try Self.ensureFolders(context: context, source: source, remoteKey: path, now: started)
                }
                _ = try PersistenceController.bumpLibraryVersionValue(in: context)
                try context.save()
            }
            }
            count += page.assets.count
            cursor = page.nextCursor
        } while cursor != nil
        try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
        try await context.perform {
            guard let source = try Self.fetch(context, entity: "Source", id: sourceID), source.value(forKey: "epoch") as? Int == epoch,
                  source.value(forKey: "status") as? String == "active" else { throw RemoteMediaError.revoked }
            let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            request.predicate = NSPredicate(format: "source.id == %@ AND last_seen_at < %lld", sourceID, Int64(started))
            for row in try context.fetch(request) { row.setValue("missing", forKey: "access_state") }
            source.setValue(started, forKey: "last_sync_at")
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
        }
        return count
    }

    static func records(context: NSManagedObjectContext, originals: URL, sourceID: String? = nil, collectionID: String? = nil, recursive: Bool = true) throws -> [RemoteMediaRecord] {
        var sourceID = sourceID
        var memberIDs: Set<String>?
        var folder: String?
        if let collectionID {
            guard let collection = try fetch(context, entity: "Collection", id: collectionID) else { return [] }
            sourceID = collection.value(forKeyPath: "source.id") as? String
            guard let source = collection.value(forKey: "source") as? NSManagedObject else { return [] }
            if LocalReferenceService.isReference(source), source.value(forKey: "type") as? String == "local_photos" {
                let membership = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
                membership.predicate = NSPredicate(format: "key == %@", "reference.membership." + collectionID)
                guard let json = try context.fetch(membership).first?.value(forKey: "value_json") as? String,
                      let value = try? JSONDecoder().decode(LocalReferenceMembership.self, from: Data(json.utf8)), value.version == 1,
                      value.sourceID == sourceID, value.epoch == source.value(forKey: "epoch") as? Int else { return [] }
                memberIDs = Set(value.sourceAssetIDs)
            } else {
                guard collection.value(forKey: "type") as? String == "source_folder" else { return [] }
                folder = collection.value(forKey: "rel_path") as? String ?? ""
            }
        }
        let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
        request.predicate = sourceID.map { NSPredicate(format: "source.id == %@", $0) }
            ?? NSPredicate(format: "source.type IN %@", ["webdav", "smb", "local_photos", "local_files"])
        var result: [RemoteMediaRecord] = []
        for row in try context.fetch(request) {
            let path = row.value(forKey: "remote_key") as? String ?? ""
            if let memberIDs, !memberIDs.contains(row.value(forKey: "id") as? String ?? "") { continue }
            if let folder, !RemoteMediaPath.contains(folder: folder, remoteKey: path, recursive: recursive) { continue }
            if AssetFileProber.videoExtensions.contains((path as NSString).pathExtension.lowercased()) { continue }
            if let item = try record(context: context, row: row, originals: originals) { result.append(item) }
        }
        return result.sorted { $0.descriptor.runtimeID < $1.descriptor.runtimeID }
    }

    static func record(context: NSManagedObjectContext, row: NSManagedObject, originals: URL) throws -> RemoteMediaRecord? {
        guard let source = row.value(forKey: "source") as? NSManagedObject,
              (["smb", "webdav"].contains(source.value(forKey: "type") as? String ?? "") || LocalReferenceService.isReference(source)),
              let id = row.value(forKey: "id") as? String,
              let sourceID = source.value(forKey: "id") as? String,
              let path = row.value(forKey: "remote_key") as? String,
              !AssetFileProber.videoExtensions.contains((path as NSString).pathExtension.lowercased()),
              RemoteMediaPath.normalized(path) != nil else { return nil }
        let epoch = source.value(forKey: "epoch") as? Int ?? 1
        let revision = row.value(forKey: "revision") as? String ?? ""
        var origins = row.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
        if LocalReferenceService.isReference(source), source.value(forKey: "type") as? String == "local_photos" {
            let legacy = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
            legacy.predicate = NSPredicate(format: "sourceAsset.source.id == %@ AND sourceAsset.remote_key == %@", SourceRepository.localPhotosID, path)
            origins.formUnion(try context.fetch(legacy))
        }
        var blocked = origins.contains { origin in
            guard let asset = origin.value(forKey: "asset") as? NSManagedObject else { return false }
            return asset.value(forKey: "privacy_excluded") as? Bool == true || asset.value(forKey: "deleted_at") != nil
                || origin.value(forKey: "policy_state") as? String == "revoked"
        }
        var fingerprints: Set<String> = [ImportCoordinator.sourceFingerprint(source)]
        if source.value(forKey: "type") as? String == "local_photos" {
            fingerprints.insert("local_photos:" + SourceRepository.localPhotosID)
        }
        let tombstones = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
        tombstones.predicate = NSPredicate(format: "source_fingerprint IN %@ AND remote_id == %@", Array(fingerprints), path)
        if try context.fetch(tombstones).contains(where: { Data(($0.value(forKey: "remote_id") as? String ?? "").utf8) == Data(path.utf8) }) { blocked = true }
        let policyAllowed = source.value(forKey: "retention_policy") as? String == "user_managed" || origins.contains { $0.value(forKey: "policy_state") as? String == "allowed" }
        var local: NSManagedObject?
        for origin in origins.sorted(by: { ($0.value(forKeyPath: "asset.id") as? String ?? "") < ($1.value(forKeyPath: "asset.id") as? String ?? "") }) {
            let originState = origin.value(forKey: "origin_state") as? String
            let retained = originState == "detached" && source.value(forKey: "status") as? String == "disconnected"
            guard origin.value(forKey: "policy_state") as? String == "allowed", originState == "active" || retained,
                  let asset = origin.value(forKey: "asset") as? NSManagedObject,
                  asset.value(forKey: "readiness") as? String == "ready",
                  let assetID = asset.value(forKey: "id") as? String,
                  let hash = asset.value(forKey: "content_hash") as? String,
                  let relative = asset.value(forKey: "original_rel_path") as? String,
                  FileManager.default.fileExists(atPath: originals.appendingPathComponent(relative).path),
                  let provenance = try provenance(context: context, assetID: assetID, sourceAssetID: id),
                  provenance.sourceEpoch == epoch, provenance.importedRevision == revision, provenance.contentHash == hash else { continue }
            local = asset
            break
        }
        return RemoteMediaRecord(
            descriptor: RemoteMediaDescriptor(sourceID: sourceID, sourceEpoch: epoch, sourceAssetID: id, revision: revision, remoteKey: path,
                byteSize: (row.value(forKey: "byte_size") as? NSNumber)?.int64Value, takenAt: (row.value(forKey: "remote_taken_at") as? NSNumber)?.int64Value),
            sourceName: source.value(forKey: "display_name") as? String ?? "",
            sourceStatus: source.value(forKey: "status") as? String ?? "", accessState: row.value(forKey: "access_state") as? String ?? "",
            policyAllowed: policyAllowed, blocked: blocked, localAssetID: local?.value(forKey: "id") as? String,
            localRelativePath: local?.value(forKey: "original_rel_path") as? String, localContentHash: local?.value(forKey: "content_hash") as? String,
            indexedAt: Int64(row.value(forKey: "last_seen_at") as? Int ?? 0), referenceOnly: LocalReferenceService.isReference(source))
    }

    static func referenceAliases(context: NSManagedObjectContext, runtimeID: String) throws -> Set<String> {
        let reference = MediaReference(runtimeID: runtimeID)
        let request = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        switch reference {
        case .asset(let id): request.predicate = NSPredicate(format: "asset.id == %@", id)
        case .sourceAsset(let id): request.predicate = NSPredicate(format: "sourceAsset.id == %@", id)
        }
        var result: Set<String> = [runtimeID]
        for row in try context.fetch(request) {
            if let id = row.value(forKeyPath: "asset.id") as? String { result.insert(id) }
            if let id = row.value(forKeyPath: "sourceAsset.id") as? String { result.insert("remote:" + id) }
        }
        var photoKeys = Set<String>()
        if case .sourceAsset(let id) = reference,
           let sourceAsset = try fetch(context, entity: "SourceAsset", id: id), sourceAsset.value(forKeyPath: "source.type") as? String == "local_photos",
           let key = sourceAsset.value(forKey: "remote_key") as? String { photoKeys.insert(key) }
        for origin in try context.fetch(request) where origin.value(forKeyPath: "sourceAsset.source.type") as? String == "local_photos" {
            if let key = origin.value(forKeyPath: "sourceAsset.remote_key") as? String { photoKeys.insert(key) }
        }
        if !photoKeys.isEmpty {
            let photos = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            let sources = NSFetchRequest<NSManagedObject>(entityName: "Source")
            sources.predicate = NSPredicate(format: "type == %@", "local_photos")
            photos.predicate = NSPredicate(format: "source IN %@ AND remote_key IN %@", try context.fetch(sources), Array(photoKeys))
            for photo in try context.fetch(photos) {
                if let id = photo.value(forKey: "id") as? String { result.insert("remote:" + id) }
                for origin in photo.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [] {
                    if let id = origin.value(forKeyPath: "asset.id") as? String { result.insert(id) }
                }
            }
        }
        return result
    }

    static func isExcluded(context: NSManagedObjectContext, frameID: String, runtimeID: String) throws -> Bool {
        let aliases = try referenceAliases(context: context, runtimeID: runtimeID).map { MediaReference(runtimeID: $0).rawID }
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        request.predicate = NSPredicate(format: "frame.id == %@ AND mode == %@ AND ref_key IN %@", frameID, "exclude", aliases)
        return try context.count(for: request) > 0
    }

    static func provenance(context: NSManagedObjectContext, assetID: String, sourceAssetID: String) throws -> RemoteImportProvenance? {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", RemoteImportProvenance.key(assetID: assetID, sourceAssetID: sourceAssetID))
        guard let json = try context.fetch(request).first?.value(forKey: "value_json") as? String else { return nil }
        return try? JSONDecoder().decode(RemoteImportProvenance.self, from: Data(json.utf8))
    }

    static func writeProvenance(context: NSManagedObjectContext, asset: NSManagedObject, sourceAsset: NSManagedObject, frameScoped: Bool) throws {
        let assetID = asset.value(forKey: "id") as? String ?? ""
        let sourceID = sourceAsset.value(forKey: "id") as? String ?? ""
        let previous = try provenance(context: context, assetID: assetID, sourceAssetID: sourceID)
        let value = RemoteImportProvenance(sourceEpoch: sourceAsset.value(forKeyPath: "source.epoch") as? Int ?? 1,
            importedRevision: sourceAsset.value(forKey: "revision") as? String ?? "", contentHash: asset.value(forKey: "content_hash") as? String ?? "", frameScoped: frameScoped && (previous?.frameScoped ?? true))
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", RemoteImportProvenance.key(assetID: assetID, sourceAssetID: sourceID))
        let setting = try context.fetch(request).first ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
        setting.setValue(RemoteImportProvenance.key(assetID: assetID, sourceAssetID: sourceID), forKey: "key")
        setting.setValue(String(decoding: try JSONEncoder().encode(value), as: UTF8.self), forKey: "value_json")
        let now = Int(Date().timeIntervalSince1970 * 1000)
        if setting.value(forKey: "created_at") == nil { setting.setValue(now, forKey: "created_at") }
        setting.setValue(now, forKey: "updated_at")
    }

    static func fetch(_ context: NSManagedObjectContext, entity: String, id: String) throws -> NSManagedObject? {
        let request = NSFetchRequest<NSManagedObject>(entityName: entity)
        request.predicate = NSPredicate(format: "id == %@", id)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private static func ensureFolders(context: NSManagedObjectContext, source: NSManagedObject, remoteKey: String, now: Int) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(format: "source == %@ AND type == %@", source, "source_folder")
        var folders = Dictionary(uniqueKeysWithValues: try context.fetch(request).map { (Data(($0.value(forKey: "rel_path") as? String ?? "").utf8), $0) })
        let segments = remoteKey.split(separator: "/").dropLast()
        var paths = [""]
        var path = ""
        for segment in segments {
            path = path.isEmpty ? String(segment) : path + "/" + segment
            paths.append(path)
        }
        var parent: NSManagedObject?
        for path in paths {
            let key = Data(path.utf8)
            let folder = folders[key] ?? NSEntityDescription.insertNewObject(forEntityName: "Collection", into: context)
            if folders[key] == nil {
                folder.setValue(UUID().uuidString, forKey: "id")
                folder.setValue(source, forKey: "source")
                folder.setValue(path.isEmpty ? source.value(forKey: "display_name") : (path as NSString).lastPathComponent, forKey: "name")
                folder.setValue(path, forKey: "rel_path")
                folder.setValue(path, forKey: "sort_key")
                folder.setValue("source_folder", forKey: "type")
                folder.setValue(now, forKey: "created_at")
                folders[key] = folder
            }
            folder.setValue(parent, forKey: "parent")
            folder.setValue(now, forKey: "updated_at")
            parent = folder
        }
    }
}
