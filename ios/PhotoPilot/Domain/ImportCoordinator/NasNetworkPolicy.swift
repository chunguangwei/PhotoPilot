import Foundation
import Network

struct NasNetworkBlockedError: Error {}

protocol ImportNetworkPolicy: Sendable {
    var allowsTransfer: Bool { get }
    var wifiOnly: Bool { get }
    func check() throws
}

final class NasNetworkPolicy: ImportNetworkPolicy, @unchecked Sendable {
    private let settings: AppSettingsRepository
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var connected = false
    private var unmetered = false

    init(settings: AppSettingsRepository) {
        self.settings = settings
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self.connected = path.status == .satisfied
            self.unmetered = !path.isExpensive
            self.lock.unlock()
        }
        monitor.start(queue: DispatchQueue(label: "photopilot.nas.network"))
    }

    deinit { monitor.cancel() }

    var wifiOnly: Bool {
        (try? settings.value(forKey: AppSettingsRepository.wifiOnlyKey)) != "false"
    }

    var allowsTransfer: Bool {
        lock.lock()
        let connected = self.connected
        let unmetered = self.unmetered
        lock.unlock()
        return Self.allows(connected: connected, unmetered: unmetered, wifiOnly: wifiOnly)
    }

    static func allows(connected: Bool, unmetered: Bool, wifiOnly: Bool) -> Bool {
        connected && (!wifiOnly || unmetered)
    }

    func check() throws {
        if !allowsTransfer { throw NasNetworkBlockedError() }
    }
}

struct NetworkGuardedSourceAdapter: SourceAdapter {
    let underlying: any SourceAdapter
    let policy: NasNetworkPolicy

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        try policy.check()
        let page = try await underlying.enumerateAssets(cursor: cursor)
        try policy.check()
        return page
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        try policy.check()
        let payload = try await underlying.assetPayload(remoteID: remoteID)
        try policy.check()
        return payload
    }
}
