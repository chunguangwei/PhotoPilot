import BackgroundTasks
import Foundation
import UIKit

@MainActor
final class ScreenWakeOwner {
    static let shared = ScreenWakeOwner()
    private var owners: Set<String> = []

    func set(_ owner: String, enabled: Bool) {
        if enabled { owners.insert(owner) } else { owners.remove(owner) }
        UIApplication.shared.isIdleTimerDisabled = !owners.isEmpty
    }
}

struct AIApplicationResources: Sendable {
    let controller: PersistenceController
    let media: RemoteMediaService
    let service: AIFrameService
}

enum AIApplicationRuntime {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var resources: [String: AIApplicationResources] = [:]

    static func resolve(controller: PersistenceController, media: RemoteMediaService? = nil) -> AIApplicationResources {
        lock.lock()
        defer { lock.unlock() }
        let key = controller.storeURL.path
        if let resource = resources[key] { return resource }
        let media = media ?? RemoteMediaService.shared(controller: controller)
        let resource = AIApplicationResources(controller: controller, media: media, service: AIFrameService(controller: controller, media: media))
        resources[key] = resource
        return resource
    }

    static func forget(_ url: URL) {
        lock.lock()
        resources[url.path] = nil
        lock.unlock()
    }
}

@MainActor
final class AIExecutionRuntime {
    static let shared = AIExecutionRuntime()
    static var processingIdentifier: String { (Bundle.main.bundleIdentifier ?? "dev.photopilot.scaffold") + ".ai.processing" }
    private struct Permit {
        let token: UUID
        var finite: UIBackgroundTaskIdentifier = .invalid
        var system: BGTask?
        let expiration: @Sendable (String) async -> Void
    }
    private var permits: [String: Permit] = [:]
    private var registeredContinued: Set<String> = []
    private var registered = false

    var allowsExecution: Bool {
        UIApplication.shared.applicationState == .active || permits.values.contains {
            $0.system != nil || ($0.finite != .invalid && UIApplication.shared.backgroundTimeRemaining > 2)
        }
    }

    func register() {
        guard !registered else { return }
        registered = true
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.processingIdentifier, using: .main) { task in
            Task { @MainActor in
                let resources = AIApplicationRuntime.resolve(controller: PersistenceController())
                self.permits["scheduled"] = Permit(token: UUID(), system: task, expiration: { _ in await resources.service.interruptSystem() })
                let token = self.permits["scheduled"]!.token
                task.expirationHandler = Self.expirationHandler { await self.expire("scheduled", token: token, reason: "pausedAuto") }
                await resources.service.processBackgroundPending()
                await resources.service.waitUntilIdle()
                self.end("scheduled", token: token)
            }
        }
    }

    @discardableResult
    func begin(_ id: String, userInitiated: Bool, expiration: @escaping @Sendable (String) async -> Void) -> UUID {
        if let existing = permits[id] { end(id, token: existing.token) }
        let token = UUID()
        ScreenWakeOwner.shared.set("ai." + id, enabled: true)
        var permit = Permit(token: token, expiration: expiration)
        permit.finite = UIApplication.shared.beginBackgroundTask(withName: "photopilot.ai", expirationHandler:
            Self.expirationHandler { await self.expire(id, token: token, reason: "pausedAuto") })
        permits[id] = permit
        guard userInitiated, UIApplication.shared.applicationState == .active else { return token }
        if #available(iOS 26.0, *) {
            let identifier = (Bundle.main.bundleIdentifier ?? "dev.photopilot.scaffold") + ".ai.continued." + token.uuidString
            if registeredContinued.insert(identifier).inserted {
                BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { task in
                    Task { @MainActor in
                        guard let continued = task as? BGContinuedProcessingTask, self.permits[id]?.token == token else {
                            task.setTaskCompleted(success: true)
                            return
                        }
                        if let finite = self.permits[id]?.finite, finite != .invalid {
                            UIApplication.shared.endBackgroundTask(finite)
                            self.permits[id]?.finite = .invalid
                        }
                        self.permits[id]?.system = continued
                        continued.progress.totalUnitCount = 1
                        task.expirationHandler = Self.expirationHandler { await self.expire(id, token: token, reason: "needsResume") }
                    }
                }
            }
            let request = BGContinuedProcessingTaskRequest(identifier: identifier,
                title: AIText.value("Photo classification", "照片分类"), subtitle: AIText.value("Processing on this device", "正在本机处理"))
            request.strategy = .fail
            do { try BGTaskScheduler.shared.submit(request) } catch { schedule() }
        }
        return token
    }

    func progress(_ id: String, completed: Int, total: Int) {
        if #available(iOS 26.0, *), let task = permits[id]?.system as? BGContinuedProcessingTask {
            task.progress.totalUnitCount = Int64(max(total, 1))
            task.progress.completedUnitCount = Int64(completed)
        }
    }

    func end(_ id: String, token: UUID? = nil) {
        guard let current = permits[id], token == nil || current.token == token,
              let permit = permits.removeValue(forKey: id) else { return }
        ScreenWakeOwner.shared.set("ai." + id, enabled: false)
        if permit.finite != .invalid { UIApplication.shared.endBackgroundTask(permit.finite) }
        permit.system?.setTaskCompleted(success: true)
    }

    func schedule() {
        let request = BGProcessingTaskRequest(identifier: Self.processingIdentifier)
        request.requiresNetworkConnectivity = true
        do { try BGTaskScheduler.shared.submit(request) } catch { }
    }

    nonisolated static func expirationHandler(_ action: @escaping @MainActor @Sendable () async -> Void) -> @Sendable () -> Void {
        { Task { @MainActor in await action() } }
    }

    private func expire(_ id: String, token: UUID, reason: String) async {
        guard permits[id]?.token == token, let permit = permits.removeValue(forKey: id) else { return }
        ScreenWakeOwner.shared.set("ai." + id, enabled: false)
        await permit.expiration(reason)
        if permit.finite != .invalid { UIApplication.shared.endBackgroundTask(permit.finite) }
        permit.system?.setTaskCompleted(success: false)
        if reason == "pausedAuto" { schedule() }
    }
}
