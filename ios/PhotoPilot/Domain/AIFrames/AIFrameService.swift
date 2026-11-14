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
    private let root: URL
    private let media: RemoteMediaService
    private var jobs: [String: Task<Void, Never>] = [:]
    private var erasing = false
    private var memoryWarning = false
    private var queuedFrameIDs: [String] = []
    private var previewTask: Task<[AIFrameMatch], Error>?

    init(controller: PersistenceController, media: RemoteMediaService? = nil) {
        self.media = media ?? RemoteMediaService.shared(controller: controller)
        store = AIFrameStore(controller: controller)
        root = controller.storeURL.deletingLastPathComponent()
        models = try? AIModelManager.bundled(root: root.appendingPathComponent("ai-models", isDirectory: true))
    }

    func prepareForErase() async {
        erasing = true
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
        try? await store.stop(frameID: frameID)
    }

    func pauseForBackground() async {
        previewTask?.cancel()
        for (frameID, job) in jobs {
            job.cancel()
            try? await store.stop(frameID: frameID, status: "pausedAuto")
        }
        queuedFrameIDs.removeAll()
    }

    /// Runs after imports and on foreground. One-time configurations run again only after interruption.
    func processPending() async {
        guard !erasing, await UIApplication.shared.applicationState == .active,
              !ProcessInfo.processInfo.isLowPowerModeEnabled,
              ProcessInfo.processInfo.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue,
              let states = try? await store.states() else { return }
        for state in states {
            guard state.shouldRunOnForeground, jobs[state.configuration.frameID] == nil else { continue }
            start(frameID: state.configuration.frameID)
        }
    }

    func start(frameID: String) {
        guard !erasing, jobs[frameID] == nil, !queuedFrameIDs.contains(frameID) else { return }
        queuedFrameIDs.append(frameID)
        launchNext()
    }

    private func launchNext() {
        guard jobs.isEmpty, !queuedFrameIDs.isEmpty, previewTask == nil else { return }
        let frameID = queuedFrameIDs.removeFirst()
        jobs[frameID] = Task { [weak self] in
            await self?.run(frameID: frameID)
        }
    }

    private func run(frameID: String) async {
        defer { jobs[frameID] = nil; launchNext() }
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
            let skipped = candidates.filter { initialState.completedContentVersions[$0.id] == Self.version($0, configuration: configuration) }.count
            let (state, token) = try await store.begin(frameID: frameID, configuration: configuration, total: candidates.count, skipped: skipped)
            let text = try await textEmbeddings(configuration.rule, engine: engine)
            for candidate in candidates {
                try Task.checkCancellation()
                try await checkForeground()
                let version = Self.version(candidate, configuration: configuration)
                if state.completedContentVersions[candidate.id] == version { continue }
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

    private struct PreviewCheckpoint: Codable {
        var schemaVersion = 1
        var configuration: AIFrameConfiguration
        var contentVersions: [String: String] = [:]
        var matches: [String: AIFrameMatch] = [:]

        func matchesConfiguration(_ other: AIFrameConfiguration) -> Bool {
            configuration.modelID == other.modelID && configuration.modelRevision == other.modelRevision
                && configuration.collectionIDs == other.collectionIDs && configuration.recursive == other.recursive
                && configuration.rule == other.rule
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
            let content = Dictionary(uniqueKeysWithValues: candidates.map { ($0.id, $0.contentHash) })
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
                        checkpoint.matches[candidate.id] = match
                        checkpoint.contentVersions[candidate.id] = candidate.contentHash
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
            guard await media.repository.authorized(descriptor, allowLocal: true) else { throw AIFrameError.staleRun }
        }
        let cacheVersion = candidate.contentHash + ":" + configuration.modelID + ":" + configuration.modelRevision + ":preprocess2"
        let vector: [Float]
        if let cached = try await store.cachedVector(assetID: candidate.id, version: cacheVersion, expectedDimension: text.category.count) { vector = cached }
        else {
            let lease: RemoteMediaLease
            do { lease = try await media.acquire(runtimeID: candidate.id, priority: .background) }
            catch RemoteMediaError.busy { throw AIFrameError.mediaBusy }
            do {
                if let descriptor = candidate.remoteDescriptor, descriptor != lease.descriptor { throw AIFrameError.staleRun }
                vector = try await engine.encode(imageURL: lease.fileURL)
                await lease.release()
            } catch {
                await lease.release()
                throw error
            }
            try await store.cacheVector(vector, assetID: candidate.id, version: cacheVersion)
        }
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
        guard await UIApplication.shared.applicationState == .active else { throw AIFrameError.requiresForeground }
        guard !ProcessInfo.processInfo.isLowPowerModeEnabled else { throw AIFrameError.lowPower }
        guard ProcessInfo.processInfo.thermalState.rawValue < ProcessInfo.ThermalState.serious.rawValue else { throw AIFrameError.thermal }
    }
}
