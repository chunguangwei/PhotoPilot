import CoreData
import Foundation

struct AssetDTO: Sendable {
    let id: String
    let contentHash: String
    let byteSize: Int
    let format: String
    let pixelWidth: Int
    let pixelHeight: Int
    let exifOrientation: Int
    let orientationClass: String
    let capturedAtMillis: Int?
    let capturedAtSource: String
    let importedAt: Int
    let originalRelPath: String
    let originalFileName: String?
    let readiness: String
    let privacyExcluded: Bool
    let deletedAt: Int?
}

struct SourceAssetDTO: Sendable {
    let id: String
    let remoteKey: String
    let revision: String
    let byteSize: Int?
    let lastSeenAt: Int
    let accessState: String
}

struct FrameSelectionDTO: Sendable {
    let refKey: String
    let mode: String
    let manualOrder: Int?
    let assetId: String?
    let sourceAssetId: String?
}

struct LibraryItemDTO: Sendable {
    let id: String
    let originalRelPath: String
    let capturedAtMillis: Int?
    let importedAtMillis: Int
    let orientationClass: String
    let remoteKey: String?
    let isPending: Bool
}

struct SourceOptionDTO: Sendable {
    let id: String
    let displayName: String
}

struct CollectionDTO: Sendable {
    let id: String
    let name: String
    let type: String
    let sortKey: String
    let memberCount: Int
}

enum TombstoneReason: String {
    case userDelete = "user_delete"
    case privacyDelete = "privacy_delete"
    case mirrorDelete = "mirror_delete"
}

