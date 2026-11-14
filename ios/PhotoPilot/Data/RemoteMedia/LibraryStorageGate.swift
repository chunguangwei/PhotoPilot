import Foundation

actor LibraryStorageGate {
    private static let registryLock = NSLock()
    nonisolated(unsafe) private static var registry: [String: LibraryStorageGate] = [:]
    private var occupied = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    static func shared(storeURL: URL) -> LibraryStorageGate {
        registryLock.lock()
        defer { registryLock.unlock() }
        if let gate = registry[storeURL.path] { return gate }
        let gate = LibraryStorageGate()
        registry[storeURL.path] = gate
        return gate
    }

    func perform<T: Sendable>(_ operation: @Sendable () async throws -> T) async throws -> T {
        if occupied { await withCheckedContinuation { waiters.append($0) } }
        else { occupied = true }
        do {
            try Task.checkCancellation()
            let value = try await operation()
            release()
            return value
        } catch {
            release()
            throw error
        }
    }

    private func release() {
        if waiters.isEmpty { occupied = false }
        else { waiters.removeFirst().resume() }
    }
}
