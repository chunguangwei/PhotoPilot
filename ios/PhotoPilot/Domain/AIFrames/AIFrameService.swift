import Foundation
import UIKit
import os
import Darwin

struct AIFrameScanProgress: Sendable {
    let completed: Int
    let total: Int
    let matched: Int
    let review: Int
    let failed: Int
}

actor AIFrameService {
    let store: AIFrameStore
    let models: AIModelManager?
    let drafts: AIScanDraftStore
    private var draftTask: Task<Void, Never>?
    private var activeDraftID: String?
    private var draftStartIntent: UUID?
    private var draftValidations: [String: (libraryVersion: Int, updatedAt: Date)] = [:]
    private var explicitStarts: Set<String> = []
    private let root: URL
    private let media: RemoteMediaService
    private var jobs: [String: Task<Void, Never>] = [:]
    private var erasing = false
    private var memoryWarning = false
    private var queuedFrameIDs: [String] = []
    private var previewTask: Task<[AIFrameMatch], Error>?

    init(controller: PersistenceController, media: RemoteMediaService? = nil) {
        drafts = AIScanDraftStore(root: controller.storeURL.deletingLastPathComponent())
        self.media = media ?? RemoteMediaService.shared(controller: controller)
        store = AIFrameStore(controller: controller)
        root = controller.storeURL.deletingLastPathComponent()
        models = try? AIModelManager.bundled(root: root.appendingPathComponent("ai-models", isDirectory: true))
    }

    func prepareForErase() async {
        erasing = true
        draftTask?.cancel()
        await draftTask?.value
        try? await drafts.erase()
        queuedFrameIDs.removeAll()
        let active = Array(jobs.values)
        let preview = previewTask
        preview?.cancel()
        for job in active { job.cancel() }
        await models?.cancelAllAndWait()
        for job in active { await job.value }
        _ = try? await preview?.value
    }

    func pauseForLowMemory() async {
        memoryWarning = true
        queuedFrameIDs.removeAll()
        previewTask?.cancel()
        if let id = activeDraftID { try? await pauseDraft(id: id, reason: "needsResume") }
        for (frameID, job) in jobs {
            job.cancel()
            try? await store.stop(frameID: frameID, status: "failed", lastError: AIFrameError.lowMemory.rawValue)
        }
    }

    func catalog() async throws -> [AIModelDescriptor] {
        guard let models else { throw AIFrameError.invalidManifest }
        return models.catalog.models
    }

    func configuration(frameID: String) async throws -> AIFrameConfiguration? {
        try await store.state(frameID: frameID)?.configuration
    }

    @discardableResult
    func configure(_ configuration: AIFrameConfiguration) async throws -> AIFrameConfiguration {
        guard let models else { throw AIFrameError.invalidManifest }
        let descriptor = try await models.descriptor(id: configuration.modelID)
        guard configuration.modelRevision == descriptor.revision else { throw AIFrameError.invalidManifest }
        let previous = jobs[configuration.frameID]
        previous?.cancel()
        let committed = try await store.configure(configuration)
        await previous?.value
        return committed
    }

    func cancel(frameID: String) async {
        queuedFrameIDs.removeAll { $0 == frameID }
        jobs[frameID]?.cancel()
        try? await store.stop(frameID: frameID, status: "userPaused")
    }

    func resume(frameID: String) async throws {
        try await store.resume(frameID: frameID)
        start(frameID: frameID, userInitiated: true)
    }

    func pauseForBackground() async {
        await AIExecutionRuntime.shared.schedule()
        guard !(await AIExecutionRuntime.shared.allowsExecution) else { return }
        await interruptSystem()
    }

    func interruptSystem() async {
        previewTask?.cancel()
        for (frameID, job) in jobs {
            job.cancel()
            try? await store.stop(frameID: frameID, status: "pausedAuto")
        }
        queuedFrameIDs.removeAll()
        if let id = activeDraftID { try? await pauseDraft(id: id, reason: "pausedAuto") }
    }

    func processBackgroundPending() async {
        guard !erasing, let states = try? await store.states() else { return }
        for state in states where state.shouldRunOnForeground { start(frameID: state.configuration.frameID) }
        if let draft = try? await drafts.current(), !draft.userPaused, ["running", "pausedAuto"].contains(draft.status) {
            try? await startDraft(id: draft.id, userInitiated: false)
        }
    }

    func waitUntilIdle() async {
        while !jobs.isEmpty || !queuedFrameIDs.isEmpty || draftTask != nil {
            if let task = draftTask { await task.value }
            for task in jobs.values { await task.value }
            await Task.yield()
        }
    }

    /// Runs after imports and on foreground. One-time configurations run again only after interruption.
    func processPending() async {
        guard !erasing, await UIApplication.shared.applicationState == .active,
              !ProcessInfo.processInfo.isLowPowerModeEnabled,
              ProcessInfo.processInfo.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue,
              let states = try? await store.states() else { return }
        if let draft = try? await drafts.current(), !draft.userPaused, ["running", "pausedAuto"].contains(draft.status) {
            try? await startDraft(id: draft.id, userInitiated: false)
        }
        for state in states {
            guard state.shouldRunOnForeground, jobs[state.configuration.frameID] == nil else { continue }
            start(frameID: state.configuration.frameID)
        }
    }

    func start(frameID: String, userInitiated: Bool = false) {
        guard !erasing, jobs[frameID] == nil, !queuedFrameIDs.contains(frameID) else { return }
        if userInitiated { explicitStarts.insert(frameID) }
        queuedFrameIDs.append(frameID)
        launchNext()
    }

    private func launchNext() {
        guard jobs.isEmpty, !queuedFrameIDs.isEmpty, previewTask == nil, draftTask == nil else { return }
        let frameID = queuedFrameIDs.removeFirst()
        jobs[frameID] = Task { [weak self] in
            await self?.run(frameID: frameID)
        }
    }

    private func run(frameID: String) async {
        let userInitiated = explicitStarts.remove(frameID) != nil
        let permitToken = await AIExecutionRuntime.shared.begin(frameID, userInitiated: userInitiated) { [weak self] reason in
            await self?.expireFrame(frameID: frameID, reason: reason)
        }
        defer {
            jobs[frameID] = nil
            Task { await AIExecutionRuntime.shared.end(frameID, token: permitToken) }
            launchNext()
        }
        guard let models else { return }
        var leasedID: String?
        var runConfiguration: AIFrameConfiguration?
        do {
            guard let initialState = try await store.state(frameID: frameID) else { return }
            let configuration = initialState.configuration
            runConfiguration = configuration
            try await checkForeground()
            let descriptor = try await models.descriptor(id: configuration.modelID)
            try checkMemory(for: descriptor)
            let directory = try await models.acquire(descriptor)
            leasedID = descriptor.id
            let engine = try AIEmbeddingEngine(model: descriptor, directory: directory)
            let candidates = try await store.candidates(configuration: configuration)
            let skipped = candidates.filter { $0.cacheable && initialState.completedContentVersions[$0.id] == Self.version($0, configuration: configuration) }.count
            let (state, token) = try await store.begin(frameID: frameID, configuration: configuration, total: candidates.count, skipped: skipped)
            let text = try await textEmbeddings(configuration.rule, engine: engine)
            for candidate in candidates {
                try Task.checkCancellation()
                try await checkForeground()
                let version = Self.version(candidate, configuration: configuration)
                if candidate.cacheable && state.completedContentVersions[candidate.id] == version { continue }
                do {
                    let match = try await classify(candidate, configuration: configuration, engine: engine, text: text)
                    try Task.checkCancellation()
                    try await store.record(frameID: frameID, token: token, candidate: candidate, version: version, match: match, apply: true)
                } catch is CancellationError { throw CancellationError() }
                catch AIFrameError.staleRun { throw AIFrameError.staleRun }
                catch AIFrameError.interrupted { throw AIFrameError.interrupted }
                catch AIFrameError.mediaBusy { throw AIFrameError.mediaBusy }
                catch {
                    try await store.record(frameID: frameID, token: token, candidate: candidate, version: version, match: nil, apply: false, failed: true)
                }
                await Task.yield()
            }
            try await store.finish(frameID: frameID, token: token, validIDs: Set(candidates.map(\.id)), apply: true)
        } catch is CancellationError {
            // Cancellation writes the new status before invalidating the previous worker's token.
        } catch AIFrameError.staleRun {
            // An edit or deletion owns the state now.
        } catch {
            let pauseReasons: [AIFrameError] = [.interrupted, .requiresForeground, .lowPower, .thermal, .mediaBusy]
            let status = pauseReasons.contains(where: { $0 == error as? AIFrameError }) ? "pausedAuto" : "failed"
            let message = (error as? AIFrameError)?.rawValue ?? "classificationFailed"
            if let runConfiguration {
                try? await store.stop(frameID: frameID, status: status, expectedConfiguration: runConfiguration, lastError: message)
            }
        }
        if let leasedID { await models.release(id: leasedID) }
    }

    private func expireFrame(frameID: String, reason: String) async {
        jobs[frameID]?.cancel()
        try? await store.stop(frameID: frameID, status: reason)
    }

    func currentDraft() async throws -> AIScanDraft? {
        guard let current = try await drafts.current() else { return nil }
        return try await validatedDraft(id: current.id)
    }

    func draft(id: String) async throws -> AIScanDraft? { try await validatedDraft(id: id) }

    private func validatedDraft(id: String, force: Bool = false) async throws -> AIScanDraft? {
        guard let snapshot = try await drafts.load(id) else { return nil }
        let version = try await store.libraryVersion()
        if !force, let validated = draftValidations[id], validated.libraryVersion == version,
           validated.updatedAt == snapshot.updatedAt { return snapshot }
        for category in snapshot.categories {
            let candidates = try await store.candidates(configuration: category.configuration)
            let contents = Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, $0.contentHash) })
            let versions = Dictionary(uniqueKeysWithValues: candidates.filter(\.cacheable).map { ($0.id, Self.version($0, configuration: category.configuration)) })
            let changed = category.total != candidates.count || category.failedIDs.contains { contents[$0] == nil }
                || category.matches.contains { contents[$0.assetID] == nil || $0.contentVersion != contents[$0.assetID] }
                || category.processedVersions.contains { versions[$0.key] != $0.value }
            if changed {
                try await drafts.reconcile(id: id, categoryID: category.id, versions: versions, contents: contents, total: candidates.count, invalidateCompletion: false)
                try await drafts.update(id) { latest in
                    if latest.status == "complete", latest.categories.contains(where: { !$0.complete }) { latest.status = "needsResume" }
                }
            }
        }
        draftValidations[id] = (version, snapshot.updatedAt)
        return try await drafts.load(id)
    }

    func prepareDraft(configurations: [AIFrameConfiguration], existingID: String?) async throws -> AIScanDraft {
        guard draftTask == nil, draftStartIntent == nil else { throw AIFrameError.busy }
        for configuration in configurations { try configuration.validate() }
        let draft = try await drafts.prepare(configurations: configurations, existingID: existingID)
        for category in draft.categories {
            let configuration = category.configuration
            let state = try await store.state(frameID: configuration.frameID)
            let total = try await store.candidates(configuration: configuration).count
            try await drafts.update(draft.id) { latest in
                guard let index = latest.categories.firstIndex(where: { $0.id == category.id }),
                      latest.categories[index].configuration.scanFingerprint == configuration.scanFingerprint else { return }
                latest.categories[index].total = total
                if latest.categories[index].matches.isEmpty, latest.categories[index].processedVersions.isEmpty,
                   let state, state.configuration.scanFingerprint == configuration.scanFingerprint {
                    latest.categories[index].matches = Array(state.matches.values)
                    latest.categories[index].processedVersions = state.completedContentVersions
                    latest.categories[index].selected = Set(state.matches.values.filter { $0.decision == .matched && !state.manualExcludedAssetIDs.contains($0.assetID) }.map(\.assetID))
                    latest.categories[index].initiallySelected = latest.categories[index].selected
                    latest.categories[index].manualExcluded = state.manualExcludedAssetIDs
                    latest.userPaused = latest.userPaused || state.isUserPaused
                }
                if latest.userPaused && latest.status != "running" { latest.status = "userPaused" }
            }
        }
        draftValidations[draft.id] = nil
        guard let current = try await drafts.load(draft.id) else { throw AIFrameError.invalidState }
        return current
    }

    func startDraft(id: String, userInitiated: Bool = true) async throws {
        guard !erasing, jobs.isEmpty, draftTask == nil, draftStartIntent == nil, previewTask == nil else { throw AIFrameError.busy }
        let intent = UUID()
        draftStartIntent = intent
        defer { if draftStartIntent == intent { draftStartIntent = nil } }
        guard let draft = try await drafts.load(id) else { throw AIFrameError.invalidState }
        guard draftStartIntent == intent else { return }
        if !userInitiated && (draft.userPaused || !["running", "pausedAuto"].contains(draft.status)) { return }
        try await drafts.update(id) { latest in
            latest.userPaused = false
            latest.status = "running"
            latest.lastError = nil
        }
        if userInitiated {
            for category in draft.categories {
                guard draftStartIntent == intent else { return }
                try await store.resume(frameID: category.configuration.frameID)
            }
        }
        guard draftStartIntent == intent else { return }
        activeDraftID = id
        let permitToken = await AIExecutionRuntime.shared.begin(id, userInitiated: userInitiated) { [weak self] reason in
            try? await self?.pauseDraft(id: id, reason: reason)
        }
        guard draftStartIntent == intent else {
            await AIExecutionRuntime.shared.end(id, token: permitToken)
            return
        }
        draftTask = Task { [weak self] in await self?.runDraft(id: id, permitToken: permitToken) }
    }

    func pauseDraft(id: String, reason: String = "userPaused") async throws {
        draftStartIntent = nil
        let running = draftTask != nil
        try await drafts.update(id) { draft in
            if ["userPaused", "needsResume"].contains(reason) { draft.userPaused = true }
            draft.status = reason == "userPaused" && running ? "pausing" : reason
        }
        if reason != "userPaused" { draftTask?.cancel() }
    }

    private func runDraft(id: String, permitToken: UUID) async {
        defer {
            draftTask = nil
            activeDraftID = nil
            Task { await AIExecutionRuntime.shared.end(id, token: permitToken) }
            launchNext()
        }
        do {
            guard let initial = try await drafts.load(id), let models else { throw AIFrameError.invalidState }
            for categoryID in initial.categories.map(\.id) {
                try Task.checkCancellation()
                guard let draft = try await drafts.load(id), !draft.userPaused,
                      let index = draft.categories.firstIndex(where: { $0.id == categoryID }) else { break }
                let configuration = draft.categories[index].configuration
                try await checkForeground()
                let model = try await models.descriptor(id: configuration.modelID)
                try checkMemory(for: model)
                let directory = try await models.acquire(model)
                do {
                    let engine = try AIEmbeddingEngine(model: model, directory: directory)
                    let candidates = try await store.candidates(configuration: configuration)
                    let current = Dictionary(uniqueKeysWithValues: candidates.filter(\.cacheable).map { ($0.id, Self.version($0, configuration: configuration)) })
                    let contents = Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, $0.contentHash) })
                    try await drafts.reconcile(id: id, categoryID: categoryID, versions: current, contents: contents, total: candidates.count)
                    guard let reconciled = try await drafts.load(id), !reconciled.userPaused else {
                        await models.release(id: model.id)
                        break
                    }
                    try Task.checkCancellation()
                    let text = try await textEmbeddings(configuration.rule, engine: engine)
                    for candidate in candidates {
                        try Task.checkCancellation()
                        guard let before = try await drafts.load(id), !before.userPaused else { break }
                        if candidate.cacheable && before.categories[index].processedVersions[candidate.id] == Self.version(candidate, configuration: configuration) { continue }
                        try await checkForeground()
                        do {
                            let match = try await classify(candidate, configuration: configuration, engine: engine, text: text)
                            guard let fresh = try await store.candidates(configuration: configuration).first(where: { $0.id == candidate.id && $0.contentHash == candidate.contentHash }) else { continue }
                            try await drafts.recordResult(id: id, categoryID: categoryID, match: match,
                                version: fresh.cacheable ? Self.version(candidate, configuration: configuration) : nil)
                            if let latest = try await drafts.load(id) {
                                await AIExecutionRuntime.shared.progress(id, completed: latest.completedCount, total: latest.totalCount)
                            }
                        } catch is CancellationError { throw CancellationError() }
                        catch AIFrameError.requiresForeground { throw AIFrameError.requiresForeground }
                        catch AIFrameError.lowMemory { throw AIFrameError.lowMemory }
                        catch AIFrameError.mediaBusy { throw AIFrameError.mediaBusy }
                        catch {
                            try await drafts.update(id) { latest in
                                guard let index = latest.categories.firstIndex(where: { $0.id == categoryID }) else { return }
                                latest.categories[index].failedIDs.insert(candidate.id)
                            }
                        }
                    }
                    try await drafts.update(id) { latest in
                        if !latest.userPaused, let index = latest.categories.firstIndex(where: { $0.id == categoryID }) {
                            latest.categories[index].complete = true
                        }
                    }
                    await models.release(id: model.id)
                } catch {
                    await models.release(id: model.id)
                    throw error
                }
            }
            try await drafts.update(id) { draft in
                draft.status = draft.userPaused ? (draft.status == "needsResume" ? "needsResume" : "userPaused")
                    : (draft.categories.contains { !$0.failedIDs.isEmpty } ? "needsResume" : "complete")
            }
        } catch {
            let message = error is CancellationError ? nil : AIFrameError.message(for: error)
            let pauseReasons: [AIFrameError] = [.interrupted, .requiresForeground, .lowPower, .thermal, .mediaBusy]
            let resumable = error is CancellationError || error is URLError || pauseReasons.contains { $0 == error as? AIFrameError }
            try? await drafts.update(id) { draft in
                if draft.userPaused { draft.status = draft.status == "needsResume" ? "needsResume" : "userPaused" }
                else { draft.status = resumable ? "pausedAuto" : "failed" }
                draft.lastError = message
            }
        }
    }

    func saveDraft(id: String) async throws -> [String] {
        guard draftTask == nil, draftStartIntent == nil, let snapshot = try await validatedDraft(id: id, force: true) else { throw AIFrameError.busy }
        var saved: [String] = []
        for category in snapshot.categories {
            let matches = category.matches.map { match in
                AIFrameMatch(assetID: match.assetID, score: match.score, decision: category.selected.contains(match.assetID) ? .matched : .rejected,
                    themes: match.themes, contentVersion: match.contentVersion)
            }
            let manual = Set(category.matches.filter { $0.decision == .review && category.selected.contains($0.assetID) }.map(\.assetID))
            let frameID = try await store.savePartialCategory(draftID: id, categoryID: category.id, name: category.name,
                configuration: category.configuration, matches: matches, processedVersions: category.processedVersions,
                userPaused: snapshot.userPaused || snapshot.status != "complete", manualIncludes: manual,
                manualExcludes: category.initiallySelected.subtracting(category.selected), complete: category.complete && snapshot.status == "complete")
            try await drafts.update(id) { latest in
                if let index = latest.categories.firstIndex(where: { $0.id == category.id }) { latest.categories[index].frameID = frameID }
            }
            saved.append(frameID)
        }
        try await drafts.update(id) { $0.status = "saved" }
        try await drafts.clearForm()
        return saved
    }

    private struct PreviewCheckpoint: Codable {
        var schemaVersion = 1
        var configuration: AIFrameConfiguration
        var contentVersions: [String: String] = [:]
        var matches: [String: AIFrameMatch] = [:]

        func matchesConfiguration(_ other: AIFrameConfiguration) -> Bool {
            configuration.scanFingerprint == other.scanFingerprint
        }
    }

    private var previewURL: URL { root.appendingPathComponent("ai-preview-v1.json") }

    func resumablePreviewConfiguration() -> AIFrameConfiguration? {
        guard let data = try? Data(contentsOf: previewURL),
              let checkpoint = try? JSONDecoder().decode(PreviewCheckpoint.self, from: data),
              checkpoint.schemaVersion == 1 else { return nil }
        return checkpoint.configuration
    }

    func cancelPreview() { previewTask?.cancel() }

    func preview(configuration: AIFrameConfiguration,
                 progress: @escaping @Sendable (AIFrameScanProgress) -> Void) async throws -> [AIFrameMatch] {
        try configuration.validate()
        guard jobs.isEmpty, previewTask == nil else { throw AIFrameError.busy }
        memoryWarning = false
        let task = Task { try await self.scanPreview(configuration: configuration, progress: progress) }
        previewTask = task
        defer { previewTask = nil; launchNext() }
        do { return try await task.value }
        catch is CancellationError {
            if memoryWarning { throw AIFrameError.lowMemory }
            throw CancellationError()
        }
    }

    private func scanPreview(configuration: AIFrameConfiguration,
                             progress: @escaping @Sendable (AIFrameScanProgress) -> Void) async throws -> [AIFrameMatch] {
        guard let models else { throw AIFrameError.invalidManifest }
        try await checkForeground()
        let descriptor = try await models.descriptor(id: configuration.modelID)
        guard descriptor.revision == configuration.modelRevision else { throw AIFrameError.invalidManifest }
        try checkMemory(for: descriptor)
        let directory = try await models.acquire(descriptor)
        do {
            let engine = try AIEmbeddingEngine(model: descriptor, directory: directory)
            let candidates = try await store.candidates(configuration: configuration)
            let libraryVersion = try await store.libraryVersion()
            let text = try await textEmbeddings(configuration.rule, engine: engine)
            var checkpoint = PreviewCheckpoint(configuration: configuration)
            if let data = try? Data(contentsOf: previewURL),
               let saved = try? JSONDecoder().decode(PreviewCheckpoint.self, from: data),
               saved.schemaVersion == 1, saved.matchesConfiguration(configuration) { checkpoint = saved }
            let content = Dictionary(uniqueKeysWithValues: candidates.filter(\.cacheable).map { ($0.id, $0.contentHash) })
            checkpoint.matches = checkpoint.matches.filter { checkpoint.contentVersions[$0.key] == content[$0.key] && content[$0.key] != nil }
            var results = Array(checkpoint.matches.values)
            var failed = 0
            for (index, candidate) in candidates.enumerated() {
                try Task.checkCancellation()
                try await checkForeground()
                if checkpoint.matches[candidate.id] == nil {
                    do {
                        let match = try await classify(candidate, configuration: configuration, engine: engine, text: text)
                        results.append(match)
                        guard try await store.libraryVersion() == libraryVersion else { throw AIFrameError.staleRun }
                        if candidate.cacheable {
                            checkpoint.matches[candidate.id] = match
                            checkpoint.contentVersions[candidate.id] = candidate.contentHash
                        }
                        try JSONEncoder().encode(checkpoint).write(to: previewURL, options: .atomic)
                    } catch is CancellationError { throw CancellationError() }
                    catch AIFrameError.staleRun { throw AIFrameError.staleRun }
                    catch AIFrameError.mediaBusy { throw AIFrameError.mediaBusy }
                    catch { failed += 1 }
                }
                progress(AIFrameScanProgress(completed: index + 1, total: candidates.count,
                                             matched: results.filter { $0.decision == .matched }.count,
                                             review: results.filter { $0.decision == .review }.count, failed: failed))
            }
            guard try await store.libraryVersion() == libraryVersion else { throw AIFrameError.staleRun }
            if !candidates.isEmpty && results.isEmpty && failed > 0 { throw AIFrameError.classificationFailed }
            await models.release(id: descriptor.id)
            return results
        } catch {
            if error as? AIFrameError == .staleRun { try? FileManager.default.removeItem(at: previewURL) }
            await models.release(id: descriptor.id)
            throw error
        }
    }

    func savePreview(frameID: String, configuration: AIFrameConfiguration, matches: [AIFrameMatch], manualIncludes: Set<String> = [], manualExcludes: Set<String> = []) async throws {
        var saved = configuration
        saved.frameID = frameID
        let committed = try await configure(saved)
        for id in Set(matches.map(\.assetID)).union(manualIncludes) where id.hasPrefix("remote:") {
            guard await media.authorized(runtimeID: id) else { throw AIFrameError.staleRun }
        }
        try await store.applyPreview(frameID: frameID, configuration: committed, matches: matches, manualIncludes: manualIncludes, manualExcludes: manualExcludes)
        try? FileManager.default.removeItem(at: previewURL)
    }

    private struct TextEmbeddings: Sendable {
        let category: [Float]
        let includes: [[Float]]
        let excludes: [[Float]]
        let themes: [(id: String, vector: [Float])]
    }

    private func textEmbeddings(_ rule: AIFrameRule, engine: AIEmbeddingEngine) async throws -> TextEmbeddings {
        try await checkForeground()
        let category = try await engine.encode(text: rule.category)
        var includes: [[Float]] = []
        var excludes: [[Float]] = []
        for value in rule.includes { try await checkForeground(); includes.append(try await engine.encode(text: value)) }
        for value in rule.excludes { try await checkForeground(); excludes.append(try await engine.encode(text: value)) }
        var themes: [(id: String, vector: [Float])] = []
        for theme in AIFrameNames.themes {
            try await checkForeground()
            themes.append((theme.id, try await engine.encode(text: theme.en)))
            themes.append((theme.id, try await engine.encode(text: theme.zh)))
        }
        return TextEmbeddings(category: category, includes: includes, excludes: excludes, themes: themes)
    }

    private func classify(_ candidate: AIFrameCandidate, configuration: AIFrameConfiguration,
                          engine: AIEmbeddingEngine, text: TextEmbeddings) async throws -> AIFrameMatch {
        if let descriptor = candidate.remoteDescriptor {
            guard await media.repository.authorized(descriptor, allowLocal: true), await media.authorized(runtimeID: candidate.id) else { throw AIFrameError.staleRun }
        }
        let cacheVersion = candidate.contentHash + ":" + configuration.modelID + ":" + configuration.modelRevision + ":preprocess2"
        let vector: [Float]
        if candidate.cacheable, let cached = try await store.cachedVector(assetID: candidate.id, version: cacheVersion, expectedDimension: text.category.count) { vector = cached }
        else {
            let lease: RemoteMediaLease
            do { lease = try await media.acquire(runtimeID: candidate.id, priority: .background) }
            catch RemoteMediaError.busy { throw AIFrameError.mediaBusy }
            do {
                if let descriptor = candidate.remoteDescriptor, descriptor != lease.descriptor { throw AIFrameError.staleRun }
                vector = try await engine.encode(imageURL: lease.fileURL)
                await media.cachePreview(lease: lease)
                await lease.release()
            } catch {
                await lease.release()
                throw error
            }
            guard await media.authorized(runtimeID: candidate.id) else { throw AIFrameError.staleRun }
            if candidate.cacheable { try await store.cacheVector(vector, assetID: candidate.id, version: cacheVersion) }
        }
        guard await media.authorized(runtimeID: candidate.id) else { throw AIFrameError.staleRun }
        let category = try AIFrameScoring.cosine(vector, text.category)
        let includes = try text.includes.map { try AIFrameScoring.cosine(vector, $0) }
        let excludes = try text.excludes.map { try AIFrameScoring.cosine(vector, $0) }
        var themeScores: [String: Float] = [:]
        for theme in text.themes {
            let score = try AIFrameScoring.cosine(vector, theme.vector)
            themeScores[theme.id] = max(themeScores[theme.id] ?? -1, score)
        }
        let themes = themeScores.filter { $0.value >= configuration.rule.threshold }
            .sorted { $0.value > $1.value }.prefix(2).map(\.key)
        let includeAggregate = configuration.rule.matchAll ? includes.min() : includes.max()
        let effectiveScore = min(category, includeAggregate ?? category)
        return AIFrameMatch(assetID: candidate.id, score: effectiveScore,
                            decision: configuration.rule.decision(categoryScore: category, includeScores: includes, excludeScores: excludes),
                            themes: themes, contentVersion: candidate.contentHash)
    }

    private static func version(_ candidate: AIFrameCandidate, configuration: AIFrameConfiguration) -> String {
        candidate.contentHash + ":" + configuration.modelID + ":" + configuration.modelRevision + ":preprocess2"
    }

    private func availableMemoryBudget() -> UInt64 {
        #if targetEnvironment(simulator)
        // Simulator processes use the host's memory rather than a device jetsam budget.
        var statistics = vm_statistics64_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64_data_t>.size / MemoryLayout<integer_t>.size)
        let result = withUnsafeMutablePointer(to: &statistics) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                host_statistics64(mach_host_self(), host_flavor_t(HOST_VM_INFO64), $0, &count)
            }
        }
        var pageSize: vm_size_t = 0
        guard result == KERN_SUCCESS, host_page_size(mach_host_self(), &pageSize) == KERN_SUCCESS else { return 0 }
        return (UInt64(statistics.free_count) + UInt64(statistics.inactive_count) + UInt64(statistics.purgeable_count)) * UInt64(pageSize)
        #else
        return UInt64(os_proc_available_memory())
        #endif
    }

    private func checkMemory(for model: AIModelDescriptor) throws {
        guard AIFrameMemoryPolicy.canLoad(runtime: model.runtime, physicalBytes: ProcessInfo.processInfo.physicalMemory,
                                        availableBytes: availableMemoryBudget()) else { throw AIFrameError.lowMemory }
    }

    private func checkForeground() async throws {
        guard availableMemoryBudget() >= AIFrameMemoryPolicy.minimumWhileRunning else { throw AIFrameError.lowMemory }
        guard await AIExecutionRuntime.shared.allowsExecution else { throw AIFrameError.requiresForeground }
        guard !ProcessInfo.processInfo.isLowPowerModeEnabled else { throw AIFrameError.lowPower }
        guard ProcessInfo.processInfo.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue else { throw AIFrameError.thermal }
    }
}
