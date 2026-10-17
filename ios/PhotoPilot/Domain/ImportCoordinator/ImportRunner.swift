import Foundation
import UIKit

actor ImportRunner {
    let jobID: String

    private let coordinator: ImportCoordinator
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

    init(coordinator: ImportCoordinator, jobID: String) {
        self.coordinator = coordinator
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
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled else { return }
                guard let job = try? await coordinator.fetchJob(id: jobID) else { return }
                switch job.state {
                case ImportJobState.running.rawValue:
                    if driveTask == nil {
                        drive()
                    }
                case ImportJobState.paused.rawValue, ImportJobState.pausedAuto.rawValue:
                    continue
                default:
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
            _ = try await coordinator.enqueueItems(jobID: jobID)
            _ = try await coordinator.retryDueItems(jobID: jobID, now: Self.nowMillis())
            while !Task.isCancelled {
                guard let job = try await coordinator.fetchJob(id: jobID) else { return }
                guard job.state == ImportJobState.running.rawValue else { return }
                guard let itemID = try await coordinator.nextQueuedItemID(jobID: jobID) else { return }
                do {
                    try await coordinator.stageItem(itemID: itemID)
                    try await coordinator.verifyItem(itemID: itemID)
                    _ = try await coordinator.commitItem(itemID: itemID)
                } catch {
                }
            }
        } catch {
        }
    }

    private static func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}
