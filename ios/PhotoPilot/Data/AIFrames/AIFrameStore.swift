import CoreData
import CryptoKit
import Foundation

struct AIFrameCandidate: Sendable {
    let id: String
    let contentHash: String
    let relativePath: String
    var remoteDescriptor: RemoteMediaDescriptor? = nil
    var cacheable = true
}

/// AI configuration, progress and membership changes share the library's SQLite transaction.
actor AIFrameStore {
    let controller: PersistenceController
    static let prefix = "ai.frame.v1."
    static let cachePrefix = "ai.embedding.v1."

    init(controller: PersistenceController) { self.controller = controller }

    func states() async throws -> [AIFrameState] {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key BEGINSWITH %@", Self.prefix)
            return try context.fetch(request).map { row in
                guard let string = row.value(forKey: "value_json") as? String,
                      let data = string.data(using: .utf8) else { throw AIFrameError.invalidState }
                return try AIFrameState.decode(data)
            }
        }
    }

    func state(frameID: String) async throws -> AIFrameState? {
        let context = controller.container.newBackgroundContext()
        return try await context.perform { try Self.read(context: context, frameID: frameID) }
    }

    @discardableResult
    func configure(_ configuration: AIFrameConfiguration) async throws -> AIFrameConfiguration {
        try configuration.validate()
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard try Self.frame(context, id: configuration.frameID) != nil else { throw AIFrameError.staleRun }
            var state = try Self.read(context: context, frameID: configuration.frameID) ?? AIFrameState(configuration: configuration)
            let previous = state.configuration
            var configuration = configuration
            configuration.revision = previous.revision + 1
            state.configuration = configuration
            state.runToken = nil
            state.status = state.isUserPaused ? "userPaused" : (state.status == "failed" ? "failed" : "idle")
            state.lastError = nil
            if previous.scanFingerprint != configuration.scanFingerprint {
                state.completedContentVersions.removeAll()
                state.matches.removeAll()
            }
            try Self.write(state, context: context)
            try context.save()
            return configuration
        }
    }

    func begin(frameID: String, configuration: AIFrameConfiguration, total: Int, skipped: Int = 0) async throws -> (AIFrameState, AIFrameRunToken) {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard try Self.frame(context, id: frameID) != nil,
                  var state = try Self.read(context: context, frameID: frameID),
                  state.configuration == configuration, !state.isUserPaused else { throw AIFrameError.staleRun }
            let token = state.beginRun()
            state.total = total
            state.skipped = skipped
            try Self.write(state, context: context)
            try context.save()
            return (state, token)
        }
    }

    func stop(frameID: String, status: String = "cancelled", expectedConfiguration: AIFrameConfiguration? = nil, lastError: String? = nil) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard var state = try Self.read(context: context, frameID: frameID),
                  expectedConfiguration == nil || state.configuration == expectedConfiguration else { return }
            state.runToken = nil
            if ["cancelled", "userPaused", "needsResume"].contains(status) { state.userPaused = true }
            state.status = state.isUserPaused && !["cancelled", "needsResume"].contains(status) ? "userPaused" : status
            state.lastError = lastError
            try Self.write(state, context: context)
            try context.save()
        }
    }

    func resume(frameID: String) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard var state = try Self.read(context: context, frameID: frameID) else { return }
            state.userPaused = false
            state.status = "idle"
            state.lastError = nil
            try Self.write(state, context: context)
            try context.save()
        }
    }

    func candidates(configuration: AIFrameConfiguration) async throws -> [AIFrameCandidate] {
        let context = controller.container.newBackgroundContext()
        return try await context.perform { try Self.candidates(context, configuration: configuration) }
    }

    func libraryVersion() async throws -> Int {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            let row = try Self.setting(context, key: PersistenceController.libraryVersionKey)
            return (row?.value(forKey: "value_json") as? String).flatMap(Int.init) ?? 0
        }
    }

    func cachedVector(assetID: String, version: String, expectedDimension: Int? = nil) async throws -> [Float]? {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard try Self.canCache(context: context, assetID: assetID) else { return nil }
            guard let row = try Self.setting(context, key: Self.cacheKey(assetID: assetID, version: version)),
                  let string = row.value(forKey: "value_json") as? String,
                  let data = string.data(using: .utf8) else { return nil }
            guard let vector = try? JSONDecoder().decode([Float].self, from: data),
                  expectedDimension == nil || vector.count == expectedDimension,
                  (try? AIFrameScoring.cosine(vector, vector)) != nil else { return nil }
            row.setValue(Int(Date().timeIntervalSince1970 * 1000), forKey: "updated_at")
            try context.save()
            return vector
        }
    }

    func cacheVector(_ vector: [Float], assetID: String, version: String) async throws {
        _ = try AIFrameScoring.cosine(vector, vector)
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            // A deletion racing inference must never recreate the deleted photo's features.
            guard try Self.canCache(context: context, assetID: assetID), let media = try Self.eligibleAsset(context, id: assetID) else { return }
            if assetID.hasPrefix("remote:"), !version.hasPrefix(try Self.contentVersion(media, context: context) + ":") { return }
            let key = Self.cacheKey(assetID: assetID, version: version)
            let row = try Self.setting(context, key: key) ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
            try Self.assign(row, key: key, data: JSONEncoder().encode(vector))
            try Self.trimFeatureCache(context)
            try context.save()
        }
    }

    func record(frameID: String, token: AIFrameRunToken, candidate: AIFrameCandidate,
                version: String, match: AIFrameMatch?, apply: Bool, failed: Bool = false) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard let frame = try Self.frame(context, id: frameID),
                  var state = try Self.read(context: context, frameID: frameID), state.accepts(token) else { throw AIFrameError.staleRun }
            guard let asset = try Self.eligibleAsset(context, id: candidate.id),
                  try Self.contentVersion(asset, context: context) == candidate.contentHash,
                  try Self.isInScope(context, assetID: candidate.id, configuration: state.configuration) else {
                state.skipped += 1
                try Self.write(state, context: context)
                try context.save()
                return
            }
            if failed { state.failed += 1 }
            else {
                state.succeeded += 1
                state.completedContentVersions[candidate.id] = version
            }
            if let match {
                state.matches[candidate.id] = match
                if apply { try Self.apply(match, asset: asset, frame: frame, state: &state, context: context) }
            }
            try Self.write(state, context: context)
            try context.save()
        }
    }

    func finish(frameID: String, token: AIFrameRunToken, validIDs: Set<String>, apply: Bool) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard let frame = try Self.frame(context, id: frameID),
                  var state = try Self.read(context: context, frameID: frameID), state.accepts(token) else { throw AIFrameError.staleRun }
            if apply {
                for id in state.ownedAssetIDs.subtracting(validIDs) {
                    try Self.removeOwned(id, frame: frame, state: &state, context: context)
                }
            }
            state.status = state.failed > 0 && state.succeeded == 0 && state.skipped == 0 ? "failed" : "complete"
            state.lastError = state.status == "failed" ? AIFrameError.classificationFailed.rawValue : nil
            state.runToken = nil
            try Self.write(state, context: context)
            try context.save()
        }
    }

    func discardEmptyDraft(frameID: String, name: String, configuration: AIFrameConfiguration) async throws {
        let context = controller.container.newBackgroundContext()
        var expected = configuration
        expected.frameID = frameID
        expected.revision += 1
        let expectedConfiguration = expected
        try await context.perform {
            guard let frame = try Self.frame(context, id: frameID),
                  frame.value(forKey: "name") as? String == name,
                  (frame.value(forKey: "selection_version") as? Int ?? 0) == 0,
                  frame.value(forKey: "created_at") as? Int == frame.value(forKey: "updated_at") as? Int else { return }
            for entity in ["FrameSelection", "FrameDirectoryRule"] {
                let request = NSFetchRequest<NSManagedObject>(entityName: entity)
                request.predicate = NSPredicate(format: "frame == %@", frame)
                guard try context.count(for: request) == 0 else { return }
            }
            if let state = try Self.read(context: context, frameID: frameID), state.configuration != expectedConfiguration { return }
            try Self.removeState(context: context, frameID: frameID)
            context.delete(frame)
            try context.save()
        }
    }

    func applyPreview(frameID: String, configuration: AIFrameConfiguration, matches: [AIFrameMatch], manualIncludes: Set<String> = [], manualExcludes: Set<String> = []) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            try Self.applyPreview(context: context, frameID: frameID, configuration: configuration, matches: matches,
                manualIncludes: manualIncludes, manualExcludes: manualExcludes)
            try context.save()
        }
    }

    private static func applyPreview(context: NSManagedObjectContext, frameID: String, configuration: AIFrameConfiguration,
        matches: [AIFrameMatch], manualIncludes: Set<String> = [], manualExcludes: Set<String> = [],
        processedVersions: [String: String] = [:], userPaused: Bool? = nil, complete: Bool = true) throws {
            guard let frame = try Self.frame(context, id: frameID),
                  var state = try Self.read(context: context, frameID: frameID), state.configuration == configuration else { throw AIFrameError.staleRun }
            let candidates = try Self.candidates(context, configuration: configuration)
            let eligible = Set(candidates.map(\.id))
            let currentVersions = Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, $0.contentHash) })
            for match in matches where match.contentVersion != nil || match.assetID.hasPrefix("remote:") {
                guard match.contentVersion != nil, currentVersions[match.assetID] == match.contentVersion else { throw AIFrameError.staleRun }
            }
            let includeAliases = try manualIncludes.reduce(into: Set<String>()) { $0.formUnion(try RemoteMediaRepository.referenceAliases(context: context, runtimeID: $1)) }
            let excludedAliases = try manualExcludes.reduce(into: Set<String>()) { $0.formUnion(try RemoteMediaRepository.referenceAliases(context: context, runtimeID: $1)) }
            state.recordManualChange(includes: includeAliases, removed: excludedAliases)
            for id in manualIncludes {
                let aliases = try RemoteMediaRepository.referenceAliases(context: context, runtimeID: id).map { MediaReference(runtimeID: $0).rawID }
                let oldExcludes = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
                oldExcludes.predicate = NSPredicate(format: "frame == %@ AND mode == %@ AND ref_key IN %@", frame, "exclude", aliases)
                for row in try context.fetch(oldExcludes) { context.delete(row) }
            }
            for id in manualIncludes.union(manualExcludes).intersection(eligible) {
                guard let asset = try Self.eligibleAsset(context, id: id) else { continue }
                let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
                request.predicate = NSPredicate(format: "frame == %@ AND ref_key == %@", frame, MediaReference(runtimeID: id).rawID)
                let row = try context.fetch(request).first ?? NSEntityDescription.insertNewObject(forEntityName: "FrameSelection", into: context)
                let now = Int(Date().timeIntervalSince1970 * 1000)
                row.setValue(frame, forKey: "frame")
                Self.bind(asset, id: id, selection: row)
                row.setValue(manualIncludes.contains(id) ? "include" : "exclude", forKey: "mode")
                row.setValue(now, forKey: "added_at")
                if row.value(forKey: "created_at") == nil { row.setValue(now, forKey: "created_at") }
                row.setValue(now, forKey: "updated_at")
                Self.bump(frame)
            }
            for match in matches where eligible.contains(match.assetID) {
                guard let asset = try Self.eligibleAsset(context, id: match.assetID) else { continue }
                try Self.apply(match, asset: asset, frame: frame, state: &state, context: context)
                state.matches[match.assetID] = match
            }
            if complete {
                for id in state.ownedAssetIDs.subtracting(eligible) { try Self.removeOwned(id, frame: frame, state: &state, context: context) }
            }
            for (id, version) in processedVersions where eligible.contains(id) {
                if let content = currentVersions[id], version == content + ":" + configuration.modelID + ":" + configuration.modelRevision + ":preprocess2" {
                    state.completedContentVersions[id] = version
                }
            }
            if let userPaused { state.userPaused = userPaused }
            if state.isUserPaused { state.runToken = nil; state.status = "userPaused" }
            try Self.write(state, context: context)
    }

    func mappedFrameID(draftID: String, categoryID: String) async throws -> String? {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard let json = try Self.setting(context, key: "ai.draft.frame.v1." + draftID + "." + categoryID)?.value(forKey: "value_json") as? String else { return nil }
            return try JSONDecoder().decode(String.self, from: Data(json.utf8))
        }
    }

    func savePartialCategory(draftID: String, categoryID: String, name: String, configuration: AIFrameConfiguration,
        matches: [AIFrameMatch], processedVersions: [String: String], userPaused: Bool,
        manualIncludes: Set<String> = [], manualExcludes: Set<String> = [], complete: Bool = false) async throws -> String {
        try configuration.validate()
        let context = controller.container.newBackgroundContext()
        return try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
            try await context.perform {
                let key = "ai.draft.frame.v1." + draftID + "." + categoryID
                let mapping = try Self.setting(context, key: key)
                let mapped = (mapping?.value(forKey: "value_json") as? String).flatMap { try? JSONDecoder().decode(String.self, from: Data($0.utf8)) }
                let existing = try Self.frame(context, id: configuration.frameID)
                let id = mapped ?? (existing == nil ? UUID().uuidString : configuration.frameID)
                let frame: NSManagedObject
                if let current = try Self.frame(context, id: id) { frame = current }
                else if mapped != nil { throw AIFrameError.staleRun }
                else {
                    let folders = NSFetchRequest<NSManagedObject>(entityName: "Collection")
                    folders.predicate = NSPredicate(format: "id IN %@ AND source.type IN %@", configuration.collectionIDs, ["webdav", "smb"])
                    let remote = try context.count(for: folders) > 0 || matches.contains { $0.assetID.hasPrefix("remote:") }
                    frame = try FrameManager.insertFrame(context: context, frameID: id, name: name,
                        storageMode: remote ? .remote : nil,
                        now: Int(Date().timeIntervalSince1970 * 1000))
                }
                var committed = configuration
                committed.frameID = id
                var state = try Self.read(context: context, frameID: id) ?? AIFrameState(configuration: committed)
                if mapped == nil, existing != nil, state.configuration.revision != configuration.revision { throw AIFrameError.staleRun }
                if mapped != nil, state.configuration.scanFingerprint != committed.scanFingerprint { throw AIFrameError.staleRun }
                if state.configuration.scanFingerprint != committed.scanFingerprint {
                    state.completedContentVersions.removeAll()
                    state.matches.removeAll()
                    committed.revision = state.configuration.revision + 1
                } else { committed.revision = state.configuration.revision }
                state.configuration = committed
                state.runToken = nil
                state.userPaused = userPaused || state.isUserPaused
                state.status = state.isUserPaused ? "userPaused" : "complete"
                try Self.write(state, context: context)
                try Self.applyPreview(context: context, frameID: id, configuration: committed, matches: matches,
                    manualIncludes: manualIncludes, manualExcludes: manualExcludes, processedVersions: processedVersions,
                    userPaused: state.isUserPaused, complete: complete)
                frame.setValue(name, forKey: "name")
                let row = try mapping ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
                try Self.assign(row, key: key, data: JSONEncoder().encode(id))
                try context.save()
                return id
            }
        }
    }

    private static func apply(_ match: AIFrameMatch, asset: NSManagedObject, frame: NSManagedObject,
                              state: inout AIFrameState, context: NSManagedObjectContext) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        request.predicate = NSPredicate(format: "frame == %@ AND ref_key == %@", frame, MediaReference(runtimeID: match.assetID).rawID)
        let existing = try context.fetch(request).first
        let aliases = try RemoteMediaRepository.referenceAliases(context: context, runtimeID: match.assetID)
        let excluded = try RemoteMediaRepository.isExcluded(context: context, frameID: state.configuration.frameID, runtimeID: match.assetID)
        if match.decision == .matched, state.canAutomaticallyInclude(match.assetID),
           state.manualExcludedAssetIDs.isDisjoint(with: aliases), !excluded {
            // Existing explicit includes belong to the user. Explicit excludes always win.
            guard existing == nil else { return }
            let now = Int(Date().timeIntervalSince1970 * 1000)
            let row = NSEntityDescription.insertNewObject(forEntityName: "FrameSelection", into: context)
            row.setValue(frame, forKey: "frame")
            Self.bind(asset, id: match.assetID, selection: row)
            row.setValue("include", forKey: "mode")
            row.setValue(now, forKey: "added_at")
            row.setValue(now, forKey: "created_at")
            row.setValue(now, forKey: "updated_at")
            state.ownedAssetIDs.insert(match.assetID)
            bump(frame)
        } else {
            try removeOwned(match.assetID, frame: frame, state: &state, context: context)
        }
    }

    private static func removeOwned(_ id: String, frame: NSManagedObject, state: inout AIFrameState,
                                    context: NSManagedObjectContext) throws {
        guard state.ownedAssetIDs.remove(id) != nil else { return }
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        request.predicate = NSPredicate(format: "frame == %@ AND ref_key == %@ AND mode == %@", frame, MediaReference(runtimeID: id).rawID, "include")
        for row in try context.fetch(request) { context.delete(row) }
        bump(frame)
    }

    private static func bump(_ frame: NSManagedObject) {
        frame.setValue((frame.value(forKey: "selection_version") as? Int ?? 0) + 1, forKey: "selection_version")
        frame.setValue(Int(Date().timeIntervalSince1970 * 1000), forKey: "updated_at")
    }

    static func manualChange(context: NSManagedObjectContext, frameID: String, includes: Set<String>, removed: Set<String>) throws {
        guard var state = try read(context: context, frameID: frameID) else { return }
        let includeAliases = try includes.reduce(into: Set<String>()) { $0.formUnion(try RemoteMediaRepository.referenceAliases(context: context, runtimeID: $1)) }
        let removedAliases = try removed.reduce(into: Set<String>()) { $0.formUnion(try RemoteMediaRepository.referenceAliases(context: context, runtimeID: $1)) }
        state.recordManualChange(includes: includeAliases, removed: removedAliases)
        try write(state, context: context)
    }

    static func removeState(context: NSManagedObjectContext, frameID: String) throws {
        if let row = try setting(context, key: prefix + frameID) { context.delete(row) }
    }

    static func remapReferences(context: NSManagedObjectContext, frameID: String, mapping: [String: String], removed: Set<String> = []) throws {
        guard !mapping.isEmpty || !removed.isEmpty else { return }
        guard var state = try read(context: context, frameID: frameID) else { return }
        state.ownedAssetIDs = Set(state.ownedAssetIDs.map { mapping[$0] ?? $0 })
        state.manualExcludedAssetIDs = Set(state.manualExcludedAssetIDs.map { mapping[$0] ?? $0 })
        state.ownedAssetIDs.subtract(removed)
        state.manualExcludedAssetIDs.subtract(removed)
        for id in Set(mapping.keys).union(removed) {
            state.completedContentVersions.removeValue(forKey: id)
            state.matches.removeValue(forKey: id)
        }
        state.runToken = nil
        state.status = "idle"
        try write(state, context: context)
    }

    static func removeFeatures(context: NSManagedObjectContext, assetID: String) throws {
        let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        assetRequest.predicate = NSPredicate(format: "id == %@", assetID)
        var referenceIDs: Set<String> = [assetID]
        if let asset = try context.fetch(assetRequest).first {
            for origin in asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [] {
                if let sourceID = origin.value(forKeyPath: "sourceAsset.id") as? String { referenceIDs.insert("remote:" + sourceID) }
            }
            for frame in asset.value(forKey: "coveredFrames") as? Set<NSManagedObject> ?? [] {
                frame.setValue(nil, forKey: "coverAsset")
            }
        }
        if let directory = context.persistentStoreCoordinator?.persistentStores.first?.url?.deletingLastPathComponent() {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent("ai-preview-v1.json"))
        }
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSCompoundPredicate(orPredicateWithSubpredicates: referenceIDs.map { NSPredicate(format: "key BEGINSWITH %@", cachePrefix + $0 + ".") })
        for row in try context.fetch(request) { context.delete(row) }
        for frame in try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")) {
            guard let json = frame.value(forKey: "settings_json") as? String,
                  var settings = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
                  let cover = settings["remoteCoverRef"] as? String, referenceIDs.contains(cover) else { continue }
            settings.removeValue(forKey: "remoteCoverRef")
            frame.setValue(String(decoding: try JSONSerialization.data(withJSONObject: settings), as: UTF8.self), forKey: "settings_json")
        }
        let statesRequest = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        statesRequest.predicate = NSPredicate(format: "key BEGINSWITH %@", prefix)
        for row in try context.fetch(statesRequest) {
            guard let string = row.value(forKey: "value_json") as? String, let data = string.data(using: .utf8) else { continue }
            guard var state = try? AIFrameState.decode(data) else { context.delete(row); continue }
            for id in referenceIDs {
                state.matches.removeValue(forKey: id)
                state.completedContentVersions.removeValue(forKey: id)
                state.ownedAssetIDs.remove(id)
            }
            try assign(row, key: prefix + state.configuration.frameID, data: JSONEncoder().encode(state))
        }
    }

    private static func candidates(_ context: NSManagedObjectContext, configuration: AIFrameConfiguration) throws -> [AIFrameCandidate] {
        let selected = try selectedCollections(context, configuration: configuration)
        guard !selected.isEmpty else { return [] }
        let membership = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
        membership.predicate = NSPredicate(format: "collection.id IN %@", Array(selected))
        let ids = Set(try context.fetch(membership).compactMap { $0.value(forKeyPath: "asset.id") as? String })
        let assetRequest = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        assetRequest.predicate = NSPredicate(format: "id IN %@ AND readiness == %@ AND deleted_at == nil AND privacy_excluded == NO AND media_kind != %@", Array(ids), "ready", "video")
        let origins = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        origins.predicate = NSPredicate(format: "asset.id IN %@ AND policy_state == %@ AND origin_state == %@", Array(ids), "allowed", "active")
        let allowed = Set(try context.fetch(origins).compactMap { $0.value(forKeyPath: "asset.id") as? String })
        var result = try context.fetch(assetRequest).compactMap { asset -> AIFrameCandidate? in
            guard let id = asset.value(forKey: "id") as? String, allowed.contains(id),
                  let hash = asset.value(forKey: "content_hash") as? String,
                  let path = asset.value(forKey: "original_rel_path") as? String else { return nil }
            return AIFrameCandidate(id: id, contentHash: hash, relativePath: path)
        }.sorted { $0.id < $1.id }
        let originals = originalsRoot(context)
        var remote: [String: RemoteMediaRecord] = [:]
        for collectionID in selected {
            for record in try RemoteMediaRepository.records(context: context, originals: originals, collectionID: collectionID, recursive: false)
                where record.remoteAllowed || record.localReady {
                remote[record.descriptor.runtimeID] = record
            }
        }
        var aliases = Set<String>()
        for record in remote.values {
            if let source = try RemoteMediaRepository.fetch(context, entity: "SourceAsset", id: record.descriptor.sourceAssetID) {
                for origin in source.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? [] {
                    if let id = origin.value(forKeyPath: "asset.id") as? String { aliases.insert(id) }
                }
            }
            let hash = SHA256Hex.digest(data: Data(record.descriptor.identity.utf8))
            result.append(AIFrameCandidate(id: record.descriptor.runtimeID, contentHash: hash, relativePath: record.descriptor.runtimeID, remoteDescriptor: record.descriptor, cacheable: record.cacheable))
        }
        return result.filter { !aliases.contains($0.id) }.sorted { $0.id < $1.id }
    }

    private static func isInScope(_ context: NSManagedObjectContext, assetID: String, configuration: AIFrameConfiguration) throws -> Bool {
        if assetID.hasPrefix("remote:") { return try candidates(context, configuration: configuration).contains { $0.id == assetID } }
        let selected = try selectedCollections(context, configuration: configuration)
        guard !selected.isEmpty else { return false }
        let request = NSFetchRequest<NSManagedObject>(entityName: "CollectionAsset")
        request.predicate = NSPredicate(format: "asset.id == %@ AND collection.id IN %@", assetID, Array(selected))
        return try context.count(for: request) > 0
    }

    private static func selectedCollections(_ context: NSManagedObjectContext, configuration: AIFrameConfiguration) throws -> Set<String> {
        let collectionsRequest = NSFetchRequest<NSManagedObject>(entityName: "Collection")
        let collections = try context.fetch(collectionsRequest)
        var children: [String: [String]] = [:]
        let existingIDs = Set(collections.compactMap { $0.value(forKey: "id") as? String })
        for row in collections {
            guard let parent = row.value(forKeyPath: "parent.id") as? String,
                  let id = row.value(forKey: "id") as? String else { continue }
            children[parent, default: []].append(id)
        }
        var selected = Set<String>()
        var queue = configuration.collectionIDs.filter { existingIDs.contains($0) }
        while let id = queue.popLast() {
            guard selected.insert(id).inserted else { continue }
            if configuration.recursive { queue += children[id] ?? [] }
        }
        return selected
    }

    private static func eligibleAsset(_ context: NSManagedObjectContext, id: String) throws -> NSManagedObject? {
        if id.hasPrefix("remote:") {
            guard let row = try RemoteMediaRepository.fetch(context, entity: "SourceAsset", id: MediaReference(runtimeID: id).rawID),
                  let record = try RemoteMediaRepository.record(context: context, row: row, originals: originalsRoot(context)),
                  record.remoteAllowed || record.localReady else { return nil }
            return row
        }
        let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
        request.predicate = NSPredicate(format: "id == %@ AND readiness == %@ AND deleted_at == nil AND privacy_excluded == NO AND media_kind != %@", id, "ready", "video")
        guard let asset = try context.fetch(request).first else { return nil }
        let origins = NSFetchRequest<NSManagedObject>(entityName: "AssetOrigin")
        origins.predicate = NSPredicate(format: "asset == %@ AND policy_state == %@ AND origin_state == %@", asset, "allowed", "active")
        guard try context.count(for: origins) > 0 else { return nil }
        return asset
    }

    private static func originalsRoot(_ context: NSManagedObjectContext) -> URL {
        (context.persistentStoreCoordinator?.persistentStores.first?.url?.deletingLastPathComponent() ?? FileManager.default.temporaryDirectory).appendingPathComponent("originals")
    }

    private static func canCache(context: NSManagedObjectContext, assetID: String) throws -> Bool {
        guard case .sourceAsset(let id) = MediaReference(runtimeID: assetID) else { return true }
        guard let row = try RemoteMediaRepository.fetch(context, entity: "SourceAsset", id: id),
              let record = try RemoteMediaRepository.record(context: context, row: row, originals: originalsRoot(context)) else { return false }
        return record.cacheable
    }

    private static func contentVersion(_ media: NSManagedObject, context: NSManagedObjectContext) throws -> String {
        if media.entity.name == "SourceAsset" {
            guard let record = try RemoteMediaRepository.record(context: context, row: media, originals: originalsRoot(context)) else { throw AIFrameError.staleRun }
            return SHA256Hex.digest(data: Data(record.descriptor.identity.utf8))
        }
        return media.value(forKey: "content_hash") as? String ?? ""
    }

    private static func bind(_ media: NSManagedObject, id: String, selection: NSManagedObject) {
        selection.setValue(id.hasPrefix("remote:") ? nil : media, forKey: "asset")
        selection.setValue(id.hasPrefix("remote:") ? media : nil, forKey: "sourceAsset")
        selection.setValue(MediaReference(runtimeID: id).rawID, forKey: "ref_key")
    }

    private static func trimFeatureCache(_ context: NSManagedObjectContext) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key BEGINSWITH %@", cachePrefix)
        request.sortDescriptors = [NSSortDescriptor(key: "updated_at", ascending: true), NSSortDescriptor(key: "key", ascending: true)]
        let rows = try context.fetch(request)
        var bytes = rows.reduce(0) { $0 + (($1.value(forKey: "value_json") as? String)?.utf8.count ?? 0) }
        for row in rows where bytes > 16_777_216 {
            bytes -= (row.value(forKey: "value_json") as? String)?.utf8.count ?? 0
            context.delete(row)
        }
    }

    private static func cacheKey(assetID: String, version: String) -> String {
        let digest = SHA256.hash(data: Data(version.utf8)).map { String(format: "%02x", $0) }.joined()
        return cachePrefix + assetID + "." + digest
    }

    private static func frame(_ context: NSManagedObjectContext, id: String) throws -> NSManagedObject? {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
        request.predicate = NSPredicate(format: "id == %@", id)
        return try context.fetch(request).first
    }

    private static func setting(_ context: NSManagedObjectContext, key: String) throws -> NSManagedObject? {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", key)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private static func read(context: NSManagedObjectContext, frameID: String) throws -> AIFrameState? {
        guard let row = try setting(context, key: prefix + frameID),
              let value = row.value(forKey: "value_json") as? String,
              let data = value.data(using: .utf8) else { return nil }
        return try AIFrameState.decode(data)
    }

    private static func write(_ state: AIFrameState, context: NSManagedObjectContext) throws {
        let key = prefix + state.configuration.frameID
        let row = try setting(context, key: key) ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
        try assign(row, key: key, data: JSONEncoder().encode(state))
    }

    private static func assign(_ row: NSManagedObject, key: String, data: Data) throws {
        guard let value = String(data: data, encoding: .utf8) else { throw AIFrameError.invalidState }
        let now = Int(Date().timeIntervalSince1970 * 1000)
        row.setValue(key, forKey: "key")
        row.setValue(value, forKey: "value_json")
        if row.value(forKey: "created_at") == nil { row.setValue(now, forKey: "created_at") }
        row.setValue(now, forKey: "updated_at")
    }
}
