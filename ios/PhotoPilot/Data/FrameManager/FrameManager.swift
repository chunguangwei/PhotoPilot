import CoreData
import Foundation

struct FrameCardDTO: Sendable {
    let id: String
    let name: String
    let coverRelPath: String?
    let playableCount: Int
    let pendingCount: Int
    let updatedAt: Int
    var storageMode: FrameStorageMode = .local
}

struct FrameDetailDTO: Sendable {
    let id: String
    let name: String
    let orderMode: String
    let selectionVersion: Int64
    let includeAssetRefs: [String]
    let includeSourceAssetRefs: [String]
    let excludeRefs: [String]
    let rules: [SaveRuleRow]
    let manualOrderedRefs: [String]
    let newRefs: [String]
    let coverAssetId: String?
}

struct PlaybackSnapshotDTO: Sendable {
    let remainingHoldMs: Int
    let cursorJSON: String
    let seed: Int
    let selectionVersion: Int
}

struct FramePlaybackBundle: Sendable {
    let settingsJSON: String
    let randomSeed: Int
    let selectionVersion: Int64
    let name: String
    let orderMode: String
}

actor FrameManager {
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

    func createFrame(name: String, storageMode: FrameStorageMode? = nil) async throws -> String {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let frame = NSEntityDescription.insertNewObject(forEntityName: "Frame", into: context)
            let frameID = UUID().uuidString
            frame.setValue(frameID, forKey: "id")
            frame.setValue(name, forKey: "name")
            frame.setValue(try storageMode?.applying(to: Self.defaultSettingsJSON()) ?? Self.defaultSettingsJSON(), forKey: "settings_json")
            frame.setValue(NSNumber(value: 0), forKey: "selection_version")
            frame.setValue(PlaybackQueue.orderCapturedAsc, forKey: "order_mode")
            frame.setValue(NSNumber(value: Int64.random(in: Int64.min...Int64.max)), forKey: "random_seed")
            frame.setValue(now, forKey: "created_at")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
            return frameID
        }
    }

    func renameFrame(frameID: String, name: String) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            frame.setValue(name, forKey: "name")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func duplicateFrame(frameID: String) async throws -> String {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let source = try Self.fetchFrame(context: context, frameID: frameID)
            let frame = NSEntityDescription.insertNewObject(forEntityName: "Frame", into: context)
            let newID = UUID().uuidString
            frame.setValue(newID, forKey: "id")
            let sourceName = source.value(forKey: "name") as? String ?? ""
            frame.setValue(sourceName, forKey: "name")
            frame.setValue(source.value(forKey: "settings_json"), forKey: "settings_json")
            frame.setValue(NSNumber(value: 0), forKey: "selection_version")
            frame.setValue(source.value(forKey: "order_mode"), forKey: "order_mode")
            frame.setValue(source.value(forKey: "random_seed"), forKey: "random_seed")
            frame.setValue(source.value(forKey: "coverAsset"), forKey: "coverAsset")
            frame.setValue(now, forKey: "created_at")
            frame.setValue(now, forKey: "updated_at")

            let selections = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            selections.predicate = NSPredicate(format: "frame.id == %@", frameID)
            for row in try context.fetch(selections) {
                let copy = NSEntityDescription.insertNewObject(forEntityName: "FrameSelection", into: context)
                copy.setValue(frame, forKey: "frame")
                copy.setValue(row.value(forKey: "ref_key"), forKey: "ref_key")
                copy.setValue(row.value(forKey: "mode"), forKey: "mode")
                copy.setValue(row.value(forKey: "manual_order"), forKey: "manual_order")
                copy.setValue(row.value(forKey: "added_at"), forKey: "added_at")
                copy.setValue(row.value(forKey: "asset"), forKey: "asset")
                copy.setValue(row.value(forKey: "sourceAsset"), forKey: "sourceAsset")
                copy.setValue(now, forKey: "created_at")
                copy.setValue(now, forKey: "updated_at")
            }

            let rules = NSFetchRequest<NSManagedObject>(entityName: "FrameDirectoryRule")
            rules.predicate = NSPredicate(format: "frame.id == %@", frameID)
            for row in try context.fetch(rules) {
                let copy = NSEntityDescription.insertNewObject(
                    forEntityName: "FrameDirectoryRule",
                    into: context
                )
                copy.setValue(frame, forKey: "frame")
                copy.setValue(row.value(forKey: "collection"), forKey: "collection")
                copy.setValue(row.value(forKey: "recursive"), forKey: "recursive")
                copy.setValue(row.value(forKey: "include_future"), forKey: "include_future")
                copy.setValue(now, forKey: "created_at")
                copy.setValue(now, forKey: "updated_at")
            }

            try context.save()
            return newID
        }
    }

    func deleteFrame(frameID: String) async throws {
        let context = makeContext()
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            try AIFrameStore.removeState(context: context, frameID: frameID)
            context.delete(frame)
            try context.save()
        }
    }

    func frameCards() async throws -> [FrameCardDTO] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
            let rows = try context.fetch(request)
            return rows.map {
                FrameCardDTO(
                    id: $0.value(forKey: "id") as? String ?? "",
                    name: $0.value(forKey: "name") as? String ?? "",
                    coverRelPath: Self.remoteCover($0.value(forKey: "settings_json") as? String ?? "{}") ?? ($0.value(forKeyPath: "coverAsset.original_rel_path") as? String),
                    playableCount: 0,
                    pendingCount: 0,
                    updatedAt: $0.value(forKey: "updated_at") as? Int ?? 0,
                    storageMode: FrameStorageMode.read($0.value(forKey: "settings_json") as? String ?? "{}")
                )
            }
            .sorted { $0.updatedAt > $1.updatedAt }
        }
    }

    func frameCounts(frameID: String) async throws -> (playable: Int, pending: Int) {
        let resolution = try await FrameRepository(controller: controller).resolve(frameID: frameID)
        return (resolution.playable.count, resolution.pending.count)
    }

    func queueAssetRelPaths(frameID: String, limit: Int) async throws -> [String] {
        let context = makeContext()
        return try await context.perform {
            let selections = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            selections.predicate = NSPredicate(
                format: "frame.id == %@ AND mode == %@",
                frameID,
                SelectionResolver.modeInclude
            )
            selections.sortDescriptors = [NSSortDescriptor(key: "added_at", ascending: true)]
            let rows = try context.fetch(selections)
            var paths: [String] = []
            for row in rows {
                if let path = row.value(forKeyPath: "asset.original_rel_path") as? String {
                    paths.append(path)
                    if paths.count >= limit { break }
                }
            }
            return paths
        }
    }

    func frameDetail(frameID: String) async throws -> FrameDetailDTO {
        let context = makeContext()
        return try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            let selections = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
            selections.predicate = NSPredicate(format: "frame.id == %@", frameID)
            var includeAssets: [String] = []
            var includeSourceAssets: [String] = []
            var excludes: [String] = []
            var manualRows: [(ref: String, order: Int, addedAt: Int)] = []
            var newRefs: [String] = []
            for row in try context.fetch(selections) {
                let rawRef = row.value(forKey: "ref_key") as? String ?? ""
                let ref = row.value(forKey: "sourceAsset") != nil ? "remote:" + rawRef : rawRef
                let mode = row.value(forKey: "mode") as? String
                if mode == SelectionResolver.modeExclude {
                    excludes.append(ref)
                    continue
                }
                if let assetID = row.value(forKeyPath: "asset.id") as? String {
                    includeAssets.append(assetID)
                } else if let sourceAssetID = row.value(forKeyPath: "sourceAsset.id") as? String {
                    includeSourceAssets.append(sourceAssetID)
                }
                let addedAt = row.value(forKey: "added_at") as? Int ?? 0
                if let order = row.value(forKey: "manual_order") as? Int {
                    manualRows.append((ref, order, addedAt))
                } else {
                    newRefs.append(ref)
                }
            }
            let rulesRequest = NSFetchRequest<NSManagedObject>(entityName: "FrameDirectoryRule")
            rulesRequest.predicate = NSPredicate(format: "frame.id == %@", frameID)
            let rules = try context.fetch(rulesRequest).map {
                SaveRuleRow(
                    collectionId: $0.value(forKeyPath: "collection.id") as? String ?? "",
                    recursive: $0.value(forKey: "recursive") as? Bool ?? false,
                    includeFuture: $0.value(forKey: "include_future") as? Bool ?? false
                )
            }
            let ordered = manualRows.sorted {
                if $0.order != $1.order { return $0.order < $1.order }
                if $0.addedAt != $1.addedAt { return $0.addedAt < $1.addedAt }
                return $0.ref < $1.ref
            }
            return FrameDetailDTO(
                id: frameID,
                name: frame.value(forKey: "name") as? String ?? "",
                orderMode: frame.value(forKey: "order_mode") as? String ?? PlaybackQueue.orderCapturedAsc,
                selectionVersion: Int64(frame.value(forKey: "selection_version") as? Int ?? 0),
                includeAssetRefs: includeAssets.sorted(),
                includeSourceAssetRefs: includeSourceAssets.sorted(),
                excludeRefs: excludes.sorted(),
                rules: rules,
                manualOrderedRefs: ordered.map(\.ref),
                newRefs: newRefs.sorted(),
                coverAssetId: Self.remoteCover(frame.value(forKey: "settings_json") as? String ?? "{}") ?? (frame.value(forKeyPath: "coverAsset.id") as? String)
            )
        }
    }

    func frameExists(frameID: String) async throws -> Bool {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
            request.predicate = NSPredicate(format: "id == %@", frameID)
            request.fetchLimit = 1
            return try context.count(for: request) > 0
        }
    }

    func updateSettingsJSON(frameID: String, settingsJSON: String, preservingMediaSettings: Bool = false) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            let previous = frame.value(forKey: "settings_json") as? String ?? "{}"
            var updated = settingsJSON
            if preservingMediaSettings {
                var current = (try JSONSerialization.jsonObject(with: Data(previous.utf8)) as? [String: Any]) ?? [:]
                let incoming = (try JSONSerialization.jsonObject(with: Data(settingsJSON.utf8)) as? [String: Any]) ?? [:]
                for key in ["holdSeconds", "transition", "transitionSeconds", "fitMode", "solidColor", "clock", "date", "keepAwakeWhenPaused", "rest", "brightness", "qualityLevel", "music"] {
                    current[key] = incoming[key]
                }
                updated = String(decoding: try JSONSerialization.data(withJSONObject: current), as: UTF8.self)
            }
            if FrameStorageMode.read(previous) != FrameStorageMode.read(updated)
                || FrameStorageMode.isExplicit(previous) != FrameStorageMode.isExplicit(updated) {
                frame.setValue((frame.value(forKey: "selection_version") as? Int ?? 0) + 1, forKey: "selection_version")
                _ = try PersistenceController.bumpLibraryVersionValue(in: context)
            }
            frame.setValue(updated, forKey: "settings_json")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
        }
        }
    }

    func updateOrderMode(frameID: String, orderMode: String) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            frame.setValue(orderMode, forKey: "order_mode")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func setCover(frameID: String, assetID: String) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            var settings = (try? JSONSerialization.jsonObject(with: Data((frame.value(forKey: "settings_json") as? String ?? "{}").utf8)) as? [String: Any]) ?? [:]
            switch MediaReference(runtimeID: assetID) {
            case .sourceAsset(let id):
                guard let source = try RemoteMediaRepository.fetch(context, entity: "SourceAsset", id: id),
                      let record = try RemoteMediaRepository.record(context: context, row: source,
                        originals: self.controller.storeURL.deletingLastPathComponent().appendingPathComponent("originals")),
                      record.remoteAllowed || record.localReady else { throw RemoteMediaError.revoked }
                settings["remoteCoverRef"] = assetID
                frame.setValue(nil, forKey: "coverAsset")
            case .asset(let id):
                guard let asset = try RemoteMediaRepository.fetch(context, entity: "Asset", id: id),
                      asset.value(forKey: "privacy_excluded") as? Bool != true,
                      asset.value(forKey: "deleted_at") == nil else { throw RemoteMediaError.revoked }
                settings.removeValue(forKey: "remoteCoverRef")
                frame.setValue(asset, forKey: "coverAsset")
            }
            frame.setValue(String(decoding: try JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys]), as: UTF8.self), forKey: "settings_json")
            frame.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func framePlaybackBundle(frameID: String) async throws -> FramePlaybackBundle {
        let context = makeContext()
        return try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            return FramePlaybackBundle(
                settingsJSON: frame.value(forKey: "settings_json") as? String ?? "{}",
                randomSeed: frame.value(forKey: "random_seed") as? Int ?? 0,
                selectionVersion: Int64(frame.value(forKey: "selection_version") as? Int ?? 0),
                name: frame.value(forKey: "name") as? String ?? "",
                orderMode: frame.value(forKey: "order_mode") as? String ?? PlaybackQueue.orderCapturedAsc
            )
        }
    }

    func savePlaybackSnapshot(
        frameID: String,
        remainingHoldMs: Int,
        cursorJSON: String,
        seed: Int,
        selectionVersion: Int
    ) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let frame = try Self.fetchFrame(context: context, frameID: frameID)
            let request = NSFetchRequest<NSManagedObject>(entityName: "PlaybackSnapshot")
            request.predicate = NSPredicate(format: "frame.id == %@", frameID)
            request.fetchLimit = 1
            let row = try context.fetch(request).first
                ?? NSEntityDescription.insertNewObject(forEntityName: "PlaybackSnapshot", into: context)
            row.setValue(frame, forKey: "frame")
            row.setValue(NSNumber(value: remainingHoldMs), forKey: "remaining_hold_ms")
            row.setValue(cursorJSON, forKey: "cursor_json")
            row.setValue(NSNumber(value: seed), forKey: "seed")
            row.setValue(NSNumber(value: selectionVersion), forKey: "selection_version")
            row.setValue(NSNumber(value: now), forKey: "saved_at")
            if row.value(forKey: "created_at") == nil {
                row.setValue(NSNumber(value: now), forKey: "created_at")
            }
            row.setValue(NSNumber(value: now), forKey: "updated_at")
            try context.save()
        }
    }

    func playbackSnapshot(frameID: String) async throws -> PlaybackSnapshotDTO? {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "PlaybackSnapshot")
            request.predicate = NSPredicate(format: "frame.id == %@", frameID)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return nil }
            return PlaybackSnapshotDTO(
                remainingHoldMs: row.value(forKey: "remaining_hold_ms") as? Int ?? 0,
                cursorJSON: row.value(forKey: "cursor_json") as? String ?? "{}",
                seed: row.value(forKey: "seed") as? Int ?? 0,
                selectionVersion: row.value(forKey: "selection_version") as? Int ?? 0
            )
        }
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

    private static func remoteCover(_ json: String) -> String? {
        let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        guard let ref = object?["remoteCoverRef"] as? String, ref.hasPrefix("remote:") else { return nil }
        return ref
    }

    static func defaultSettingsJSON() -> String {
        let settings: [String: Any] = [
            "holdSeconds": 10,
            "transition": "random",
            "transitionSeconds": 0.8,
            "fitMode": "fit_blur",
            "solidColor": NSNull(),
            "clock": ["enabled": true, "position": "bottom_leading", "hour24": NSNull()],
            "date": ["enabled": true],
            "weather": ["enabled": false, "placeId": NSNull(), "units": NSNull()],
            "keepAwakeWhenPaused": true,
            "rest": ["enabled": false, "startMinute": NSNull(), "endMinute": NSNull(), "display": "clock"],
            "brightness": NSNull(),
            "qualityLevel": "auto",
            "music": [
                "enabled": false,
                "source": "builtin",
                "trackId": NSNull(),
                "localPath": NSNull(),
                "localTitle": NSNull(),
            ],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys]) else {
            return "{}"
        }
        return String(decoding: data, as: UTF8.self)
    }
}