actor AssetRepository {
    private let controller: PersistenceController

    init(controller: PersistenceController) {
        self.controller = controller
    }

    private func makeContext() -> NSManagedObjectContext {
        controller.container.newBackgroundContext()
    }

    private func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }

    func fetchAsset(id: String) async throws -> AssetDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return nil }
            return Self.assetDTO(row)
        }
    }

    func fetchSourceAsset(id: String) async throws -> SourceAssetDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return nil }
            return Self.sourceAssetDTO(row)
        }
    }

    func frameSelections(frameID: String) async throws -> [FrameSelectionDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "frame.id == %@", frameID)
            return try context.fetch(request).map(Self.selectionDTO)
        }
    }

    func libraryVersion() async throws -> Int {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", PersistenceController.libraryVersionKey)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return 0 }
            return (row.value(forKey: "value_json") as? String).flatMap(Int.init) ?? 0
        }
    }

    func candidates(frameID: String, privacyHidden: Bool) async throws -> [AssetDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "frame.id == %@", frameID)
            let selections = try context.fetch(request)
            var includedIds = Set<String>()
            var excludedKeys = Set<String>()
            for selection in selections {
                guard let mode = selection.value(forKey: "mode") as? String,
                      let refKey = selection.value(forKey: "ref_key") as? String else { continue }
                if mode == FrameSelectionMode.exclude.rawValue {
                    excludedKeys.insert(refKey)
                } else if let assetId = selection.value(forKeyPath: "asset.id") as? String {
                    includedIds.insert(assetId)
                }
            }
            let assetIds = includedIds.subtracting(excludedKeys)
            guard !assetIds.isEmpty else { return [] }
            var clauses: [NSPredicate] = [
                NSPredicate(format: "id IN %@", assetIds),
                NSPredicate(format: "readiness != %@", Readiness.deleted.rawValue),
                NSPredicate(format: "deleted_at == nil"),
            ]
            if privacyHidden {
                clauses.append(NSPredicate(format: "privacy_excluded == NO"))
            }
            let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            assetRequest.predicate = NSCompoundPredicate(andPredicateWithSubpredicates: clauses)
            return try context.fetch(assetRequest)
                .map(Self.assetDTO)
                .sorted { lhs, rhs in
                    let lKey = lhs.capturedAtMillis ?? lhs.importedAt
                    let rKey = rhs.capturedAtMillis ?? rhs.importedAt
                    if lKey != rKey { return lKey < rKey }
                    return lhs.id < rhs.id
                }
        }
    }

    @discardableResult
    func upsertFrameSelection(
        frameID: String,
        assetId: String?,
        sourceAssetId: String?,
        mode: FrameSelectionMode,
        manualOrder: Int? = nil
    ) async throws -> FrameSelectionDTO {
        let refKey = try FrameSelectionValidator.deriveRefKey(assetId: assetId, sourceAssetId: sourceAssetId)
        let now = nowMillis()
        let context = makeContext()
        return try await context.perform {
            let frameRequest = NSFetchRequest<NSManagedObject>(entityName: "Frame")
            frameRequest.predicate = NSPredicate(format: "id == %@", frameID)
            frameRequest.fetchLimit = 1
            guard let frame = try context.fetch(frameRequest).first else {
                throw PersistenceError.dbConstraintViolation
            }
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "frame.id == %@ AND ref_key == %@", frameID, refKey)
            request.fetchLimit = 1
            let row = try context.fetch(request).first ?? {
                let created = NSEntityDescription.insertNewObject(forEntityName: "FrameSelection", into: context)
                created.setValue(frame, forKey: "frame")
                created.setValue(refKey, forKey: "ref_key")
                created.setValue(now, forKey: "added_at")
                created.setValue(now, forKey: "created_at")
                return created
            }()
            try FrameSelectionValidator.validateStoredRefKey(
                assetId: assetId,
                sourceAssetId: sourceAssetId,
                refKey: row.value(forKey: "ref_key") as? String
            )
            row.setValue(mode.rawValue, forKey: "mode")
            row.setValue(manualOrder, forKey: "manual_order")
            if let assetId {
                let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
                assetRequest.predicate = NSPredicate(format: "id == %@", assetId)
                assetRequest.fetchLimit = 1
                guard let asset = try context.fetch(assetRequest).first else {
                    throw PersistenceError.dbConstraintViolation
                }
                row.setValue(asset, forKey: "asset")
                row.setValue(nil, forKey: "sourceAsset")
            } else if let sourceAssetId {
                let sourceAssetRequest = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
                sourceAssetRequest.predicate = NSPredicate(format: "id == %@", sourceAssetId)
                sourceAssetRequest.fetchLimit = 1
                guard let sourceAsset = try context.fetch(sourceAssetRequest).first else {
                    throw PersistenceError.dbConstraintViolation
                }
                row.setValue(nil, forKey: "asset")
                row.setValue(sourceAsset, forKey: "sourceAsset")
            }
            row.setValue(now, forKey: "updated_at")
            try context.save()
            return Self.selectionDTO(row)
        }
    }

    func removeFrameSelection(frameID: String, refKey: String) async throws {
        let context = makeContext()
        try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "frame.id == %@ AND ref_key == %@", frameID, refKey)
            for row in try context.fetch(request) {
                context.delete(row)
            }
            if context.hasChanges {
                try context.save()
            }
        }
    }

    func deleteAsset(id: String, reason: TombstoneReason) async throws {
        let identity = try await tombstoneIdentity(assetId: id)
        let head64kHash = try? AssetFileProber.head64kHash(of: originalsFileURL(relPath: identity.originalRelPath))
        let now = nowMillis()
        let context = makeContext()
        try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let asset = try context.fetch(request).first else {
                throw PersistenceError.dbConstraintViolation
            }
            try Self.insertTombstone(
                context: context,
                identity: identity,
                head64kHash: head64kHash,
                reason: reason,
                now: now
            )
            asset.setValue(Readiness.deleted.rawValue, forKey: "readiness")
            asset.setValue(now, forKey: "deleted_at")
            asset.setValue(now, forKey: "updated_at")
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
    }

    func restoreDeletedAsset(id: String) async throws {
        let now = nowMillis()
        let context = makeContext()
        try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let asset = try context.fetch(request).first else {
                throw PersistenceError.dbConstraintViolation
            }
            asset.setValue(Readiness.ready.rawValue, forKey: "readiness")
            asset.setValue(nil, forKey: "deleted_at")
            asset.setValue(now, forKey: "updated_at")

            let tombstones = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            tombstones.predicate = NSPredicate(
                format: "content_hash == %@ AND reason == %@",
                asset.value(forKey: "content_hash") as? String ?? "",
                TombstoneReason.userDelete.rawValue
            )
            for tombstone in try context.fetch(tombstones) {
                context.delete(tombstone)
            }
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
    }

    func hideAssetGlobally(id: String) async throws {
        let identity = try await tombstoneIdentity(assetId: id)
        let head64kHash = try? AssetFileProber.head64kHash(of: originalsFileURL(relPath: identity.originalRelPath))
        let now = nowMillis()
        let context = makeContext()
        try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let asset = try context.fetch(request).first else {
                throw PersistenceError.dbConstraintViolation
            }
            try Self.insertTombstone(
                context: context,
                identity: identity,
                head64kHash: head64kHash,
                reason: .privacyDelete,
                now: now
            )
            asset.setValue(NSNumber(value: true), forKey: "privacy_excluded")
            asset.setValue(now, forKey: "updated_at")
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
    }

    static let deletedRetentionMillis = 24 * 60 * 60 * 1000

    @discardableResult
    func purgeExpiredDeletedAssets(now: Int? = nil) async throws -> Int {
        let nowMs = now ?? nowMillis()
        let cutoff = nowMs - Self.deletedRetentionMillis
        let context = makeContext()
        let expired: [(id: String, relPath: String)] = try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(
                format: "readiness == %@ AND deleted_at != nil AND deleted_at < %@",
                Readiness.deleted.rawValue,
                NSNumber(value: cutoff)
            )
            return try context.fetch(request).map {
                (
                    $0.value(forKey: "id") as? String ?? "",
                    $0.value(forKey: "original_rel_path") as? String ?? ""
                )
            }
        }
        guard !expired.isEmpty else { return 0 }
        for asset in expired {
            try? FileManager.default.removeItem(at: originalsFileURL(relPath: asset.relPath))
        }
        try await context.perform {
            for asset in expired {
                let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
                request.predicate = NSPredicate(format: "id == %@", asset.id)
                request.fetchLimit = 1
                if let row = try context.fetch(request).first {
                    context.delete(row)
                }
            }
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
        }
        return expired.count
    }

    @discardableResult
    func clearUserDeletionTombstones() async throws -> Int {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            request.predicate = NSPredicate(
                format: "reason IN %@",
                [TombstoneReason.userDelete.rawValue]
            )
            let rows = try context.fetch(request)
            for row in rows {
                context.delete(row)
            }
            if !rows.isEmpty {
                try context.save()
            }
            return rows.count
        }
    }

    func disconnectSource(sourceID: String, keepCopies: Bool) async throws {
        let context = makeContext()
        let now = nowMillis()
        let filesToDelete: [String] = try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
            request.predicate = NSPredicate(format: "id == %@", sourceID)
            request.fetchLimit = 1
            guard let source = try context.fetch(request).first else {
                throw ImportFailureCode.srcUnavailable
            }
            let fingerprint = ImportCoordinator.sourceFingerprint(source)
            let sourceAssets = source.value(forKey: "sourceAssets") as? Set<NSManagedObject> ?? []
            var files: [String] = []
            if keepCopies {
                for sourceAsset in sourceAssets {
                    let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                    for origin in origins {
                        origin.setValue(OriginState.detached.rawValue, forKey: "origin_state")
                        origin.setValue(now, forKey: "updated_at")
                    }
                }
                let jobs = source.value(forKey: "importJobs") as? Set<NSManagedObject> ?? []
                for job in jobs {
                    context.delete(job)
                }
                source.setValue("disconnected", forKey: "status")
                source.setValue(now, forKey: "updated_at")
                _ = try PersistenceController.bumpLibraryVersionValue(in: context)
                try context.save()
                return files
            }
            for sourceAsset in sourceAssets {
                let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                for origin in origins {
                    guard let asset = origin.value(forKey: "asset") as? NSManagedObject else {
                        continue
                    }
                    let otherOrigins = (asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [])
                        .filter { $0 != origin && !$0.isDeleted }
                    if otherOrigins.isEmpty {
                        let identity = TombstoneIdentity(
                            contentHash: asset.value(forKey: "content_hash") as? String ?? "",
                            byteSize: asset.value(forKey: "byte_size") as? Int,
                            sourceFingerprint: fingerprint,
                            remoteId: sourceAsset.value(forKey: "remote_key") as? String,
                            originalRelPath: asset.value(forKey: "original_rel_path") as? String ?? ""
                        )
                        let head = try? AssetFileProber.head64kHash(
                            of: self.originalsFileURL(relPath: identity.originalRelPath)
                        )
                        try Self.insertTombstone(
                            context: context,
                            identity: identity,
                            head64kHash: head,
                            reason: .userDelete,
                            now: now
                        )
                        files.append(identity.originalRelPath)
                        context.delete(asset)
                    } else {
                        context.delete(origin)
                    }
                }
                let selections = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
                selections.predicate = NSPredicate(
                    format: "sourceAsset.id == %@",
                    sourceAsset.value(forKey: "id") as? String ?? ""
                )
                for selection in try context.fetch(selections) {
                    context.delete(selection)
                }
            }
            context.delete(source)
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
            return files
        }
        for relPath in filesToDelete {
            try? FileManager.default.removeItem(at: originalsFileURL(relPath: relPath))
        }
    }

    func affectedFrameCount(assetIDs: [String]) async throws -> Int {
        guard !assetIDs.isEmpty else { return 0 }
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "asset.id IN %@", assetIDs)
            var frameIDs = Set<String>()
            for row in try context.fetch(request) {
                if let frameID = row.value(forKeyPath: "frame.id") as? String {
                    frameIDs.insert(frameID)
                }
            }
            return frameIDs.count
        }
    }

    static func insertTombstone(
        context: NSManagedObjectContext,
        identity: TombstoneIdentity,
        head64kHash: String?,
        reason: TombstoneReason,
        now: Int
    ) throws {
        let tombstone = NSEntityDescription.insertNewObject(forEntityName: "LocalTombstone", into: context)
        tombstone.setValue(UUID().uuidString, forKey: "id")
        tombstone.setValue(identity.contentHash, forKey: "content_hash")
        tombstone.setValue(identity.sourceFingerprint, forKey: "source_fingerprint")
        tombstone.setValue(identity.remoteId, forKey: "remote_id")
        tombstone.setValue(identity.byteSize, forKey: "byte_size")
        tombstone.setValue(head64kHash, forKey: "head64k_hash")
        tombstone.setValue(reason.rawValue, forKey: "reason")
        tombstone.setValue(now, forKey: "created_at")
        tombstone.setValue(now, forKey: "updated_at")
    }

    func libraryItems(collectionID: String? = nil, readiness: String = Readiness.ready.rawValue) async throws -> [LibraryItemDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            var predicates = [
                NSPredicate(format: "readiness == %@", readiness),
                NSPredicate(format: "privacy_excluded == NO"),
            ]
            if let collectionID {
                predicates.append(NSPredicate(
                    format: "SUBQUERY(collectionAssets, $link, $link.collection.id == %@).@count > 0",
                    collectionID
                ))
            }
            request.predicate = NSCompoundPredicate(andPredicateWithSubpredicates: predicates)
            let items = try context.fetch(request).map { row -> LibraryItemDTO in
                let origins = row.value(forKey: "assetOrigins") as? Set<NSManagedObject>
                let remoteKey = origins?.first?.value(forKeyPath: "sourceAsset.remote_key") as? String
                return LibraryItemDTO(
                    id: row.value(forKey: "id") as? String ?? "",
                    originalRelPath: row.value(forKey: "original_rel_path") as? String ?? "",
                    capturedAtMillis: row.value(forKey: "captured_at") as? Int,
                    importedAtMillis: row.value(forKey: "imported_at") as? Int ?? 0,
                    orientationClass: row.value(forKey: "orientation_class") as? String ?? "",
                    remoteKey: remoteKey,
                    isPending: false
                )
            }
            return items.sorted {
                let left = $0.capturedAtMillis ?? $0.importedAtMillis
                let right = $1.capturedAtMillis ?? $1.importedAtMillis
                if left != right { return left > right }
                return $0.id < $1.id
            }
        }
    }

    func pendingSourceItems() async throws -> [LibraryItemDTO] {
        let context = makeContext()
        return try await context.perform {
            let terminalItemStates = [
                ImportItemState.imported.rawValue,
                ImportItemState.failed.rawValue,
                ImportItemState.canceled.rawValue,
                ImportItemState.skipped.rawValue,
            ]
            let terminalJobStates = [
                ImportJobState.completed.rawValue,
                ImportJobState.partialFailed.rawValue,
                ImportJobState.failed.rawValue,
                ImportJobState.canceled.rawValue,
            ]
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(
                format: "NOT (state IN %@) AND NOT (job.state IN %@)",
                terminalItemStates,
                terminalJobStates
            )
            var items: [LibraryItemDTO] = []
            var seenIDs = Set<String>()
            for item in try context.fetch(request) {
                guard let sourceAsset = item.value(forKey: "sourceAsset") as? NSManagedObject,
                      let sourceAssetID = sourceAsset.value(forKey: "id") as? String,
                      seenIDs.insert(sourceAssetID).inserted else { continue }
                let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                guard origins.isEmpty else { continue }
                items.append(LibraryItemDTO(
                    id: "pending:\(sourceAssetID)",
                    originalRelPath: "",
                    capturedAtMillis: sourceAsset.value(forKey: "remote_taken_at") as? Int,
                    importedAtMillis: sourceAsset.value(forKey: "last_seen_at") as? Int ?? 0,
                    orientationClass: "",
                    remoteKey: sourceAsset.value(forKey: "remote_key") as? String,
                    isPending: true
                ))
            }
            return items
        }
    }

    func sourceOptions() async throws -> [SourceOptionDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
            return try context.fetch(request)
                .map {
                    SourceOptionDTO(
                        id: $0.value(forKey: "id") as? String ?? "",
                        displayName: $0.value(forKey: "display_name") as? String ?? ""
                    )
                }
                .sorted {
                    if $0.displayName != $1.displayName { return $0.displayName < $1.displayName }
                    return $0.id < $1.id
                }
        }
    }

    func searchAssetIDs(matching text: String) async throws -> Set<String> {
        let context = makeContext()
        return try await context.perform {
            var result = Set<String>()
            let sourceAssets = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            sourceAssets.predicate = NSPredicate(format: "remote_key CONTAINS[cd] %@", text)
            for row in try context.fetch(sourceAssets) {
                let origins = row.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                for origin in origins {
                    if let assetID = origin.value(forKeyPath: "asset.id") as? String {
                        result.insert(assetID)
                    }
                }
            }
            let collections = NSFetchRequest<NSManagedObject>(entityName: "Collection")
            collections.predicate = NSPredicate(format: "name CONTAINS[cd] %@", text)
            var collectionIDs: [String] = []
            for row in try context.fetch(collections) {
                if let id = row.value(forKey: "id") as? String {
                    collectionIDs.append(id)
                }
            }
            if !collectionIDs.isEmpty {
                let links = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
                links.predicate = NSPredicate(format: "collection.id IN %@", collectionIDs)
                for row in try context.fetch(links) {
                    if let assetID = row.value(forKeyPath: "asset.id") as? String {
                        result.insert(assetID)
                    }
                }
            }
            return result
        }
    }

    func assetIDs(inSourceIDs sourceIDs: Set<String>) async throws -> Set<String> {
        guard !sourceIDs.isEmpty else { return [] }
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
            request.predicate = NSPredicate(format: "sourceAsset.source.id IN %@", sourceIDs)
            var result = Set<String>()
            for row in try context.fetch(request) {
                if let assetID = row.value(forKeyPath: "asset.id") as? String {
                    result.insert(assetID)
                }
            }
            return result
        }
    }

    func collections() async throws -> [CollectionDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
            let rows = try context.fetch(request).map {
                CollectionDTO(
                    id: $0.value(forKey: "id") as? String ?? "",
                    name: $0.value(forKey: "name") as? String ?? "",
                    type: $0.value(forKey: "type") as? String ?? "",
                    sortKey: $0.value(forKey: "sort_key") as? String ?? "",
                    memberCount: ($0.value(forKey: "collectionAssets") as? Set<NSManagedObject>)?.count ?? 0
                )
            }
            return rows.sorted {
                if $0.sortKey != $1.sortKey { return $0.sortKey > $1.sortKey }
                return $0.id < $1.id
            }
        }
    }

    func collectionAssetIDs(collectionID: String) async throws -> [String] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
            request.predicate = NSPredicate(format: "collection.id == %@", collectionID)
            return try context.fetch(request).compactMap {
                $0.value(forKeyPath: "asset.id") as? String
            }
        }
    }

    func sourceDisplayName(assetID: String) async throws -> String? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
            request.predicate = NSPredicate(format: "asset.id == %@", assetID)
            request.sortDescriptors = [NSSortDescriptor(key: "imported_at", ascending: true)]
            request.fetchLimit = 1
            guard let origin = try context.fetch(request).first else { return nil }
            return origin.value(forKeyPath: "sourceAsset.source.display_name") as? String
        }
    }

    func frameCount(assetID: String) async throws -> Int {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            request.predicate = NSPredicate(format: "asset.id == %@", assetID)
            return try context.count(for: request)
        }
    }

    nonisolated func assetFileURL(relPath: String) -> URL {
        originalsFileURL(relPath: relPath)
    }

    func tombstoneSuppressionLevel(probe: TombstoneMatcher.Probe) async throws -> Int? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            request.predicate = TombstoneMatcher.matchPredicate(probe: probe)
            var best: Int?
            for tombstone in try context.fetch(request) {
                guard let rank = TombstoneMatcher.rankMatch(tombstone: tombstone, probe: probe) else { continue }
                if best == nil || rank < best! { best = rank }
            }
            return best
        }
    }

    struct TombstoneIdentity {
        let contentHash: String
        let byteSize: Int?
        let sourceFingerprint: String?
        let remoteId: String?
        let originalRelPath: String
    }

    private nonisolated func originalsFileURL(relPath: String) -> URL {
        controller.storeURL.deletingLastPathComponent()
            .appendingPathComponent("originals", isDirectory: true)
            .appendingPathComponent(relPath)
    }

    private func tombstoneIdentity(assetId: String) async throws -> TombstoneIdentity {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "id == %@", assetId)
            request.fetchLimit = 1
            guard let asset = try context.fetch(request).first else {
                throw PersistenceError.dbConstraintViolation
            }
            var fingerprint: String?
            var remoteId: String?
            let originsRequest = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
            originsRequest.predicate = NSPredicate(format: "asset.id == %@", assetId)
            if let origin = try context.fetch(originsRequest).first,
               let sourceAsset = origin.value(forKey: "sourceAsset") as? NSManagedObject,
               let source = sourceAsset.value(forKey: "source") as? NSManagedObject {
                fingerprint = ImportCoordinator.sourceFingerprint(source)
                remoteId = sourceAsset.value(forKey: "remote_key") as? String
            }
            return TombstoneIdentity(
                contentHash: asset.value(forKey: "content_hash") as? String ?? "",
                byteSize: asset.value(forKey: "byte_size") as? Int,
                sourceFingerprint: fingerprint,
                remoteId: remoteId,
                originalRelPath: asset.value(forKey: "original_rel_path") as? String ?? ""
            )
        }
    }

    private static func assetDTO(_ row: NSManagedObject) -> AssetDTO {
        AssetDTO(
            id: row.value(forKey: "id") as? String ?? "",
            contentHash: row.value(forKey: "content_hash") as? String ?? "",
            byteSize: row.value(forKey: "byte_size") as? Int ?? 0,
            format: row.value(forKey: "format") as? String ?? "",
            pixelWidth: row.value(forKey: "pixel_width") as? Int ?? 0,
            pixelHeight: row.value(forKey: "pixel_height") as? Int ?? 0,
            exifOrientation: row.value(forKey: "exif_orientation") as? Int ?? 1,
            orientationClass: row.value(forKey: "orientation_class") as? String ?? "",
            capturedAtMillis: row.value(forKey: "captured_at") as? Int,
            capturedAtSource: row.value(forKey: "captured_at_source") as? String ?? "",
            importedAt: row.value(forKey: "imported_at") as? Int ?? 0,
            originalRelPath: row.value(forKey: "original_rel_path") as? String ?? "",
            originalFileName: row.value(forKey: "original_file_name") as? String,
            readiness: row.value(forKey: "readiness") as? String ?? "",
            privacyExcluded: row.value(forKey: "privacy_excluded") as? Bool ?? false,
            deletedAt: row.value(forKey: "deleted_at") as? Int
        )
    }

    private static func sourceAssetDTO(_ row: NSManagedObject) -> SourceAssetDTO {
        SourceAssetDTO(
            id: row.value(forKey: "id") as? String ?? "",
            remoteKey: row.value(forKey: "remote_key") as? String ?? "",
            revision: row.value(forKey: "revision") as? String ?? "",
            byteSize: row.value(forKey: "byte_size") as? Int,
            lastSeenAt: row.value(forKey: "last_seen_at") as? Int ?? 0,
            accessState: row.value(forKey: "access_state") as? String ?? ""
        )
    }

    private static func selectionDTO(_ row: NSManagedObject) -> FrameSelectionDTO {
        FrameSelectionDTO(
            refKey: row.value(forKey: "ref_key") as? String ?? "",
            mode: row.value(forKey: "mode") as? String ?? "",
            manualOrder: row.value(forKey: "manual_order") as? Int,
            assetId: row.value(forKeyPath: "asset.id") as? String,
            sourceAssetId: row.value(forKeyPath: "sourceAsset.id") as? String
        )
    }
}
