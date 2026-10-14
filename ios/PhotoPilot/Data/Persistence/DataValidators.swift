import CoreData
import Foundation

enum FrameSelectionValidator {
    static func validateExactlyOneReference(assetId: String?, sourceAssetId: String?) throws {
        switch (assetId, sourceAssetId) {
        case (.none, .none):
            throw DataValidationFailure.selectionBothNil
        case (.some, .some):
            throw DataValidationFailure.selectionBothSet
        case (.some, .none), (.none, .some):
            return
        }
    }

    static func deriveRefKey(assetId: String?, sourceAssetId: String?) throws -> String {
        try validateExactlyOneReference(assetId: assetId, sourceAssetId: sourceAssetId)
        return assetId ?? sourceAssetId!
    }

    static func validateStoredRefKey(assetId: String?, sourceAssetId: String?, refKey: String?) throws {
        let derived = try deriveRefKey(assetId: assetId, sourceAssetId: sourceAssetId)
        guard refKey == derived else {
            throw DataValidationFailure.selectionRefKeyMismatch
        }
    }
}

enum TombstoneMatcher {
    struct Probe {
        let contentHash: String?
        let sourceFingerprint: String?
        let remoteId: String?
        let byteSize: Int?
        let head64kHash: String?
    }

    static func matchPredicate(probe: Probe) -> NSPredicate {
        var clauses: [NSPredicate] = []
        if let contentHash = probe.contentHash {
            clauses.append(NSPredicate(format: "content_hash == %@", contentHash))
        }
        if let sourceFingerprint = probe.sourceFingerprint, let remoteId = probe.remoteId {
            clauses.append(NSPredicate(
                format: "source_fingerprint == %@ AND remote_id == %@",
                sourceFingerprint,
                remoteId
            ))
        }
        if let sourceFingerprint = probe.sourceFingerprint,
           let byteSize = probe.byteSize,
           let head64kHash = probe.head64kHash {
            clauses.append(NSPredicate(
                format: "source_fingerprint == %@ AND byte_size == %@ AND head64k_hash == %@",
                sourceFingerprint,
                NSNumber(value: byteSize),
                head64kHash
            ))
        }
        if clauses.isEmpty {
            return NSPredicate(value: false)
        }
        return NSCompoundPredicate(orPredicateWithSubpredicates: clauses)
    }

    static func rankMatch(tombstone: NSManagedObject, probe: Probe) -> Int? {
        if let contentHash = probe.contentHash,
           tombstone.value(forKey: "content_hash") as? String == contentHash {
            return 1
        }
        if let fingerprint = probe.sourceFingerprint,
           tombstone.value(forKey: "source_fingerprint") as? String == fingerprint,
           let remoteId = probe.remoteId,
           tombstone.value(forKey: "remote_id") as? String == remoteId {
            return 2
        }
        if let fingerprint = probe.sourceFingerprint,
           tombstone.value(forKey: "source_fingerprint") as? String == fingerprint,
           let byteSize = probe.byteSize,
           tombstone.value(forKey: "byte_size") as? Int == byteSize,
           let head64kHash = probe.head64kHash,
           tombstone.value(forKey: "head64k_hash") as? String == head64kHash {
            return 3
        }
        return nil
    }
}

enum CollectionValidator {
    static func validateParentIsNotInSubtree(
        context: NSManagedObjectContext,
        collectionID: String,
        proposedParentID: String
    ) throws {
        var cursor: String? = proposedParentID
        while let current = cursor {
            if current == collectionID {
                throw DataValidationFailure.collectionCycle
            }
            let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
            request.predicate = NSPredicate(format: "id == %@", current)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return }
            cursor = row.value(forKeyPath: "parent.id") as? String
        }
    }

    static func validateSourceRelPathUnique(
        context: NSManagedObjectContext,
        sourceId: String?,
        relPath: String?,
        excludingCollectionId: String?
    ) throws {
        guard let sourceId, let relPath else { return }
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(format: "source.id == %@ AND rel_path == %@", sourceId, relPath)
        for row in try context.fetch(request) {
            if let id = row.value(forKey: "id") as? String, id != excludingCollectionId {
                throw DataValidationFailure.duplicateSourceRelPath
            }
        }
    }
}

enum SourceValidator {
    static func validateEndpointUnique(
        context: NSManagedObjectContext,
        type: String,
        endpointKey: String?,
        excludingSourceId: String?
    ) throws {
        guard let endpointKey else { return }
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "type == %@ AND endpoint_key == %@", type, endpointKey)
        for row in try context.fetch(request) {
            if let id = row.value(forKey: "id") as? String, id != excludingSourceId {
                throw DataValidationFailure.duplicateSourceEndpoint
            }
        }
    }
}

enum AssetValidator {
    static func validateContentHashUniqueAmongActive(
        context: NSManagedObjectContext,
        contentHash: String,
        excludingAssetId: String?
    ) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        request.predicate = NSPredicate(
            format: "content_hash == %@ AND readiness != %@",
            contentHash,
            Readiness.deleted.rawValue
        )
        for row in try context.fetch(request) {
            if let id = row.value(forKey: "id") as? String, id != excludingAssetId {
                throw DataValidationFailure.duplicateActiveContentHash
            }
        }
    }
}

enum FrameDirectoryRuleValidator {
    static func validateIncludeFuture(_ includeFuture: Bool, confirmedByUser: Bool) throws {
        if includeFuture && !confirmedByUser {
            throw DataValidationFailure.includeFutureWithoutConfirmation
        }
    }
}

enum ImportJobStateValidator {
    static func derive(
        itemStates: [String],
        signal: ImportJobSignal,
        enumerationComplete: Bool
    ) -> String {
        if itemStates.isEmpty, signal == .none {
            return enumerationComplete
                ? ImportJobState.completed.rawValue
                : ImportJobState.running.rawValue
        }
        switch signal {
        case .userCancel:
            return ImportJobState.canceled.rawValue
        case .fatalError:
            return ImportJobState.failed.rawValue
        case .userPause:
            return ImportJobState.paused.rawValue
        case .autoPause:
            return ImportJobState.pausedAuto.rawValue
        case .none:
            break
        }
        let terminal: Set<String> = [
            ImportItemState.imported.rawValue,
            ImportItemState.failed.rawValue,
            ImportItemState.canceled.rawValue,
            ImportItemState.skipped.rawValue,
        ]
        guard itemStates.allSatisfy({ terminal.contains($0) }) else {
            return ImportJobState.running.rawValue
        }
        let importedCount = itemStates.filter { $0 == ImportItemState.imported.rawValue }.count
        let failedCount = itemStates.filter { $0 == ImportItemState.failed.rawValue }.count
        if failedCount > 0 {
            return importedCount > 0
                ? ImportJobState.partialFailed.rawValue
                : ImportJobState.failed.rawValue
        }
        return ImportJobState.completed.rawValue
    }

    static func validateStateWrite(state: String, derived: String?) throws {
        guard state == derived else {
            throw DataValidationFailure.stateWriteNotDerived
        }
    }
}
