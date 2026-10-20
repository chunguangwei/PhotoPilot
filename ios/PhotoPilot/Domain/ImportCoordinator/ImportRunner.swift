import Foundation
import UIKit

actor ImportRunner {
    let jobID: String

    private let coordinator: ImportCoordinator
    private let networkPolicy: (any ImportNetworkPolicy)?
    private let requiresNetwork: Bool
    private var networkPaused = false
    private var driveTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid

    private var onTerminal: (@Sendable (String) -> Void)?

    func setOnTerminal(_ handler: @escaping @Sendable (String) -> Void) {
        onTerminal = handler
    }

    var isDriving: Bool {
        driveTask != nil
    }

    init(coordinator: ImportCoordinator, jobID: String, networkPolicy: (any ImportNetworkPolicy)? = nil, requiresNetwork: Bool = true) {
        self.coordinator = coordinator
        self.networkPolicy = networkPolicy
        self.requiresNetwork = requiresNetwork
        self.jobID = jobID
    }

    func start() {
        drive()
        startWatchdog()
    }

    func pause() async throws {
        driveTask?.cancel()
        driveTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        _ = try await coordinator.handleSignal(jobID: jobID, signal: .userPause)
    }

    func resume() async throws {
        _ = try await coordinator.handleSignal(jobID: jobID, signal: .none)
        try await coordinator.requeueInterruptedItems(jobID: jobID)
        try await coordinator.retryDueItems(jobID: jobID, now: Self.nowMillis())
        drive()
        startWatchdog()
    }

    func retryFailed() async throws {
        try await coordinator.requeueFailedItems(jobID: jobID)
        drive()
        startWatchdog()
    }

    func cancel() async throws {
        driveTask?.cancel()
        driveTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        _ = try await coordinator.handleSignal(jobID: jobID, signal: .userCancel)
        onTerminal?(jobID)
    }

    func cancelAndDelete() async throws {
        driveTask?.cancel()
        driveTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        try await coordinator.cancelAndDeleteBatch(jobID: jobID)
        onTerminal?(jobID)
    }

    private func startWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: networkPolicy == nil ? 15_000_000_000 : 1_000_000_000)
                guard !Task.isCancelled else { return }
                guard let job = try? await coordinator.fetchJob(id: jobID) else { return }
                if let networkPolicy {
                    if requiresNetwork, !networkPolicy.allowsTransfer, job.state == ImportJobState.running.rawValue {
                        let task = driveTask
                        task?.cancel()
                        await task?.value
                        driveTask = nil
                        _ = try? await coordinator.handleSignal(jobID: jobID, signal: .autoPause)
                        networkPaused = true
                        continue
                    }
                    if networkPolicy.allowsTransfer, networkPaused,
                       job.state == ImportJobState.pausedAuto.rawValue {
                        networkPaused = false
                        _ = try? await coordinator.handleSignal(jobID: jobID, signal: .none)
                        try? await coordinator.requeueInterruptedItems(jobID: jobID)
                        drive()
                        continue
                    }
                }
                switch job.state {
                case ImportJobState.running.rawValue:
                    if driveTask == nil {
                        drive()
                    }
                case ImportJobState.paused.rawValue, ImportJobState.pausedAuto.rawValue:
                    continue
                default:
                    if driveTask != nil { continue }
                    onTerminal?(jobID)
                    return
                }
            }
        }
    }

    func batchMemberAssetIDs() async throws -> [String] {
        try await coordinator.batchMemberAssetIDs(jobID: jobID)
    }

    func job() async throws -> ImportJobDTO? {
        try await coordinator.fetchJob(id: jobID)
    }

    private func drive() {
        guard driveTask == nil else { return }
        driveTask = Task {
            await runLoop()
            driveTask = nil
        }
    }

    private func beginBackgroundKeepAlive() async {
        guard backgroundTaskID == .invalid else { return }
        let identifier = await MainActor.run {
            var taskID: UIBackgroundTaskIdentifier = .invalid
            taskID = UIApplication.shared.beginBackgroundTask(withName: "photopilot.import") {
                UIApplication.shared.endBackgroundTask(taskID)
            }
            return taskID
        }
        backgroundTaskID = identifier
    }

    private func endBackgroundKeepAlive() async {
        guard backgroundTaskID != .invalid else { return }
        let identifier = backgroundTaskID
        backgroundTaskID = .invalid
        await MainActor.run {
            UIApplication.shared.endBackgroundTask(identifier)
        }
    }

    private func runLoop() async {
        await beginBackgroundKeepAlive()
        defer {
            Task { await endBackgroundKeepAlive() }
        }
        do {
            if requiresNetwork { try networkPolicy?.check() }
            _ = try await coordinator.enqueueItems(jobID: jobID)
            _ = try await coordinator.retryDueItems(jobID: jobID, now: Self.nowMillis())
            while !Task.isCancelled {
                try Task.checkCancellation()
                if requiresNetwork { try networkPolicy?.check() }
                guard let current = try await coordinator.fetchJob(id: jobID) else { return }
                guard ![ImportJobState.paused.rawValue, ImportJobState.pausedAuto.rawValue,
                        ImportJobState.canceled.rawValue].contains(current.state) else { return }
                _ = try await coordinator.retryDueItems(jobID: jobID, now: Self.nowMillis())
                guard let itemID = try await coordinator.nextQueuedItemID(jobID: jobID) else {
                    if let retryAt = try await coordinator.nextRetryAt(jobID: jobID) {
                        let delay = max(1, min(1_000, retryAt - Self.nowMillis()))
                        try await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000)
                        continue
                    }
                    return
                }
                do {
                    try await coordinator.stageItem(itemID: itemID)
                    try Task.checkCancellation()
                    try await coordinator.verifyItem(itemID: itemID)
                    try Task.checkCancellation()
                    _ = try await coordinator.commitItem(itemID: itemID)
                } catch {
                    if Task.isCancelled { return }
                    if error is NasNetworkBlockedError || (requiresNetwork && networkPolicy?.allowsTransfer == false) {
                        _ = try? await coordinator.handleSignal(jobID: jobID, signal: .autoPause)
                        networkPaused = true
                        return
                    }
                }
            }
        } catch {
            guard !Task.isCancelled else { return }
            if error is NasNetworkBlockedError || (requiresNetwork && networkPolicy?.allowsTransfer == false) {
                _ = try? await coordinator.handleSignal(jobID: jobID, signal: .autoPause)
                networkPaused = true
            } else {
                _ = try? await coordinator.handleSignal(jobID: jobID, signal: .fatalError)
            }
        }
    }

    private static func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}
