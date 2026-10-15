import CoreData
import Foundation

enum ImportFailureCode: String, Error {
    case storeLowSpace = "STORE_LOW_SPACE"
    case storeWriteFailed = "STORE_WRITE_FAILED"
    case storeFileLocked = "STORE_FILE_LOCKED"
    case dbConstraintViolation = "DB_CONSTRAINT_VIOLATION"
    case mediaUnsupportedFormat = "MEDIA_UNSUPPORTED_FORMAT"
    case mediaDecodeFailed = "MEDIA_DECODE_FAILED"
    case mediaTruncated = "MEDIA_TRUNCATED"
    case srcUnavailable = "SRC_UNAVAILABLE"
    case canceledByUser = "CANCELLED_BY_USER"
    case canceledByEpoch = "CANCELLED_BY_EPOCH"
    case skippedByTombstone = "SKIPPED_BY_TOMBSTONE"
    case tlsUntrustedCert = "TLS_UNTRUSTED_CERT"
    case tlsHostnameMismatch = "TLS_HOSTNAME_MISMATCH"
    case tlsFingerprintChanged = "TLS_FINGERPRINT_CHANGED"
    case tlsCleartextBlocked = "TLS_CLEARTEXT_BLOCKED"
    case srcAuthFailed = "SRC_AUTH_FAILED"
    case srcPathNotFound = "SRC_PATH_NOT_FOUND"
    case srcPermissionDenied = "SRC_PERMISSION_DENIED"
    case srcAssetGone = "SRC_ASSET_GONE"
    case permLocalNetworkDenied = "PERM_LOCAL_NETWORK_DENIED"
}

struct ImportJobDTO: Sendable {
    let id: String
    let sourceId: String
    let state: String
    let counts: [String: Int]
    let cursor: String?
    let totalKnown: Int?
    let createdAt: Int
}

struct ImportItemDTO: Sendable {
    let id: String
    let jobId: String
    let state: String
    let retryCount: Int
    let retryAt: Int?
    let failureCode: String?
}

enum CommitOutcome: Sendable, Equatable {
    case merged(assetId: String)
    case imported(assetId: String)
    case skippedByTombstone
    case canceledByEpoch
}

