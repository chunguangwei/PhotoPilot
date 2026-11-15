import CoreData
import Foundation

struct FrameStorageState: Codable, Sendable {
    var version = 1
    var status = "idle"
    var completed = 0
    var total = 0
    var selectionVersion: Int64 = 0
    var sourceRefs: [String] = []
    var jobIDs: [String] = []
    var error: String?
}

struct RemoteReleaseSummary: Sendable {
    let assetIDs: [String]
    let bytes: Int
    let retained: Int
}

struct RemoteCleanupIntent: Codable, Sendable {
    struct File: Codable, Sendable {
        let assetID: String
        let relativePath: String
        let trashName: String
    }
    let id: String
    var committed: Bool
    let files: [File]
}

actor FrameStorageService {
    private struct Active {
        let id: UUID
        let task: Task<Void, Never>
    }

    let controller: PersistenceController
    private let media: RemoteMediaService
    private let root: URL
    private var erasing = false
    private var active: [String: Active] = [:]
    private var restarting: Set<String> = []
    private var intents: [String: UUID] = [:]
    private var coordinators: [String: [(ImportCoordinator, String)]] = [:]
    private var observer: NSObjectProtocol?
    static let statePrefix = "frame.storage.v1."
    static let cleanupPrefix = "remote.cleanup.v1."

    init(controller: PersistenceController, media: RemoteMediaService) {
        self.controller = controller
        self.media = media
        root = controller.storeURL.deletingLastPathComponent()
    }

    func startObserving() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: .remoteMediaChanged, object: nil, queue: nil) { [weak self] _ in
            Task { await self?.audit() }
        }
    }

    func state(frameID: String) async throws -> FrameStorageState {
        let context = controller.container.newBackgroundContext()
        return try await context.perform {
            guard let json = try Self.setting(context, key: Self.statePrefix + frameID)?.value(forKey: "value_json") as? String else { return FrameStorageState() }
            return (try? JSONDecoder().decode(FrameStorageState.self, from: Data(json.utf8))) ?? FrameStorageState()
        }
    }

    func setMode(frameID: String, mode: FrameStorageMode) async throws {
        let intent = UUID()
        intents[frameID] = intent
        await pause(frameID: frameID)
        guard intents[frameID] == intent else { throw RemoteMediaError.changed }
        try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
            let context = self.controller.container.newBackgroundContext()
            try await context.perform {
                guard let frame = try RemoteMediaRepository.fetch(context, entity: "Frame", id: frameID) else { throw RemoteMediaError.changed }
                let old = frame.value(forKey: "settings_json") as? String ?? "{}"
                if FrameStorageMode.read(old) != mode || !FrameStorageMode.isExplicit(old) {
                    try Self.canonicalizeSelections(context: context, frame: frame, originals: self.root.appendingPathComponent("originals"))
                    frame.setValue(try mode.applying(to: frame.value(forKey: "settings_json") as? String ?? old), forKey: "settings_json")
                    frame.setValue((frame.value(forKey: "selection_version") as? Int ?? 0) + 1, forKey: "selection_version")
                    frame.setValue(Int(Date().timeIntervalSince1970 * 1000), forKey: "updated_at")
                    _ = try PersistenceController.bumpLibraryVersionValue(in: context)
                    try context.save()
                }
            }
        }
        guard intents[frameID] == intent else { return }
        var progress = try await state(frameID: frameID)
        progress.status = "idle"
        progress.error = nil
        try await saveState(progress, frameID: frameID)
        if mode == .local { start(frameID: frameID) }
    }

    private static func canonicalizeSelections(context: NSManagedObjectContext, frame: NSManagedObject, originals: URL) throws {
        let request = NSFetchRequest<NSManagedObject>(entityName: "FrameSelection")
        request.predicate = NSPredicate(format: "frame == %@", frame)
        let selections = try context.fetch(request)
        var mapped: [String: String] = [:]
        for selection in selections {
            guard let asset = selection.value(forKey: "asset") as? NSManagedObject,
                  let assetID = asset.value(forKey: "id") as? String else { continue }
            guard let sourceAsset = try canonicalSource(context: context, asset: asset, originals: originals),
                  let sourceID = sourceAsset.value(forKey: "id") as? String else { continue }
            mapped[assetID] = "remote:" + sourceID
            if let existing = selections.first(where: { $0 != selection && !$0.isDeleted && $0.value(forKeyPath: "sourceAsset.id") as? String == sourceID }) {
                if selection.value(forKey: "mode") as? String == "exclude" { existing.setValue("exclude", forKey: "mode") }
                context.delete(selection)
            } else {
                selection.setValue(nil, forKey: "asset")
                selection.setValue(sourceAsset, forKey: "sourceAsset")
                selection.setValue(sourceID, forKey: "ref_key")
            }
        }
        if let cover = frame.value(forKey: "coverAsset") as? NSManagedObject,
           let source = try canonicalSource(context: context, asset: cover, originals: originals),
           let sourceID = source.value(forKey: "id") as? String {
            let raw = frame.value(forKey: "settings_json") as? String ?? "{}"
            var settings = (try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any]) ?? [:]
            settings["remoteCoverRef"] = "remote:" + sourceID
            frame.setValue(String(decoding: try JSONSerialization.data(withJSONObject: settings), as: UTF8.self), forKey: "settings_json")
            frame.setValue(nil, forKey: "coverAsset")
        }
        if let frameID = frame.value(forKey: "id") as? String { try AIFrameStore.remapReferences(context: context, frameID: frameID, mapping: mapped) }
    }

    private static func canonicalSource(context: NSManagedObjectContext, asset: NSManagedObject, originals: URL) throws -> NSManagedObject? {
        let origins = asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
        guard !origins.isEmpty, origins.allSatisfy({ ["webdav", "smb"].contains($0.value(forKeyPath: "sourceAsset.source.type") as? String ?? "") && $0.value(forKey: "policy_state") as? String == "allowed" }) else { return nil }
        let sources = origins.compactMap { $0.value(forKey: "sourceAsset") as? NSManagedObject }
            .sorted { ($0.value(forKey: "id") as? String ?? "") < ($1.value(forKey: "id") as? String ?? "") }
        return try sources.first { row in
            guard let record = try RemoteMediaRepository.record(context: context, row: row, originals: originals) else { return false }
            return record.remoteAllowed || record.localReady
        }
    }

    func start(frameID: String) {
        guard !erasing, active[frameID] == nil else { return }
        let id = UUID()
        let task = Task {
            do { try await synchronize(frameID: frameID) }
            catch {
                var progress = (try? await state(frameID: frameID)) ?? FrameStorageState()
                progress.status = error is CancellationError ? "paused" : "failed"
                progress.error = error is CancellationError ? nil : error.localizedDescription
                try? await saveState(progress, frameID: frameID, expectedVersion: progress.selectionVersion)
            }
            if active[frameID]?.id == id {
                active[frameID] = nil
                if restarting.remove(frameID) != nil { start(frameID: frameID) }
            }
        }
        active[frameID] = Active(id: id, task: task)
    }

    func wait(frameID: String) async { await active[frameID]?.task.value }

    func pause(frameID: String) async {
        restarting.remove(frameID)
        let task = active[frameID]?.task
        task?.cancel()
        await task?.value
        for (coordinator, jobID) in coordinators[frameID] ?? [] {
            _ = try? await coordinator.handleSignal(jobID: jobID, signal: .userPause)
        }
        coordinators[frameID] = nil
    }

    func stopAll() async {
        erasing = true
        for id in Array(active.keys) { await pause(frameID: id) }
    }

    func resumePending() async {
        let context = controller.container.newBackgroundContext()
        let frames = (try? await context.perform { () -> [String] in
            try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")).compactMap { frame in
                let json = frame.value(forKey: "settings_json") as? String ?? "{}"
                return FrameStorageMode.isExplicit(json) && FrameStorageMode.read(json) == .local ? frame.value(forKey: "id") as? String : nil
            }
        }) ?? []
        for id in frames {
            if let progress = try? await state(frameID: id), progress.status == "paused" { continue }
            start(frameID: id)
        }
    }

    private func audit() async {
        for id in Array(active.keys) {
            guard let bundle = try? await FrameManager(controller: controller).framePlaybackBundle(frameID: id) else {
                active[id]?.task.cancel()
                continue
            }
            let progress = try? await state(frameID: id)
            let changed = progress?.status == "running" && bundle.selectionVersion != progress?.selectionVersion
            if FrameStorageMode.read(bundle.settingsJSON) != .local || changed {
                if FrameStorageMode.read(bundle.settingsJSON) == .local { restarting.insert(id) }
                active[id]?.task.cancel()
            }
        }
        await resumePending()
    }

    private func synchronize(frameID: String) async throws {
        let bundle = try await FrameManager(controller: controller).framePlaybackBundle(frameID: frameID)
        guard FrameStorageMode.read(bundle.settingsJSON) == .local else { throw RemoteMediaError.changed }
        let references = try await FrameRepository(controller: controller).resolve(frameID: frameID).candidates
        let records = try await media.repository.records()
        let selected = records.filter { !($0.referenceOnly) && references.contains($0.descriptor.runtimeID) }
        var progress = FrameStorageState()
        progress.status = "running"
        progress.total = selected.count
        progress.selectionVersion = bundle.selectionVersion
        progress.sourceRefs = selected.map { $0.descriptor.runtimeID }
        progress.completed = selected.filter(\.localReady).count
        try await saveState(progress, frameID: frameID, expectedVersion: bundle.selectionVersion)
        let needed = selected.filter { !$0.localReady }
        for (sourceID, items) in Dictionary(grouping: needed, by: { $0.descriptor.sourceID }).sorted(by: { $0.key < $1.key }) {
            try Task.checkCancellation()
            guard items.allSatisfy(\.remoteAllowed) else { throw RemoteMediaError.unavailable }
            let adapter = try await media.adapter(sourceID: sourceID)
            let payloadRoot = root.appendingPathComponent("staging/remote-frame/" + UUID().uuidString)
            let scoped = ScopedRemoteImportAdapter(descriptors: items.map(\.descriptor), underlying: adapter, media: media,
                directory: payloadRoot, frameID: frameID, selectionVersion: bundle.selectionVersion)
            let scope: [String: Any] = ["frameID": frameID, "selectionVersion": Int(bundle.selectionVersion),
                "remoteIDs": items.map { $0.descriptor.sourceAssetID },
                "revisions": Dictionary(uniqueKeysWithValues: items.map { ($0.descriptor.sourceAssetID, $0.descriptor.revision) })]
            let scopeJSON = String(decoding: try JSONSerialization.data(withJSONObject: scope, options: [.sortedKeys]), as: UTF8.self)
            let coordinator = ImportCoordinator(controller: controller, adapter: scoped)
            let job = try await coordinator.createJob(sourceID: sourceID, scopeJSON: scopeJSON)
            coordinators[frameID, default: []].append((coordinator, job.id))
            progress.jobIDs.append(job.id)
            try await saveState(progress, frameID: frameID, expectedVersion: bundle.selectionVersion)
            do {
                _ = try await coordinator.enqueueItems(jobID: job.id)
                while let itemID = try await coordinator.nextQueuedItemID(jobID: job.id) {
                    try Task.checkCancellation()
                    try await coordinator.stageItem(itemID: itemID)
                    try Task.checkCancellation()
                    try await coordinator.verifyItem(itemID: itemID)
                    try Task.checkCancellation()
                    let outcome = try await coordinator.commitItem(itemID: itemID)
                    switch outcome {
                    case .imported, .merged: progress.completed += 1
                    default: throw RemoteMediaError.changed
                    }
                    try await saveState(progress, frameID: frameID, expectedVersion: bundle.selectionVersion)
                    await scoped.clean()
                }
                await scoped.clean()
            } catch {
                _ = try? await coordinator.handleSignal(jobID: job.id, signal: error is CancellationError ? .userPause : .fatalError)
                await scoped.clean()
                throw error
            }
        }
        progress.status = "complete"
        try await saveState(progress, frameID: frameID, expectedVersion: bundle.selectionVersion)
        coordinators[frameID] = nil
    }

    func releasable(frameID: String) async throws -> RemoteReleaseSummary {
        let candidates = try await releaseCandidates(frameID: frameID)
        return RemoteReleaseSummary(assetIDs: candidates.files.map(\.assetID), bytes: candidates.bytes, retained: candidates.retained)
    }

    private struct ReleaseCandidates: Sendable {
        let files: [RemoteCleanupIntent.File]
        let bytes: Int
        let retained: Int
    }

    private func releaseCandidates(frameID: String) async throws -> ReleaseCandidates {
        let manager = FrameManager(controller: controller)
        let bundle = try await manager.framePlaybackBundle(frameID: frameID)
        guard FrameStorageMode.read(bundle.settingsJSON) == .remote else { return ReleaseCandidates(files: [], bytes: 0, retained: 0) }
        let current = try await FrameRepository(controller: controller).resolve(frameID: frameID)
        let progress = try await state(frameID: frameID)
        let wanted = Set(current.candidates + progress.sourceRefs)
        let context = controller.container.newBackgroundContext()
        let otherFrames = try await context.perform { () -> [(String, String, String?)] in
            try context.fetch(NSFetchRequest<NSManagedObject>(entityName: "Frame")).compactMap { row in
                guard let id = row.value(forKey: "id") as? String, id != frameID else { return nil }
                return (id, row.value(forKey: "settings_json") as? String ?? "{}", row.value(forKeyPath: "coverAsset.id") as? String)
            }
        }
        var protected: Set<String> = []
        for (id, json, cover) in otherFrames where FrameStorageMode.read(json) == .local {
            let references = try await FrameRepository(controller: controller).resolve(frameID: id)
            protected.formUnion(references.allowed)
            if let cover { protected.insert(cover) }
            if let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any], let cover = object["remoteCoverRef"] as? String { protected.insert(cover) }
        }
        let protectedRefs = protected
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Asset")
            request.predicate = NSPredicate(format: "readiness == %@ AND privacy_excluded == NO AND deleted_at == nil", "ready")
            var files: [RemoteCleanupIntent.File] = []
            var bytes = 0
            var retained = 0
            for asset in try context.fetch(request) {
                guard let id = asset.value(forKey: "id") as? String,
                      let relative = asset.value(forKey: "original_rel_path") as? String else { continue }
                let origins = asset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                let sourceRefs = Set(origins.compactMap { $0.value(forKeyPath: "sourceAsset.id") as? String }.map { "remote:" + $0 })
                guard wanted.contains(id) || !wanted.isDisjoint(with: sourceRefs) else { continue }
                var eligible = !origins.isEmpty && !protectedRefs.contains(id) && protectedRefs.isDisjoint(with: sourceRefs)
                for origin in origins {
                    guard let sourceAsset = origin.value(forKey: "sourceAsset") as? NSManagedObject,
                          let source = sourceAsset.value(forKey: "source") as? NSManagedObject,
                          ["webdav", "smb"].contains(source.value(forKey: "type") as? String ?? ""),
                          let sourceID = sourceAsset.value(forKey: "id") as? String,
                          let record = try RemoteMediaRepository.record(context: context, row: sourceAsset, originals: self.root.appendingPathComponent("originals")),
                          record.remoteAllowed,
                          let provenance = try RemoteMediaRepository.provenance(context: context, assetID: id, sourceAssetID: sourceID),
                          provenance.frameScoped,
                          provenance.sourceEpoch == record.descriptor.sourceEpoch, provenance.importedRevision == record.descriptor.revision,
                          provenance.contentHash == asset.value(forKey: "content_hash") as? String else { eligible = false; continue }
                }
                let file = self.root.appendingPathComponent("originals").appendingPathComponent(relative)
                guard eligible, let size = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.intValue else {
                    retained += 1
                    continue
                }
                files.append(RemoteCleanupIntent.File(assetID: id, relativePath: relative, trashName: UUID().uuidString))
                bytes += size
            }
            return ReleaseCandidates(files: files, bytes: bytes, retained: retained)
        }
    }

    func releaseCopies(frameID: String, verifyLegacy: Bool, expectedVersion: Int64? = nil) async throws {
        await pause(frameID: frameID)
        if verifyLegacy { try await verifyLegacyCopies(frameID: frameID) }
        try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
            if let expectedVersion {
                let bundle = try await FrameManager(controller: self.controller).framePlaybackBundle(frameID: frameID)
                guard bundle.selectionVersion == expectedVersion else { throw RemoteMediaError.changed }
            }
            try await self.releaseSerialized(frameID: frameID)
        }
        await media.clearCache()
    }

    private func releaseSerialized(frameID: String) async throws {
        let candidates = try await releaseCandidates(frameID: frameID)
        guard !candidates.files.isEmpty else { return }
        var intent = RemoteCleanupIntent(id: UUID().uuidString, committed: false, files: candidates.files)
        let context = controller.container.newBackgroundContext()
        let key = Self.cleanupPrefix + intent.id
        let prepared = intent
        try await context.perform {
            try Self.write(context, key: key, value: prepared)
            try context.save()
        }
        let trash = root.appendingPathComponent("remote-trash/" + intent.id)
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        do {
            for file in intent.files {
                let original = root.appendingPathComponent("originals").appendingPathComponent(file.relativePath)
                try FileManager.default.moveItem(at: original, to: trash.appendingPathComponent(file.trashName))
            }
            intent.committed = true
            let committed = intent
            try await context.perform {
                for file in committed.files {
                    guard let asset = try RemoteMediaRepository.fetch(context, entity: "Asset", id: file.assetID) else { throw RemoteMediaError.changed }
                    asset.setValue("pending", forKey: "readiness")
                    asset.setValue(Int(Date().timeIntervalSince1970 * 1000), forKey: "updated_at")
                }
                try Self.write(context, key: key, value: committed)
                _ = try PersistenceController.bumpLibraryVersionValue(in: context)
                try context.save()
            }
            try FileManager.default.removeItem(at: trash)
            try await context.perform {
                if let row = try Self.setting(context, key: key) { context.delete(row) }
                try context.save()
            }
        } catch {
            await context.perform { context.rollback() }
            try await recoverCleanup()
            throw error
        }
    }

    func recoverCleanup() async throws {
        let context = controller.container.newBackgroundContext()
        let values = try await context.perform { () -> [(String, RemoteCleanupIntent)] in
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key BEGINSWITH %@", Self.cleanupPrefix)
            return try context.fetch(request).compactMap { row in
                guard let key = row.value(forKey: "key") as? String, let json = row.value(forKey: "value_json") as? String,
                      let value = try? JSONDecoder().decode(RemoteCleanupIntent.self, from: Data(json.utf8)) else { return nil }
                return (key, value)
            }
        }
        for (key, intent) in values {
            let trash = root.appendingPathComponent("remote-trash/" + intent.id)
            if !intent.committed {
                for file in intent.files {
                    let original = root.appendingPathComponent("originals").appendingPathComponent(file.relativePath)
                    let removed = trash.appendingPathComponent(file.trashName)
                    if !FileManager.default.fileExists(atPath: original.path), FileManager.default.fileExists(atPath: removed.path) {
                        try FileManager.default.createDirectory(at: original.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try FileManager.default.moveItem(at: removed, to: original)
                    }
                }
            }
            if FileManager.default.fileExists(atPath: trash.path) { try FileManager.default.removeItem(at: trash) }
            try await context.perform {
                if let row = try Self.setting(context, key: key) { context.delete(row) }
                try context.save()
            }
        }
    }

    private func verifyLegacyCopies(frameID: String) async throws {
        let resolution = try await FrameRepository(controller: controller).resolve(frameID: frameID)
        let refs = Set(resolution.candidates + ((try? await state(frameID: frameID).sourceRefs) ?? []))
        for record in try await media.repository.records() where !record.referenceOnly && refs.contains(record.descriptor.runtimeID) && record.remoteAllowed {
            let lease = try await media.acquire(runtimeID: record.descriptor.runtimeID)
            do {
                let hash = try AssetFileProber.contentHash(of: lease.fileURL)
                await lease.release()
                let context = controller.container.newBackgroundContext()
                try await LibraryStorageGate.shared(storeURL: controller.storeURL).perform {
                    try await context.perform {
                        guard let sourceAsset = try RemoteMediaRepository.fetch(context, entity: "SourceAsset", id: record.descriptor.sourceAssetID),
                              let current = try RemoteMediaRepository.record(context: context, row: sourceAsset, originals: self.root.appendingPathComponent("originals")),
                              current.descriptor == record.descriptor, current.remoteAllowed else { throw RemoteMediaError.changed }
                        let origins = sourceAsset.value(forKey: "assetOrigins") as? Set<NSManagedObject> ?? []
                        for origin in origins {
                            guard let asset = origin.value(forKey: "asset") as? NSManagedObject,
                                  asset.value(forKey: "content_hash") as? String == hash,
                                  let assetID = asset.value(forKey: "id") as? String,
                                  try RemoteMediaRepository.provenance(context: context, assetID: assetID, sourceAssetID: record.descriptor.sourceAssetID) == nil else { continue }
                            let raw = sourceAsset.value(forKeyPath: "source.settings_json") as? String ?? "{}"
                            let settings = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any]
                            guard settings?["catalogOnly"] as? Bool == true else { continue }
                            try RemoteMediaRepository.writeProvenance(context: context, asset: asset, sourceAsset: sourceAsset, frameScoped: true)
                        }
                        try context.save()
                    }
                }
            } catch {
                await lease.release()
                throw error
            }
        }
    }

    private func saveState(_ state: FrameStorageState, frameID: String, expectedVersion: Int64? = nil) async throws {
        let context = controller.container.newBackgroundContext()
        try await context.perform {
            guard let frame = try RemoteMediaRepository.fetch(context, entity: "Frame", id: frameID) else { return }
            if let expectedVersion, frame.value(forKey: "selection_version") as? Int != Int(expectedVersion) { throw RemoteMediaError.changed }
            try Self.write(context, key: Self.statePrefix + frameID, value: state)
            try context.save()
        }
    }

    private static func setting(_ context: NSManagedObjectContext, key: String) throws -> NSManagedObject? {
        let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
        request.predicate = NSPredicate(format: "key == %@", key)
        return try context.fetch(request).first
    }

    private static func write<T: Encodable>(_ context: NSManagedObjectContext, key: String, value: T) throws {
        let row = try setting(context, key: key) ?? NSEntityDescription.insertNewObject(forEntityName: "SettingsKV", into: context)
        row.setValue(key, forKey: "key")
        row.setValue(String(decoding: try JSONEncoder().encode(value), as: UTF8.self), forKey: "value_json")
        let now = Int(Date().timeIntervalSince1970 * 1000)
        if row.value(forKey: "created_at") == nil { row.setValue(now, forKey: "created_at") }
        row.setValue(now, forKey: "updated_at")
    }
}
