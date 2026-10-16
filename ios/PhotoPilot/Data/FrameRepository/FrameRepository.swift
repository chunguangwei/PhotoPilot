import CoreData
import Foundation

struct SelectionRefEdit: Sendable {
    let assetId: String?
    let sourceAssetId: String?

    var refKey: String { assetId ?? sourceAssetId ?? "" }
}

actor FrameRepository {
    private let controller: PersistenceController
    private let cache: ResolutionCache

    init(controller: PersistenceController, cache: ResolutionCache = ResolutionCache()) {
        self.controller = controller
        self.cache = cache
    }

    private func makeContext() -> NSManagedObjectContext {
        controller.container.newBackgroundContext()
    }

    private func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private func originalsFileURL(relPath: String) -> URL {
        controller.storeURL.deletingLastPathComponent()
            .appendingPathComponent("originals", isDirectory: true)
            .appendingPathComponent(relPath)
    }

    func resolve(frameID: String) async throws -> SelectionResolution {
        let context = makeContext()
        return try await context.perform {
            try self.resolveInContext(context: context, frameID: frameID)
        }
    }

    private func resolveInContext(context: NSManagedObjectContext, frameID: String) throws -> SelectionResolution {
        let frame = try Self.fetchFrame(context: context, frameID: frameID)
        let selectionVersion = Int64(frame.value(forKey: "selection_version") as? Int ?? 0)

        let sourceRequest = NSFetchRequest<NSManagedObject>(entityName: "Source")
        var epochs: [String: Int64] = [:]
        for source in try context.fetch(sourceRequest) {
            if let id = source.value(forKey: "id") as? String {
                epochs[id] = Int64(source.value(forKey: "epoch") as? Int ?? 0)
            }
        }
        let libraryVersion = try Self.libraryVersionValue(context: context)
        let cacheKey = ResolutionCache.Key(
            frameId: frameID,
            selectionVersion: selectionVersion,
            sourceEpochsKey: ResolutionCache.sourceEpochsKey(epochs),
            libraryVersion: libraryVersion
        )
        if let cached = self.cache.get(cacheKey) { return cached }

        let skeleton = try self.loadSkeleton(context: context, frameID: frameID)
        let firstPass = SelectionResolver.resolve(
            SelectionResolveInput(
                selections: skeleton.selections,
                rules: skeleton.rules,
                collectionChildren: skeleton.collectionChildren,
                collectionAssets: skeleton.collectionAssets,
                assets: [:]
            )
        )
        let assets = try self.loadResolvableAssets(context: context, candidateIds: firstPass.candidates)
        let resolution = SelectionResolver.resolve(
            SelectionResolveInput(
                selections: skeleton.selections,
                rules: skeleton.rules,
                collectionChildren: skeleton.collectionChildren,
                collectionAssets: skeleton.collectionAssets,
                assets: assets
            )
        )
        self.cache.put(cacheKey, resolution: resolution)
        return resolution
    }

    func save(
        frameID: String,
        baseVersion: Int64,
        addIncludes: [SelectionRefEdit] = [],
        removeRefs: [SelectionRefEdit] = [],
        addExcludes: [SelectionRefEdit] = [],
        ruleChanges: [RuleChange] = [],
        removeRules: [String] = [],
        manualOrderRefs: [String]? = nil
    ) async throws -> SelectionSaveResult {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            let currentVersion = Int64(frame.value(forKey: "selection_version") as? Int ?? 0)
            let orderMode = frame.value(forKey: "order_mode") as? String ?? PlaybackQueue.orderCapturedAsc

            let selectionRows = try Self.fetchSelections(context: context, frameID: frameID)
            let ruleRows = try Self.fetchRules(context: context, frameID: frameID)

            var existingIncludes: [SaveIncludeRow] = []
            var existingExcludes: [String] = []
            for row in selectionRows {
                let ref = row.value(forKey: "ref_key") as? String ?? ""
                switch row.value(forKey: "mode") as? String {
                case SelectionResolver.modeInclude:
                    existingIncludes.append(
                        SaveIncludeRow(
                            ref: ref,
                            manualOrder: (row.value(forKey: "manual_order") as? Int).map(Int64.init),
                            addedAt: Int64(row.value(forKey: "added_at") as? Int ?? 0)
                        )
                    )
                case SelectionResolver.modeExclude:
                    existingExcludes.append(ref)
                default:
                    break
                }
            }
            if let manualOrderRefs {
                var positions: [String: Int64] = [:]
                for (index, ref) in manualOrderRefs.enumerated() {
                    positions[ref] = Int64(index + 1)
                }
                existingIncludes = existingIncludes.map { row in
                    guard let position = positions[row.ref] else { return row }
                    return SaveIncludeRow(ref: row.ref, manualOrder: position, addedAt: row.addedAt)
                }
            }
            let existingRules = ruleRows.map {
                SaveRuleRow(
                    collectionId: $0.value(forKeyPath: "collection.id") as? String ?? "",
                    recursive: $0.value(forKey: "recursive") as? Bool ?? false,
                    includeFuture: $0.value(forKey: "include_future") as? Bool ?? false
                )
            }

            var request = SelectionSaveRequest()
            request.addIncludes = addIncludes.map { AddInclude(ref: $0.refKey, addedAt: now) }
            request.removeRefs = removeRefs.map(\.refKey)
            request.addExcludes = addExcludes.map(\.refKey)
            request.ruleChanges = ruleChanges
            request.removeRules = removeRules

            let result = SelectionSavePlanner.plan(
                SelectionSaveInput(
                    currentVersion: currentVersion,
                    baseVersion: baseVersion,
                    orderMode: orderMode,
                    existingIncludes: existingIncludes,
                    existingExcludes: existingExcludes,
                    existingRules: existingRules,
                    request: request
                )
            )
            guard case .ok(let newVersion, let includeRows, let excludeRows, let finalRules) = result else {
                return result
            }

            var refEdits: [String: SelectionRefEdit] = [:]
            for row in selectionRows {
                let ref = row.value(forKey: "ref_key") as? String ?? ""
                refEdits[ref] = SelectionRefEdit(
                    assetId: row.value(forKeyPath: "asset.id") as? String,
                    sourceAssetId: row.value(forKeyPath: "sourceAsset.id") as? String
                )
            }
            for edit in addIncludes + addExcludes + removeRefs {
                refEdits[edit.refKey] = edit
            }
            for change in ruleChanges where !change.includeFuture {
                for member in change.members where refEdits[member] == nil {
                    refEdits[member] = SelectionRefEdit(assetId: member, sourceAssetId: nil)
                }
            }

            let finalIncludeRefs = Set(includeRows.map(\.ref))
            let finalExcludeRefs = Set(excludeRows)
            for row in selectionRows {
                let ref = row.value(forKey: "ref_key") as? String ?? ""
                let mode = row.value(forKey: "mode") as? String
                let obsolete = (mode == SelectionResolver.modeInclude && !finalIncludeRefs.contains(ref)) ||
                    (mode == SelectionResolver.modeExclude && !finalExcludeRefs.contains(ref))
                if obsolete { context.delete(row) }
            }

            var existingByRef: [String: NSManagedObject] = [:]
            for row in selectionRows {
                if let ref = row.value(forKey: "ref_key") as? String, !row.isDeleted {
                    existingByRef[ref] = row
                }
            }
            for row in includeRows {
                try Self.upsertSelectionRow(
                    context: context,
                    frame: frame,
                    ref: row.ref,
                    mode: SelectionResolver.modeInclude,
                    manualOrder: row.manualOrder,
                    addedAt: row.addedAt,
                    refEdits: refEdits,
                    existingByRef: existingByRef,
                    now: now
                )
            }
            for ref in excludeRows {
                let addedAt = existingByRef[ref].map { Int64($0.value(forKey: "added_at") as? Int ?? 0) } ?? now
                try Self.upsertSelectionRow(
                    context: context,
                    frame: frame,
                    ref: ref,
                    mode: SelectionResolver.modeExclude,
                    manualOrder: nil,
                    addedAt: addedAt,
                    refEdits: refEdits,
                    existingByRef: existingByRef,
                    now: now
                )
            }

            let finalRuleCollections = Set(finalRules.map(\.collectionId))
            for row in ruleRows {
                let collectionId = row.value(forKeyPath: "collection.id") as? String ?? ""
                if !finalRuleCollections.contains(collectionId) { context.delete(row) }
            }
            for row in finalRules {
                let existing = ruleRows.first {
                    ($0.value(forKeyPath: "collection.id") as? String) == row.collectionId && !$0.isDeleted
                }
                let target = existing ?? NSEntityDescription.insertNewObject(
                    forEntityName: "FrameDirectoryRule",
                    into: context
                )
                target.setValue(frame, forKey: "frame")
                target.setValue(try Self.fetchCollection(context: context, collectionID: row.collectionId), forKey: "collection")
                target.setValue(row.recursive, forKey: "recursive")
                target.setValue(row.includeFuture, forKey: "include_future")
                if existing == nil { target.setValue(now, forKey: "created_at") }
                target.setValue(now, forKey: "updated_at")
            }

            frame.setValue(Int(newVersion), forKey: "selection_version")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
            self.cache.clear()
            return result
        }
    }

    func buildPlayQueue(frameID: String) async throws -> [String] {
        let context = makeContext()
        return try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            let resolution = try self.resolveInContext(context: context, frameID: frameID)
            guard !resolution.playable.isEmpty else { return [] }

            let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            assetRequest.predicate = NSPredicate(format: "id IN %@", resolution.playable)
            var entities: [String: NSManagedObject] = [:]
            for asset in try context.fetch(assetRequest) {
                if let id = asset.value(forKey: "id") as? String { entities[id] = asset }
            }
            var selectionsByRef: [String: NSManagedObject] = [:]
            for row in try Self.fetchSelections(context: context, frameID: frameID) {
                if let ref = row.value(forKey: "ref_key") as? String { selectionsByRef[ref] = row }
            }
            let queueAssets = resolution.playable.compactMap { id -> QueueAsset? in
                guard let entity = entities[id] else { return nil }
                let importedAt = Int64(entity.value(forKey: "imported_at") as? Int ?? 0)
                return QueueAsset(
                    id: id,
                    capturedAt: (entity.value(forKey: "captured_at") as? Int).map(Int64.init),
                    importedAt: importedAt,
                    manualOrder: (selectionsByRef[id]?.value(forKey: "manual_order") as? Int).map(Int64.init),
                    addedAt: selectionsByRef[id].map { Int64($0.value(forKey: "added_at") as? Int ?? 0) } ?? importedAt
                )
            }
            let seed = UInt64(bitPattern: Int64(frame.value(forKey: "random_seed") as? Int ?? 0))
            return PlaybackQueue.buildQueue(
                assets: queueAssets,
                orderMode: frame.value(forKey: "order_mode") as? String ?? PlaybackQueue.orderCapturedAsc,
                randomSeed: seed
            )
        }
    }

    private func loadSkeleton(context: NSManagedObjectContext, frameID: String) throws -> SelectionResolveInput {
        let selections = try Self.fetchSelections(context: context, frameID: frameID).map {
            SelectionRef(
                assetId: $0.value(forKeyPath: "asset.id") as? String,
                sourceAssetId: $0.value(forKeyPath: "sourceAsset.id") as? String,
                mode: $0.value(forKey: "mode") as? String ?? ""
            )
        }
        let rules = try Self.fetchRules(context: context, frameID: frameID).map {
            DirectoryRuleRef(
                collectionId: $0.value(forKeyPath: "collection.id") as? String ?? "",
                recursive: $0.value(forKey: "recursive") as? Bool ?? false
            )
        }
        var children: [String: [String]] = [:]
        let collectionRequest = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        for collection in try context.fetch(collectionRequest) {
            guard let parentId = collection.value(forKeyPath: "parent.id") as? String,
                  let id = collection.value(forKey: "id") as? String else { continue }
            children[parentId, default: []].append(id)
        }
        var membership: [String: [String]] = [:]
        let subtree = Self.collectSubtree(roots: rules.map(\.collectionId), children: children)
        if !subtree.isEmpty {
            let membershipRequest = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
            membershipRequest.predicate = NSPredicate(format: "collection.id IN %@", subtree)
            for row in try context.fetch(membershipRequest) {
                guard let collectionId = row.value(forKeyPath: "collection.id") as? String,
                      let assetId = row.value(forKeyPath: "asset.id") as? String else { continue }
                membership[collectionId, default: []].append(assetId)
            }
        }
        return SelectionResolveInput(
            selections: selections,
            rules: rules,
            collectionChildren: children,
            collectionAssets: membership,
            assets: [:]
        )
    }

    private func loadResolvableAssets(
        context: NSManagedObjectContext,
        candidateIds: [String]
    ) throws -> [String: ResolvableAsset] {
        guard !candidateIds.isEmpty else { return [:] }
        let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        assetRequest.predicate = NSPredicate(format: "id IN %@", candidateIds)
        var entities: [String: NSManagedObject] = [:]
        for asset in try context.fetch(assetRequest) {
            if let id = asset.value(forKey: "id") as? String { entities[id] = asset }
        }
        var allowedIds = Set<String>()
        let originRequest = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        originRequest.predicate = NSPredicate(
            format: "asset.id IN %@ AND policy_state == %@ AND origin_state == %@",
            candidateIds, "allowed", "active"
        )
        for origin in try context.fetch(originRequest) {
            if let id = origin.value(forKeyPath: "asset.id") as? String { allowedIds.insert(id) }
        }
        var result: [String: ResolvableAsset] = [:]
        for id in candidateIds {
            guard let entity = entities[id] else { continue }
            let relPath = entity.value(forKey: "original_rel_path") as? String ?? ""
            result[id] = ResolvableAsset(
                id: id,
                readiness: entity.value(forKey: "readiness") as? String ?? "",
                privacyExcluded: entity.value(forKey: "privacy_excluded") as? Bool ?? false,
                deleted: entity.value(forKey: "deleted_at") != nil,
                fileExists: FileManager.default.fileExists(atPath: originalsFileURL(relPath: relPath).path),
                policyAllowed: allowedIds.contains(id),
                mediaKind: entity.value(forKey: "media_kind") as? String ?? "image"
            )
        }
        return result
    }

    private static func upsertSelectionRow(
        context: NSManagedObjectContext,
        frame: NSManagedObject,
        ref: String,
        mode: String,
        manualOrder: Int64?,
        addedAt: Int64,
        refEdits: [String: SelectionRefEdit],
        existingByRef: [String: NSManagedObject],
        now: Int64
    ) throws {
        let edit = refEdits[ref] ?? SelectionRefEdit(assetId: ref, sourceAssetId: nil)
        let row = existingByRef[ref] ?? NSEntityDescription.insertNewObject(
            forEntityName: "FrameSelection",
            into: context
        )
        row.setValue(frame, forKey: "frame")
        row.setValue(ref, forKey: "ref_key")
        row.setValue(mode, forKey: "mode")
        row.setValue(manualOrder.map { Int($0) }, forKey: "manual_order")
        row.setValue(Int(addedAt), forKey: "added_at")
        if let assetId = edit.assetId {
            row.setValue(try Self.fetchAsset(context: context, assetID: assetId), forKey: "asset")
            row.setValue(nil, forKey: "sourceAsset")
        } else if let sourceAssetId = edit.sourceAssetId {
            row.setValue(nil, forKey: "asset")
            row.setValue(try Self.fetchSourceAsset(context: context, sourceAssetID: sourceAssetId), forKey: "sourceAsset")
        }
        if existingByRef[ref] == nil { row.setValue(Int(now), forKey: "created_at") }
        row.setValue(Int(now), forKey: "updated_at")
    }

    private static func collectSubtree(roots: [String], children: [String: [String]]) -> [String] {
        var visited = Set<String>()
        var queue = roots
        while !queue.isEmpty {
            let current = queue.removeFirst()
            guard visited.insert(current).inserted else { continue }
            queue.append(contentsOf: children[current] ?? [])
        }
        return Array(visited)
    }

    private static func fetchFrame(context: NSManagedObjectContext, frameID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
        request.predicate = NSPredicate(format: "id == %@", frameID)
        request.fetchLimit = 1
        guard let frame = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return frame
    }

    private static func fetchAsset(context: NSManagedObjectContext, assetID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        request.predicate = NSPredicate(format: "id == %@", assetID)
        request.fetchLimit = 1
        guard let asset = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return asset
    }

    private static func fetchSourceAsset(context: NSManagedObjectContext, sourceAssetID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
        request.predicate = NSPredicate(format: "id == %@", sourceAssetID)
        request.fetchLimit = 1
        guard let sourceAsset = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return sourceAsset
    }

    private static func fetchCollection(context: NSManagedObjectContext, collectionID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        request.predicate = NSPredicate(format: "id == %@", collectionID)
        request.fetchLimit = 1
        guard let collection = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return collection
    }

    private static func fetchSelections(context: NSManagedObjectContext, frameID: String) throws -> [NSManagedObject] {
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        request.predicate = NSPredicate(format: "frame.id == %@", frameID)
        return try context.fetch(request)
    }

    private static func fetchRules(context: NSManagedObjectContext, frameID: String) throws -> [NSManagedObject] {
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameDirectoryRule")
        request.predicate = NSPredicate(format: "frame.id == %@", frameID)
        return try context.fetch(request)
    }

    private static func libraryVersionValue(context: NSManagedObjectContext) throws -> Int64 {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", PersistenceController.libraryVersionKey)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else { return 0 }
        return (row.value(forKey: "value_json") as? String).flatMap(Int64.init) ?? 0
    }
}