actor ImportCoordinator {
    private let controller: PersistenceController
    private let adapter: any SourceAdapter
    private var signals: [String: ImportJobSignal] = [:]

    static let retryBackoffMillis = [1_000, 4_000, 15_000]
    static let maxRetryCount = 3
    static let enumerationDoneCursor = OffsetCursor.doneSentinel

    init(controller: PersistenceController, adapter: any SourceAdapter) {
        self.controller = controller
        self.adapter = adapter
    }

    private func makeContext() -> NSManagedObjectContext {
        controller.container.newBackgroundContext()
    }

    private func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }

    private var originalsRootURL: URL {
        controller.storeURL.deletingLastPathComponent()
            .appendingPathComponent("originals", isDirectory: true)
    }

    private func stagedFileURL(jobID: String, itemID: String) -> URL {
        PersistenceController.temporaryDirectoryURL()
            .appendingPathComponent(jobID, isDirectory: true)
            .appendingPathComponent("\(itemID).part")
    }

    func createJob(sourceID: String, scopeJSON: String) async throws -> ImportJobDTO {
        let now = nowMillis()
        let context = makeContext()
        let job = try await context.perform {
            let source = try Self.fetchSource(context: context, sourceID: sourceID)
            let row = NSEntityDescription.insertNewObject(forEntityName: "ImportJob", into: context)
            let jobId = UUID().uuidString
            row.setValue(jobId, forKey: "id")
            row.setValue(source, forKey: "source")
            row.setValue(scopeJSON, forKey: "scope_json")
            row.setValue(source.value(forKey: "epoch") as? Int ?? 1, forKey: "epoch_at_create")
            row.setValue("{}", forKey: "counts_json")
            row.setValue(ImportJobState.running.rawValue, forKey: "state")
            row.setValue(now, forKey: "created_at")
            row.setValue(now, forKey: "updated_at")
            let batch = NSEntityDescription.insertNewObject(forEntityName: "Collection", into: context)
            batch.setValue(UUID().uuidString, forKey: "id")
            batch.setValue(source, forKey: "source")
            batch.setValue(Self.importBatchName(createdAtMillis: now), forKey: "name")
            batch.setValue("import_batch", forKey: "type")
            batch.setValue(Self.importBatchSortKey(createdAtMillis: now), forKey: "sort_key")
            batch.setValue(now, forKey: "created_at")
            batch.setValue(now, forKey: "updated_at")
            try context.save()
            return Self.jobDTO(row)
        }
        try await recomputeAggregate(jobID: job.id)
        guard let dto = try await fetchJob(id: job.id) else {
            throw PersistenceError.dbConstraintViolation
        }
        return dto
    }

    static func importBatchName(createdAtMillis: Int) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(createdAtMillis) / 1000))
    }

    static func importBatchSortKey(createdAtMillis: Int) -> String {
        String(format: "%013d", createdAtMillis)
    }

    @discardableResult
    func enqueueItems(jobID: String) async throws -> Int {
        var cursor: String? = try await persistedCursor(jobID: jobID)
        if cursor == Self.enumerationDoneCursor {
            try await recomputeAggregate(jobID: jobID)
            return 0
        }
        let deltaSync = try await isDeltaSyncSource(jobID: jobID)
        var enqueued = 0
        var enumerationDone = false
        while true {
            let page: SourceAssetPage
            do {
                page = try await adapter.enumerateAssets(cursor: cursor)
            } catch {
                throw ImportFailureCode.srcUnavailable
            }
            let now = nowMillis()
            let context = makeContext()
            try await context.perform {
                let job = try Self.fetchJob(context: context, jobID: jobID)
                guard let source = job.value(forKey: "source") as? NSManagedObject else {
                    throw PersistenceError.dbConstraintViolation
                }
                for descriptor in page.assets {
                    let upsert = try Self.upsertSourceAsset(
                        context: context,
                        source: source,
                        descriptor: descriptor,
                        now: now
                    )
                    let sourceAsset = upsert.row
                    if !upsert.isNew, !upsert.revisionChanged {
                        let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                        if !origins.isEmpty {
                            continue
                        }
                    }
                    let itemRequest = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
                    itemRequest.predicate = NSPredicate(
                        format: "job.id == %@ AND sourceAsset.id == %@",
                        jobID,
                        sourceAsset.value(forKey: "id") as? String ?? ""
                    )
                    itemRequest.fetchLimit = 1
                    if try context.fetch(itemRequest).first == nil {
                        let item = NSEntityDescription.insertNewObject(
                            forEntityName: "ImportItem",
                            into: context
                        )
                        item.setValue(UUID().uuidString, forKey: "id")
                        item.setValue(job, forKey: "job")
                        item.setValue(sourceAsset, forKey: "sourceAsset")
                        item.setValue(ImportItemState.queued.rawValue, forKey: "state")
                        item.setValue(now, forKey: "created_at")
                        item.setValue(now, forKey: "updated_at")
                    }
                }
                job.setValue(page.nextCursor ?? Self.enumerationDoneCursor, forKey: "cursor")
                job.setValue(page.totalKnown.map { NSNumber(value: $0) }, forKey: "total_known")
                job.setValue(now, forKey: "updated_at")
                try context.save()
            }
            enqueued += page.assets.count
            guard let next = page.nextCursor else { enumerationDone = true; break }
            cursor = next
        }
        if deltaSync, enumerationDone {
            try await finalizeSync(jobID: jobID)
        }
        try await recomputeAggregate(jobID: jobID)
        return enqueued
    }

    private func isDeltaSyncSource(jobID: String) async throws -> Bool {
        let context = makeContext()
        return try await context.perform {
            let job = try Self.fetchJob(context: context, jobID: jobID)
            guard let source = job.value(forKey: "source") as? NSManagedObject else {
                throw PersistenceError.dbConstraintViolation
            }
            let flags = source.value(forKey: "capability_flags") as? Int ?? 0
            return flags & 2 != 0
        }
    }

    private func finalizeSync(jobID: String) async throws {
        let context = makeContext()
        let now = nowMillis()
        let outcome = try await context.perform { () -> (pending: Bool, candidates: [String], batchSize: Int) in
            let job = try Self.fetchJob(context: context, jobID: jobID)
            guard let source = job.value(forKey: "source") as? NSManagedObject else {
                throw PersistenceError.dbConstraintViolation
            }
            let jobCreatedAt = job.value(forKey: "created_at") as? Int ?? 0
            let sourceID = source.value(forKey: "id") as? String ?? ""
            let mirrorOn = Self.sourceSettingsFlag(source, key: "mirrorDelete") ?? false
            let sourceAssets = source.value(forKey: "sourceAssets") as? Set<NSManagedObject> ?? []
            var candidates: [NSManagedObject] = []
            var removedFolderMembers: [NSManagedObject] = []
            for sourceAsset in sourceAssets {
                let lastSeenAt = sourceAsset.value(forKey: "last_seen_at") as? Int ?? 0
                guard lastSeenAt < jobCreatedAt else { continue }
                let assets = (sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [])
                    .compactMap { $0.value(forKey: "asset") as? NSManagedObject }
                removedFolderMembers.append(contentsOf: assets)
                if mirrorOn {
                    let count = (sourceAsset.value(forKey: "missing_scan_count") as? Int ?? 0) + 1
                    sourceAsset.setValue(NSNumber(value: count), forKey: "missing_scan_count")
                    if count == 1 {
                        sourceAsset.setValue(now, forKey: "updated_at")
                    }
                    let firstMissing = sourceAsset.value(forKey: "updated_at") as? Int ?? now
                    if count >= 2, now - firstMissing >= Int(NasDeletionJudge.minIntervalMs) {
                        candidates.append(sourceAsset)
                    }
                } else {
                    sourceAsset.setValue(AccessState.missing.rawValue, forKey: "access_state")
                    sourceAsset.setValue(now, forKey: "updated_at")
                }
            }
            Self.removeFolderMembership(
                context: context,
                sourceID: sourceID,
                assets: removedFolderMembers
            )
            guard mirrorOn, !candidates.isEmpty else {
                try context.save()
                return (false, [], sourceAssets.count)
            }
            let batchSize = max(sourceAssets.count, 1)
            let verdict = NasDeletionJudge.judgeBatch(
                candidates: candidates.count,
                batchSize: batchSize
            )
            if verdict == .needsConfirmation {
                Self.setPendingDeleteFlag(context: context, sourceID: sourceID, now: now)
                try context.save()
                return (true, [], batchSize)
            }
            let deletedAssetIDs = try Self.executeMirrorDeletion(
                context: context,
                source: source,
                candidates: candidates,
                originalsRoot: self.originalsRootURL,
                now: now
            )
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
            return (false, deletedAssetIDs, batchSize)
        }
        _ = outcome
    }

    static func executeMirrorDeletion(
        context: NSManagedObjectContext,
        source: NSManagedObject,
        candidates: [NSManagedObject],
        originalsRoot: URL,
        now: Int
    ) throws -> [String] {
        let fingerprints = Self.sourceFingerprint(source)
        var deletedAssetIDs: [String] = []
        for candidate in candidates {
            let origins = candidate.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
            candidate.setValue(AccessState.removed.rawValue, forKey: "access_state")
            candidate.setValue(now, forKey: "updated_at")
            for origin in origins {
                guard let asset = origin.value(forKey: "asset") as? NSManagedObject else {
                    continue
                }
                let otherOrigins = (asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [])
                    .filter { $0 != origin && !$0.isDeleted }
                if otherOrigins.isEmpty {
                    let assetID = asset.value(forKey: "id") as? String ?? ""
                    deletedAssetIDs.append(assetID)
                    let identity = AssetRepository.TombstoneIdentity(
                        contentHash: asset.value(forKey: "content_hash") as? String ?? "",
                        byteSize: asset.value(forKey: "byte_size") as? Int,
                        sourceFingerprint: fingerprints,
                        remoteId: candidate.value(forKey: "remote_key") as? String,
                        originalRelPath: asset.value(forKey: "original_rel_path") as? String ?? ""
                    )
                    let head64k = (try? AssetFileProber.head64kHash(
                        of: originalsRoot.appendingPathComponent(identity.originalRelPath)
                    ))
                    try AssetRepository.insertTombstone(
                        context: context,
                        identity: identity,
                        head64kHash: head64k,
                        reason: .mirrorDelete,
                        now: now
                    )
                    asset.setValue(Readiness.deleted.rawValue, forKey: "readiness")
                    asset.setValue(now, forKey: "deleted_at")
                    asset.setValue(now, forKey: "updated_at")
                    let selections = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
                    selections.predicate = NSPredicate(format: "asset.id == %@", assetID)
                    for selection in try context.fetch(selections) {
                        context.delete(selection)
                    }
                } else {
                    context.delete(origin)
                }
            }
        }
        return deletedAssetIDs
    }

    private static func pendingDeleteKey(sourceID: String) -> String {
        "nas.pending_delete:\(sourceID)"
    }

    func pendingDeleteCandidateCount(sourceID: String) async throws -> Int {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            request.predicate = NSPredicate(
                format: "source.id == %@ AND missing_scan_count >= 2",
                sourceID
            )
            let rows = try context.fetch(request)
            return rows.filter {
                let firstMissing = $0.value(forKey: "updated_at") as? Int ?? now
                return now - firstMissing >= Int(NasDeletionJudge.minIntervalMs)
            }.count
        }
    }

    func executePendingMirrorDelete(sourceID: String) async throws -> Int {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
            request.predicate = NSPredicate(format: "id == %@", sourceID)
            request.fetchLimit = 1
            guard let source = try context.fetch(request).first else {
                throw ImportFailureCode.srcUnavailable
            }
            let sourceAssets = source.value(forKey: "sourceAssets") as? Set<NSManagedObject> ?? []
            let candidates = sourceAssets.filter {
                let count = $0.value(forKey: "missing_scan_count") as? Int ?? 0
                let firstMissing = $0.value(forKey: "updated_at") as? Int ?? now
                return count >= 2 && now - firstMissing >= Int(NasDeletionJudge.minIntervalMs)
            }
            let deleted = try Self.executeMirrorDeletion(
                context: context,
                source: source,
                candidates: Array(candidates),
                originalsRoot: self.originalsRootURL,
                now: now
            )
            Self.clearPendingDeleteFlag(context: context, sourceID: sourceID, now: now)
            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try context.save()
            return deleted.count
        }
    }

    func refusePendingMirrorDelete(sourceID: String) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            request.predicate = NSPredicate(
                format: "source.id == %@ AND missing_scan_count > 0",
                sourceID
            )
            for row in try context.fetch(request) {
                row.setValue(NSNumber(value: 0), forKey: "missing_scan_count")
                row.setValue(AccessState.missing.rawValue, forKey: "access_state")
                row.setValue(now, forKey: "updated_at")
            }
            Self.clearPendingDeleteFlag(context: context, sourceID: sourceID, now: now)
            try context.save()
        }
    }

    private static func clearPendingDeleteFlag(
        context: NSManagedObjectContext,
        sourceID: String,
        now: Int
    ) {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", pendingDeleteKey(sourceID: sourceID))
        request.fetchLimit = 1
        if let row = try? context.fetch(request).first {
            context.delete(row)
        }
    }

    static func setPendingDeleteFlag(
        context: NSManagedObjectContext,
        sourceID: String,
        now: Int
    ) {
        let key = pendingDeleteKey(sourceID: sourceID)
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", key)
        request.fetchLimit = 1
        let row = (try? context.fetch(request).first) ?? NSEntityDescription.insertNewObject(
            forEntityName: "SettingsKV",
            into: context
        )
        row.setValue(key, forKey: "key")
        row.setValue("true", forKey: "value_json")
        if row.value(forKey: "created_at") == nil {
            row.setValue(now, forKey: "created_at")
        }
        row.setValue(now, forKey: "updated_at")
    }

    static func sourceSettingsFlag(_ source: NSManagedObject, key: String) -> Bool? {
        guard let raw = source.value(forKey: "settings_json") as? String,
              let data = raw.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return parsed[key] as? Bool
    }

    static func removeFolderMembership(
        context: NSManagedObjectContext,
        sourceID: String,
        assets: [NSManagedObject]
    ) {
        guard !assets.isEmpty else { return }
        let assetIDs = assets.compactMap { $0.value(forKey: "id") as? String }
        guard !assetIDs.isEmpty else { return }
        let request = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
        request.predicate = NSPredicate(
            format: "asset.id IN %@ AND collection.source.id == %@ AND collection.type == %@",
            assetIDs,
            sourceID,
            "source_folder"
        )
        guard let rows = try? context.fetch(request) else { return }
        for row in rows {
            context.delete(row)
        }
    }

    func stageItem(itemID: String) async throws {
        let ref = try await itemRef(itemID: itemID)
        guard ref.state == ImportItemState.queued.rawValue else {
            throw PersistenceError.dbConstraintViolation
        }
        let payload: LocalAssetPayload
        do {
            payload = try await adapter.assetPayload(remoteID: ref.remoteKey)
        } catch {
            if Self.isCancellation(error) { throw error }
            let code = Self.failureCode(for: error).code
            try await markItemFailed(itemID: itemID, code: code, retryable: code == .mediaTruncated)
            throw code
        }
        let stagedURL = stagedFileURL(jobID: ref.jobId, itemID: itemID)
        do {
            try FileManager.default.createDirectory(
                at: stagedURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            if FileManager.default.fileExists(atPath: stagedURL.path) {
                try FileManager.default.removeItem(at: stagedURL)
            }
            try FileManager.default.copyItem(at: payload.localFileURL, to: stagedURL)
        } catch {
            try await markItemFailed(itemID: itemID, code: .storeWriteFailed, retryable: true)
            throw ImportFailureCode.storeWriteFailed
        }
        let now = nowMillis()
        let context = makeContext()
        try await context.perform {
            let item = try Self.fetchItem(context: context, itemID: itemID)
            item.setValue(ImportItemState.downloading.rawValue, forKey: "state")
            let size = (try? stagedURL.resourceValues(forKeys: [.fileSizeKey]).fileSize)
                ?? payload.byteSize
            item.setValue(NSNumber(value: size), forKey: "bytes_total")
            item.setValue(NSNumber(value: 0), forKey: "bytes_done")
            item.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func verifyItem(itemID: String) async throws {
        do {
            try await verifyItemInternal(itemID: itemID)
        } catch let error as DataValidationFailure {
            try? await markItemFailed(itemID: itemID, code: .dbConstraintViolation, retryable: false)
            throw error
        }
    }

    private func verifyItemInternal(itemID: String) async throws {
        let ref = try await itemRef(itemID: itemID)
        guard ref.state == ImportItemState.downloading.rawValue else {
            throw PersistenceError.dbConstraintViolation
        }
        let stagedURL = stagedFileURL(jobID: ref.jobId, itemID: itemID)
        let payload: LocalAssetPayload
        do {
            payload = try await adapter.assetPayload(remoteID: ref.remoteKey)
        } catch {
            if Self.isCancellation(error) { throw error }
            let mapped = Self.failureCode(for: error)
            try await markItemFailed(itemID: itemID, code: mapped.code, retryable: mapped.retryable)
            return
        }
        guard let stagedHash = try? AssetFileProber.contentHash(of: stagedURL),
              stagedHash == payload.contentHash else {
            try await markItemFailed(itemID: itemID, code: .mediaTruncated, retryable: true)
            return
        }
        let stagedHead64k = (try? AssetFileProber.head64kHash(of: stagedURL)) ?? payload.head64kHash
        let probe = TombstoneMatcher.Probe(
            contentHash: stagedHash,
            sourceFingerprint: ref.sourceFingerprint,
            remoteId: ref.remoteKey,
            byteSize: payload.byteSize,
            head64kHash: stagedHead64k
        )
        let now = nowMillis()
        let signal = signals[ref.jobId] ?? .none
        if try await tombstoneHit(probe: probe) {
            try? FileManager.default.removeItem(at: stagedURL)
            let context = makeContext()
            try await context.perform {
                let item = try Self.fetchItem(context: context, itemID: itemID)
                item.setValue(ImportItemState.skipped.rawValue, forKey: "state")
                item.setValue(ImportFailureCode.skippedByTombstone.rawValue, forKey: "failure_code")
                item.setValue(now, forKey: "updated_at")
                try Self.recomputeAggregateInContext(
                    context: context,
                    item: item,
                    signal: signal,
                    now: now
                )
                try context.save()
            }
            return
        }
        let context = makeContext()
        try await context.perform {
            let item = try Self.fetchItem(context: context, itemID: itemID)
            item.setValue(ImportItemState.committing.rawValue, forKey: "state")
            item.setValue(now, forKey: "updated_at")
            try Self.recomputeAggregateInContext(
                context: context,
                item: item,
                signal: signal,
                now: now
            )
            try context.save()
        }
    }

    func commitItem(itemID: String) async throws -> CommitOutcome {
        let ref = try await itemRef(itemID: itemID)
        guard ref.state == ImportItemState.committing.rawValue else {
            throw PersistenceError.dbConstraintViolation
        }
        let stagedURL = stagedFileURL(jobID: ref.jobId, itemID: itemID)
        let payload: LocalAssetPayload
        do {
            payload = try await adapter.assetPayload(remoteID: ref.remoteKey)
        } catch {
            if Self.isCancellation(error) { throw error }
            let mapped = Self.failureCode(for: error)
            try await markItemFailed(itemID: itemID, code: mapped.code, retryable: mapped.retryable)
            throw mapped.code
        }
        guard let stagedHash = try? AssetFileProber.contentHash(of: stagedURL),
              stagedHash == payload.contentHash else {
            try await markItemFailed(itemID: itemID, code: .mediaTruncated, retryable: true)
            throw ImportFailureCode.mediaTruncated
        }
        let stagedHead64k = (try? AssetFileProber.head64kHash(of: stagedURL)) ?? payload.head64kHash
        let now = nowMillis()
        let signal = signals[ref.jobId] ?? .none
        let originalsRoot = originalsRootURL
        let context = makeContext()
        do {
            return try await context.perform {
            let item = try Self.fetchItem(context: context, itemID: itemID)
            guard let job = item.value(forKey: "job") as? NSManagedObject,
                  let sourceAsset = item.value(forKey: "sourceAsset") as? NSManagedObject,
                  let source = job.value(forKey: "source") as? NSManagedObject else {
                throw PersistenceError.dbConstraintViolation
            }
            let currentEpoch = source.value(forKey: "epoch") as? Int ?? 1
            let epochAtCreate = job.value(forKey: "epoch_at_create") as? Int ?? 1
            if currentEpoch != epochAtCreate {
                try? FileManager.default.removeItem(at: stagedURL)
                item.setValue(ImportItemState.canceled.rawValue, forKey: "state")
                item.setValue(ImportFailureCode.canceledByEpoch.rawValue, forKey: "failure_code")
                item.setValue(now, forKey: "updated_at")
                try Self.recomputeAggregateInContext(
                    context: context,
                    item: item,
                    signal: signal,
                    now: now
                )
                try context.save()
                return .canceledByEpoch
            }
            let probe = TombstoneMatcher.Probe(
                contentHash: stagedHash,
                sourceFingerprint: ref.sourceFingerprint,
                remoteId: ref.remoteKey,
                byteSize: payload.byteSize,
                head64kHash: stagedHead64k
            )
            let tombstoneRequest = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            tombstoneRequest.predicate = TombstoneMatcher.matchPredicate(probe: probe)
            let tombstoneHit = try context.fetch(tombstoneRequest).contains {
                TombstoneMatcher.rankMatch(tombstone: $0, probe: probe) != nil
            }
            if tombstoneHit {
                try? FileManager.default.removeItem(at: stagedURL)
                item.setValue(ImportItemState.skipped.rawValue, forKey: "state")
                item.setValue(ImportFailureCode.skippedByTombstone.rawValue, forKey: "failure_code")
                item.setValue(now, forKey: "updated_at")
                try Self.recomputeAggregateInContext(
                    context: context,
                    item: item,
                    signal: signal,
                    now: now
                )
                try context.save()
                return .skippedByTombstone
            }

            let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            assetRequest.predicate = NSPredicate(
                format: "content_hash == %@ AND readiness != %@",
                stagedHash,
                Readiness.deleted.rawValue
            )
            assetRequest.fetchLimit = 1
            let outcome: CommitOutcome
            let asset: NSManagedObject
            if let existing = try context.fetch(assetRequest).first {
                asset = existing
                try? FileManager.default.removeItem(at: stagedURL)
                try Self.upsertAssetOrigin(
                    context: context,
                    asset: existing,
                    sourceAsset: sourceAsset,
                    now: now
                )
                outcome = .merged(assetId: existing.value(forKey: "id") as? String ?? "")
            } else {
                let assetId = UUID().uuidString
                let relPath = Self.originalRelPath(assetId: assetId, format: payload.format)
                let targetURL = originalsRoot.appendingPathComponent(relPath)
                do {
                    try FileManager.default.createDirectory(
                        at: targetURL.deletingLastPathComponent(),
                        withIntermediateDirectories: true
                    )
                    if FileManager.default.fileExists(atPath: targetURL.path) {
                        try FileManager.default.removeItem(at: targetURL)
                    }
                    try FileManager.default.moveItem(at: stagedURL, to: targetURL)
                } catch {
                    throw ImportFailureCode.storeWriteFailed
                }
                let created = NSEntityDescription.insertNewObject(forEntityName: "Asset", into: context)
                created.setValue(assetId, forKey: "id")
                created.setValue(stagedHash, forKey: "content_hash")
                created.setValue(NSNumber(value: payload.byteSize), forKey: "byte_size")
                created.setValue(payload.format, forKey: "format")
                created.setValue(payload.mediaKind, forKey: "media_kind")
                if let duration = payload.durationMillis {
                    created.setValue(NSNumber(value: duration), forKey: "duration_ms")
                }
                created.setValue(NSNumber(value: payload.pixelWidth), forKey: "pixel_width")
                created.setValue(NSNumber(value: payload.pixelHeight), forKey: "pixel_height")
                created.setValue(NSNumber(value: payload.exifOrientation), forKey: "exif_orientation")
                let orientationClass: OrientationClass =
                    payload.pixelWidth > payload.pixelHeight ? .landscape
                    : payload.pixelWidth < payload.pixelHeight ? .portrait
                    : .square
                created.setValue(orientationClass.rawValue, forKey: "orientation_class")
                if let capturedAt = payload.capturedAtMillis {
                    created.setValue(NSNumber(value: capturedAt), forKey: "captured_at")
                    created.setValue(payload.capturedAtSource, forKey: "captured_at_source")
                } else {
                    created.setValue(CapturedAtSource.unknown.rawValue, forKey: "captured_at_source")
                }
                created.setValue(NSNumber(value: now), forKey: "imported_at")
                created.setValue(relPath, forKey: "original_rel_path")
                created.setValue(Readiness.ready.rawValue, forKey: "readiness")
                created.setValue(NSNumber(value: false), forKey: "privacy_excluded")
                created.setValue(now, forKey: "created_at")
                created.setValue(now, forKey: "updated_at")
                try Self.upsertAssetOrigin(
                    context: context,
                    asset: created,
                    sourceAsset: sourceAsset,
                    now: now
                )
                asset = created
                outcome = .imported(assetId: assetId)
            }

            try Self.attachToImportBatch(context: context, job: job, asset: asset, now: now)
            try Self.attachToSourceFolder(
                context: context,
                source: source,
                sourceAsset: sourceAsset,
                asset: asset,
                now: now
            )

            let selectionRequest = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            selectionRequest.predicate = NSPredicate(
                format: "sourceAsset.id == %@",
                sourceAsset.value(forKey: "id") as? String ?? ""
            )
            let assetId = asset.value(forKey: "id") as? String ?? ""
            for selection in try context.fetch(selectionRequest) {
                try FrameSelectionValidator.validateExactlyOneReference(
                    assetId: assetId,
                    sourceAssetId: nil
                )
                selection.setValue(asset, forKey: "asset")
                selection.setValue(nil, forKey: "sourceAsset")
                selection.setValue(assetId, forKey: "ref_key")
                selection.setValue(now, forKey: "updated_at")
            }

            item.setValue(ImportItemState.imported.rawValue, forKey: "state")
            item.setValue(nil, forKey: "failure_code")
            item.setValue(now, forKey: "updated_at")

            _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            try Self.recomputeAggregateInContext(
                context: context,
                item: item,
                signal: signal,
                now: now
            )
            try context.save()
            return outcome
        }
        } catch let error as DataValidationFailure {
            try? await markItemFailed(itemID: itemID, code: .dbConstraintViolation, retryable: false)
            throw error
        } catch {
            let mapped = Self.failureCode(for: error)
            try? await markItemFailed(itemID: itemID, code: mapped.code, retryable: true)
            throw error
        }
    }

    func markItemFailed(itemID: String, code: ImportFailureCode, retryable: Bool) async throws {
        let now = nowMillis()
        let ref = try await itemRef(itemID: itemID)
        let signal = signals[ref.jobId] ?? .none
        let context = makeContext()
        try await context.perform {
            let item = try Self.fetchItem(context: context, itemID: itemID)
            let previousRetries = item.value(forKey: "retry_count") as? Int ?? 0
            if retryable && previousRetries < Self.maxRetryCount {
                item.setValue(NSNumber(value: previousRetries + 1), forKey: "retry_count")
                let backoff = Self.retryBackoffMillis[min(previousRetries, Self.retryBackoffMillis.count - 1)]
                item.setValue(NSNumber(value: now + backoff), forKey: "retry_at")
            } else {
                item.setValue(nil, forKey: "retry_at")
            }
            item.setValue(ImportItemState.failed.rawValue, forKey: "state")
            item.setValue(code.rawValue, forKey: "failure_code")
            item.setValue(now, forKey: "updated_at")
            try Self.recomputeAggregateInContext(
                context: context,
                item: item,
                signal: signal,
                now: now
            )
            try context.save()
        }
    }

    @discardableResult
    func retryDueItems(jobID: String, now: Int) async throws -> Int {
        let stamp = nowMillis()
        let signal = signals[jobID] ?? .none
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(
                format: "job.id == %@ AND state == %@ AND retry_at != nil AND retry_at <= %@",
                jobID,
                ImportItemState.failed.rawValue,
                NSNumber(value: now)
            )
            let due = try context.fetch(request)
            for item in due {
                item.setValue(ImportItemState.queued.rawValue, forKey: "state")
                item.setValue(nil, forKey: "retry_at")
                item.setValue(stamp, forKey: "updated_at")
            }
            if !due.isEmpty {
                let job = try Self.fetchJob(context: context, jobID: jobID)
                try Self.recomputeAggregateInContext(
                    context: context,
                    job: job,
                    signal: signal,
                    now: stamp
                )
                try context.save()
            }
            return due.count
        }
    }

    @discardableResult
    func handleSignal(jobID: String, signal: ImportJobSignal) async throws -> ImportJobDTO {
        signals[jobID] = signal
        let now = nowMillis()
        let context = makeContext()
        return try await context.perform {
            let job = try Self.fetchJob(context: context, jobID: jobID)
            let terminal: Set<String> = [
                ImportItemState.imported.rawValue,
                ImportItemState.failed.rawValue,
                ImportItemState.canceled.rawValue,
                ImportItemState.skipped.rawValue,
            ]
            let items = Self.items(of: job)
            switch signal {
            case .userCancel:
                for item in items where !terminal.contains(item.state) {
                    item.row.setValue(ImportItemState.canceled.rawValue, forKey: "state")
                    item.row.setValue(ImportFailureCode.canceledByUser.rawValue, forKey: "failure_code")
                    item.row.setValue(now, forKey: "updated_at")
                }
            case .userPause:
                for item in items where !terminal.contains(item.state) {
                    item.row.setValue(ImportItemState.paused.rawValue, forKey: "state")
                    item.row.setValue(now, forKey: "updated_at")
                }
            case .autoPause:
                break
            case .fatalError:
                for item in items where !terminal.contains(item.state) {
                    item.row.setValue(ImportItemState.failed.rawValue, forKey: "state")
                    item.row.setValue(ImportFailureCode.srcUnavailable.rawValue, forKey: "failure_code")
                    item.row.setValue(now, forKey: "updated_at")
                }
            case .none:
                for item in items where item.state == ImportItemState.paused.rawValue {
                    item.row.setValue(ImportItemState.queued.rawValue, forKey: "state")
                    item.row.setValue(now, forKey: "updated_at")
                }
            }
            try Self.recomputeAggregateInContext(
                context: context,
                job: job,
                signal: signal,
                now: now
            )
            try context.save()
            return Self.jobDTO(job)
        }
    }

    func fetchJob(id: String) async throws -> ImportJobDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportJob")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let job = try context.fetch(request).first else { return nil }
            return Self.jobDTO(job)
        }
    }

    func fetchLatestJob(sourceID: String) async throws -> ImportJobDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportJob")
            request.predicate = NSPredicate(format: "source.id == %@", sourceID)
            request.sortDescriptors = [NSSortDescriptor(key: "created_at", ascending: false)]
            request.fetchLimit = 1
            guard let job = try context.fetch(request).first else { return nil }
            return Self.jobDTO(job)
        }
    }

    func fetchJobs(withState state: String) async throws -> [ImportJobDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportJob")
            request.predicate = NSPredicate(format: "state == %@", state)
            request.sortDescriptors = [NSSortDescriptor(key: "created_at", ascending: true)]
            return try context.fetch(request).map(Self.jobDTO)
        }
    }

    func nextQueuedItemID(jobID: String) async throws -> String? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(
                format: "job.id == %@ AND state == %@",
                jobID,
                ImportItemState.queued.rawValue
            )
            request.sortDescriptors = [NSSortDescriptor(key: "created_at", ascending: true)]
            request.fetchLimit = 1
            return try context.fetch(request).first?.value(forKey: "id") as? String
        }
    }

    @discardableResult
    func requeueInterruptedItems(jobID: String) async throws -> Int {
        let stamp = nowMillis()
        let signal = signals[jobID] ?? .none
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(
                format: "job.id == %@ AND state IN %@",
                jobID,
                [
                    ImportItemState.downloading.rawValue,
                    ImportItemState.committing.rawValue,
                ]
            )
            let rows = try context.fetch(request)
            for row in rows {
                row.setValue(ImportItemState.queued.rawValue, forKey: "state")
                row.setValue(nil, forKey: "retry_at")
                row.setValue(NSNumber(value: 0), forKey: "retry_count")
                row.setValue(nil, forKey: "failure_code")
                row.setValue(stamp, forKey: "updated_at")
            }
            if !rows.isEmpty {
                let job = try Self.fetchJob(context: context, jobID: jobID)
                try Self.recomputeAggregateInContext(
                    context: context,
                    job: job,
                    signal: signal,
                    now: stamp
                )
                try context.save()
            }
            return rows.count
        }
    }

    @discardableResult
    func requeueFailedItems(jobID: String) async throws -> Int {
        let stamp = nowMillis()
        let signal = signals[jobID] ?? .none
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(
                format: "job.id == %@ AND state == %@",
                jobID,
                ImportItemState.failed.rawValue
            )
            let rows = try context.fetch(request)
            for row in rows {
                row.setValue(ImportItemState.queued.rawValue, forKey: "state")
                row.setValue(nil, forKey: "retry_at")
                row.setValue(NSNumber(value: 0), forKey: "retry_count")
                row.setValue(nil, forKey: "failure_code")
                row.setValue(stamp, forKey: "updated_at")
            }
            if !rows.isEmpty {
                let job = try Self.fetchJob(context: context, jobID: jobID)
                try Self.recomputeAggregateInContext(
                    context: context,
                    job: job,
                    signal: signal,
                    now: stamp
                )
                try context.save()
            }
            return rows.count
        }
    }

    func fetchItem(id: String) async throws -> ImportItemDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
            request.predicate = NSPredicate(format: "id == %@", id)
            request.fetchLimit = 1
            guard let item = try context.fetch(request).first else { return nil }
            return Self.itemDTO(item)
        }
    }

    func batchMemberAssetIDs(jobID: String) async throws -> [String] {
        let context = makeContext()
        return try await context.perform {
            guard let batch = try Self.fetchImportBatch(context: context, jobID: jobID) else { return [] }
            let members = batch.value(forKey: "collectionAssets") as? Set<NSManagedObject> ?? []
            return members.compactMap { $0.value(forKeyPath: "asset.id") as? String }
        }
    }

    @discardableResult
    func cancelAndDeleteBatch(jobID: String) async throws -> Int {
        _ = try await handleSignal(jobID: jobID, signal: .userCancel)
        let lookup = makeContext()
        let info = try await lookup.perform { () -> (collection: NSManagedObject?, assetIDs: [String]) in
            let batch = try Self.fetchImportBatch(context: lookup, jobID: jobID)
            let members = batch?.value(forKey: "collectionAssets") as? Set<NSManagedObject> ?? []
            let ids = members.compactMap { $0.value(forKeyPath: "asset.id") as? String }
            return (batch, ids)
        }
        let repository = AssetRepository(controller: controller)
        for assetID in info.assetIDs {
            try? await repository.deleteAsset(id: assetID, reason: .userDelete)
        }
        let cleanup = makeContext()
        try await cleanup.perform {
            if let collection = info.collection {
                let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
                request.predicate = NSPredicate(
                    format: "id == %@",
                    collection.value(forKey: "id") as? String ?? ""
                )
                request.fetchLimit = 1
                if let row = try cleanup.fetch(request).first {
                    cleanup.delete(row)
                }
            }
            let job = try Self.fetchJob(context: cleanup, jobID: jobID)
            cleanup.delete(job)
            try cleanup.save()
        }
        return info.assetIDs.count
    }

    private static func fetchImportBatch(
        context: NSManagedObjectContext,
        jobID: String
    ) throws -> NSManagedObject? {
        let job = try fetchJob(context: context, jobID: jobID)
        let sourceId = job.value(forKeyPath: "source.id") as? String ?? ""
        let createdAt = job.value(forKey: "created_at") as? Int ?? 0
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(
            format: "source.id == %@ AND type == %@ AND sort_key == %@",
            sourceId,
            "import_batch",
            importBatchSortKey(createdAtMillis: createdAt)
        )
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private func recomputeAggregate(jobID: String) async throws {
        let now = nowMillis()
        let signal = signals[jobID] ?? .none
        let context = makeContext()
        try await context.perform {
            let job = try Self.fetchJob(context: context, jobID: jobID)
            try Self.recomputeAggregateInContext(
                context: context,
                job: job,
                signal: signal,
                now: now
            )
            try context.save()
        }
    }

    private static func recomputeAggregateInContext(
        context: NSManagedObjectContext,
        item: NSManagedObject,
        signal: ImportJobSignal,
        now: Int
    ) throws {
        guard let job = item.value(forKey: "job") as? NSManagedObject else { return }
        try recomputeAggregateInContext(
            context: context,
            job: job,
            signal: signal,
            now: now
        )
    }

    private static func recomputeAggregateInContext(
        context: NSManagedObjectContext,
        job: NSManagedObject,
        signal: ImportJobSignal,
        now: Int
    ) throws {
        let states = items(of: job).map(\.state)
        var counts: [String: Int] = [:]
        for state in states {
            counts[state, default: 0] += 1
        }
        let data = try JSONSerialization.data(withJSONObject: counts)
        job.setValue(String(decoding: data, as: UTF8.self), forKey: "counts_json")
        let derived = ImportJobStateValidator.derive(
            itemStates: states,
            signal: signal,
            enumerationComplete: (job.value(forKey: "cursor") as? String) == enumerationDoneCursor
        )
        try ImportJobStateValidator.validateStateWrite(state: derived, derived: derived)
        job.setValue(derived, forKey: "state")
        job.setValue(now, forKey: "updated_at")
    }

    private struct ItemStateRow {
        let row: NSManagedObject
        let state: String
    }

    private static func items(of job: NSManagedObject) -> [ItemStateRow] {
        let rows = (job.value(forKey: "items") as? Set<NSManagedObject>) ?? []
        return rows.map { ItemStateRow(row: $0, state: $0.value(forKey: "state") as? String ?? "") }
    }

    private struct ItemRef: Sendable {
        let jobId: String
        let state: String
        let remoteKey: String
        let sourceFingerprint: String
    }

    private func itemRef(itemID: String) async throws -> ItemRef {
        let context = makeContext()
        return try await context.perform {
            let item = try Self.fetchItem(context: context, itemID: itemID)
            guard let job = item.value(forKey: "job") as? NSManagedObject,
                  let sourceAsset = item.value(forKey: "sourceAsset") as? NSManagedObject,
                  let source = job.value(forKey: "source") as? NSManagedObject else {
                throw PersistenceError.dbConstraintViolation
            }
            return ItemRef(
                jobId: job.value(forKey: "id") as? String ?? "",
                state: item.value(forKey: "state") as? String ?? "",
                remoteKey: sourceAsset.value(forKey: "remote_key") as? String ?? "",
                sourceFingerprint: Self.sourceFingerprint(source)
            )
        }
    }

    static func sourceFingerprint(_ source: NSManagedObject) -> String {
        if let endpointKey = source.value(forKey: "endpoint_key") as? String, !endpointKey.isEmpty {
            return endpointKey
        }
        let type = source.value(forKey: "type") as? String ?? ""
        let sourceId = source.value(forKey: "id") as? String ?? ""
        return "\(type):\(sourceId)"
    }

    private func persistedCursor(jobID: String) async throws -> String? {
        let context = makeContext()
        return try await context.perform {
            let job = try Self.fetchJob(context: context, jobID: jobID)
            return job.value(forKey: "cursor") as? String
        }
    }

    private func tombstoneHit(probe: TombstoneMatcher.Probe) async throws -> Bool {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            request.predicate = TombstoneMatcher.matchPredicate(probe: probe)
            return try context.fetch(request).contains {
                TombstoneMatcher.rankMatch(tombstone: $0, probe: probe) != nil
            }
        }
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        return (error as? URLError)?.code == .cancelled
    }

    private static func failureCode(for error: Error) -> (code: ImportFailureCode, retryable: Bool) {
        if let probe = error as? AssetFileProber.ProbeError {
            switch probe {
            case .unsupportedFormat:
                return (.mediaUnsupportedFormat, false)
            case .unreadable, .decodeFailed:
                return (.mediaDecodeFailed, false)
            }
        }
        if let transport = error as? NasTransportError {
            switch transport {
            case .certUntrusted:
                return (.tlsUntrustedCert, false)
            case .certHostnameMismatch:
                return (.tlsHostnameMismatch, false)
            case .fingerprintChanged:
                return (.tlsFingerprintChanged, false)
            case .cleartextBlocked:
                return (.tlsCleartextBlocked, false)
            case .certificateExpired:
                return (.tlsUntrustedCert, false)
            case .authFailed:
                return (.srcAuthFailed, false)
            case .pathNotFound:
                return (.srcPathNotFound, false)
            case .assetGone:
                return (.srcAssetGone, false)
            case .permissionDenied:
                return (.srcPermissionDenied, false)
            case .localNetworkDenied:
                return (.permLocalNetworkDenied, false)
            case .unavailable, .protocolUnsupported:
                return (.srcUnavailable, true)
            }
        }
        if error is DataValidationFailure {
            return (.dbConstraintViolation, false)
        }
        if let code = error as? ImportFailureCode {
            return (code, code == .mediaTruncated)
        }
        let domain = (error as NSError).domain
        if domain == "PHPhotosErrorDomain" || domain == "CloudPhotoLibraryErrorDomain" || domain == "CKErrorDomain" {
            return (.srcUnavailable, true)
        }
        return (.mediaDecodeFailed, false)
    }

    struct SourceAssetUpsert {
        let row: NSManagedObject
        let isNew: Bool
        let revisionChanged: Bool
    }

    private static func upsertSourceAsset(
        context: NSManagedObjectContext,
        source: NSManagedObject,
        descriptor: SourceAssetDescriptor,
        now: Int
    ) throws -> SourceAssetUpsert {
        let sourceId = source.value(forKey: "id") as? String ?? ""
        let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
        request.predicate = NSPredicate(format: "source.id == %@ AND remote_key == %@", sourceId, descriptor.remoteID)
        request.fetchLimit = 1
        if let row = try context.fetch(request).first {
            let revisionChanged = (row.value(forKey: "revision") as? String) != descriptor.revision
            row.setValue(descriptor.revision, forKey: "revision")
            row.setValue(descriptor.byteSize.map { NSNumber(value: $0) }, forKey: "byte_size")
            row.setValue(NSNumber(value: now), forKey: "last_seen_at")
            row.setValue(AccessState.accessible.rawValue, forKey: "access_state")
            row.setValue(NSNumber(value: 0), forKey: "missing_scan_count")
            row.setValue(now, forKey: "updated_at")
            return SourceAssetUpsert(row: row, isNew: false, revisionChanged: revisionChanged)
        }
        let created = NSEntityDescription.insertNewObject(forEntityName: "SourceAsset", into: context)
        created.setValue(UUID().uuidString, forKey: "id")
        created.setValue(source, forKey: "source")
        created.setValue(descriptor.remoteID, forKey: "remote_key")
        created.setValue(now, forKey: "created_at")
        created.setValue(descriptor.revision, forKey: "revision")
        created.setValue(descriptor.byteSize.map { NSNumber(value: $0) }, forKey: "byte_size")
        created.setValue(NSNumber(value: now), forKey: "last_seen_at")
        created.setValue(AccessState.accessible.rawValue, forKey: "access_state")
        created.setValue(now, forKey: "updated_at")
        return SourceAssetUpsert(row: created, isNew: true, revisionChanged: true)
    }

    private static func attachToSourceFolder(
        context: NSManagedObjectContext,
        source: NSManagedObject,
        sourceAsset: NSManagedObject,
        asset: NSManagedObject,
        now: Int
    ) throws {
        guard (source.value(forKey: "type") as? String) == "webdav" else { return }
        guard let raw = source.value(forKey: "settings_json") as? String,
              let data = raw.data(using: .utf8),
              let settings = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let directories = settings["directories"] as? [String] else { return }
        let recursive = settings["recursive"] as? Bool ?? true
        let sourceID = source.value(forKey: "id") as? String ?? ""
        let remoteKey = sourceAsset.value(forKey: "remote_key") as? String ?? ""
        let fileDir = remoteKey.split(separator: "/").dropLast().joined(separator: "/")
        let matched = directories.filter {
            fileDir == $0 || (recursive && ($0.isEmpty || fileDir.hasPrefix($0 + "/")))
        }
        guard let target = matched.max(by: { $0.count < $1.count }) else { return }
        let collectionRequest = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        collectionRequest.predicate = NSPredicate(
            format: "source.id == %@ AND type == %@ AND rel_path == %@",
            sourceID,
            "source_folder",
            target
        )
        collectionRequest.fetchLimit = 1
        guard let collection = try context.fetch(collectionRequest).first else { return }
        let linkRequest = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
        linkRequest.predicate = NSPredicate(
            format: "collection.id == %@ AND asset.id == %@",
            collection.value(forKey: "id") as? String ?? "",
            asset.value(forKey: "id") as? String ?? ""
        )
        linkRequest.fetchLimit = 1
        if try context.fetch(linkRequest).first == nil {
            let link = NSEntityDescription.insertNewObject(forEntityName: "CollectionAsset", into: context)
            link.setValue(collection, forKey: "collection")
            link.setValue(asset, forKey: "asset")
            link.setValue(now, forKey: "created_at")
            link.setValue(now, forKey: "updated_at")
        }
        let assetID = asset.value(forKey: "id") as? String ?? ""
        let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
        let staleAssets = origins.compactMap { $0.value(forKey: "asset") as? NSManagedObject }
            .filter { ($0.value(forKey: "id") as? String ?? "") != assetID }
        removeFolderMembership(context: context, sourceID: sourceID, assets: staleAssets)
    }

    private static func attachToImportBatch(
        context: NSManagedObjectContext,
        job: NSManagedObject,
        asset: NSManagedObject,
        now: Int
    ) throws {
        let sourceId = job.value(forKeyPath: "source.id") as? String ?? ""
        let createdAt = job.value(forKey: "created_at") as? Int ?? 0
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(
            format: "source.id == %@ AND type == %@ AND sort_key == %@",
            sourceId,
            "import_batch",
            importBatchSortKey(createdAtMillis: createdAt)
        )
        request.fetchLimit = 1
        guard let batch = try context.fetch(request).first else { return }
        let linkRequest = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
        linkRequest.predicate = NSPredicate(
            format: "collection.id == %@ AND asset.id == %@",
            batch.value(forKey: "id") as? String ?? "",
            asset.value(forKey: "id") as? String ?? ""
        )
        linkRequest.fetchLimit = 1
        guard try context.fetch(linkRequest).first == nil else { return }
        let link = NSEntityDescription.insertNewObject(forEntityName: "CollectionAsset", into: context)
        link.setValue(batch, forKey: "collection")
        link.setValue(asset, forKey: "asset")
        link.setValue(now, forKey: "created_at")
        link.setValue(now, forKey: "updated_at")
    }

    private static func upsertAssetOrigin(
        context: NSManagedObjectContext,
        asset: NSManagedObject,
        sourceAsset: NSManagedObject,
        now: Int
    ) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        request.predicate = NSPredicate(
            format: "asset.id == %@ AND sourceAsset.id == %@",
            asset.value(forKey: "id") as? String ?? "",
            sourceAsset.value(forKey: "id") as? String ?? ""
        )
        request.fetchLimit = 1
        let row = try context.fetch(request).first ?? {
            let created = NSEntityDescription.insertNewObject(forEntityName: "AssetOrigin", into: context)
            created.setValue(asset, forKey: "asset")
            created.setValue(sourceAsset, forKey: "sourceAsset")
            created.setValue(now, forKey: "created_at")
            return created
        }()
        row.setValue(NSNumber(value: now), forKey: "imported_at")
        row.setValue(PolicyState.allowed.rawValue, forKey: "policy_state")
        row.setValue(OriginState.active.rawValue, forKey: "origin_state")
        row.setValue(now, forKey: "updated_at")
    }

    private static func originalRelPath(assetId: String, format: String) -> String {
        let ext: String
        switch format {
        case AssetFormat.jpeg.rawValue: ext = "jpg"
        case AssetFormat.png.rawValue: ext = "png"
        case AssetFormat.heic.rawValue: ext = "heic"
        case AssetFormat.mp4.rawValue: ext = "mp4"
        case AssetFormat.mov.rawValue: ext = "mov"
        default: ext = format
        }
        return "\(assetId.prefix(2))/\(assetId).\(ext)"
    }

    private static func fetchSource(context: NSManagedObjectContext, sourceID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "id == %@", sourceID)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else {
            throw ImportFailureCode.srcUnavailable
        }
        return row
    }

    private static func fetchJob(context: NSManagedObjectContext, jobID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "ImportJob")
        request.predicate = NSPredicate(format: "id == %@", jobID)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return row
    }

    private static func fetchItem(context: NSManagedObjectContext, itemID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "ImportItem")
        request.predicate = NSPredicate(format: "id == %@", itemID)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return row
    }

    private static func jobDTO(_ row: NSManagedObject) -> ImportJobDTO {
        var counts: [String: Int] = [:]
        if let raw = row.value(forKey: "counts_json") as? String,
           let data = raw.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Int] {
            counts = parsed
        }
        return ImportJobDTO(
            id: row.value(forKey: "id") as? String ?? "",
            sourceId: row.value(forKeyPath: "source.id") as? String ?? "",
            state: row.value(forKey: "state") as? String ?? "",
            counts: counts,
            cursor: row.value(forKey: "cursor") as? String,
            totalKnown: (row.value(forKey: "total_known") as? NSNumber)?.intValue,
            createdAt: row.value(forKey: "created_at") as? Int ?? 0
        )
    }

    private static func itemDTO(_ row: NSManagedObject) -> ImportItemDTO {
        ImportItemDTO(
            id: row.value(forKey: "id") as? String ?? "",
            jobId: row.value(forKeyPath: "job.id") as? String ?? "",
            state: row.value(forKey: "state") as? String ?? "",
            retryCount: row.value(forKey: "retry_count") as? Int ?? 0,
            retryAt: (row.value(forKey: "retry_at") as? NSNumber)?.intValue,
            failureCode: row.value(forKey: "failure_code") as? String
        )
    }
}
