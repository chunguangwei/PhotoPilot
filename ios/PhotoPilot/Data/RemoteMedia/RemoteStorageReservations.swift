import Foundation

struct RemoteSpaceLease: Sendable {
    let token: UUID
    let owner: RemoteDiskReservations
    func release() async { await owner.release(token) }
}

actor RemoteDiskReservations {
    private let root: URL
    private let reserve: Int64
    private let freeSpace: @Sendable () -> Int64
    private var reservations: [UUID: Int64] = [:]

    init(root: URL, reserve: Int64 = RemoteMediaCache.reserveBytes, freeSpace: (@Sendable () -> Int64)? = nil) {
        self.root = root
        self.reserve = reserve
        self.freeSpace = freeSpace ?? {
            (try? FileManager.default.attributesOfFileSystem(forPath: root.path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        }
    }

    func acquire(bytes: Int64) throws -> RemoteSpaceLease {
        guard bytes >= 0, available() >= bytes else { throw RemoteMediaError.lowSpace }
        let token = UUID()
        reservations[token] = bytes
        return RemoteSpaceLease(token: token, owner: self)
    }

    func release(_ token: UUID) { reservations[token] = nil }
    func localMaximum() -> Int64 { max(0, available() / 2) }
    private func available() -> Int64 { freeSpace() - reserve - reservations.values.reduce(0, +) }
}

struct RemoteTransferLease: Sendable {
    let token: UUID
    let owner: RemoteTransferGate
    func release() async { await owner.release(token) }
}

actor RemoteTransferGate {
    private struct Waiter {
        let token: UUID
        let priority: RemoteMediaPriority
        let continuation: CheckedContinuation<RemoteTransferLease, Error>
    }
    private let limit: Int
    private var active: Set<UUID> = []
    private var waiters: [Waiter] = []

    init(limit: Int) { self.limit = max(1, limit) }

    func acquire(priority: RemoteMediaPriority) async throws -> RemoteTransferLease {
        try Task.checkCancellation()
        let token = UUID()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                if active.count < limit {
                    active.insert(token)
                    continuation.resume(returning: RemoteTransferLease(token: token, owner: self))
                } else { waiters.append(Waiter(token: token, priority: priority, continuation: continuation)) }
            }
        } onCancel: { Task { await self.cancel(token) } }
    }

    private func cancel(_ token: UUID) {
        if let index = waiters.firstIndex(where: { $0.token == token }) {
            waiters.remove(at: index).continuation.resume(throwing: CancellationError())
        } else { release(token) }
    }

    func release(_ token: UUID) {
        guard active.remove(token) != nil else { return }
        if let priority = waiters.map(\.priority.rawValue).max(), let index = waiters.firstIndex(where: { $0.priority.rawValue == priority }) {
            let waiter = waiters.remove(at: index)
            active.insert(waiter.token)
            waiter.continuation.resume(returning: RemoteTransferLease(token: waiter.token, owner: self))
        }
    }
}
