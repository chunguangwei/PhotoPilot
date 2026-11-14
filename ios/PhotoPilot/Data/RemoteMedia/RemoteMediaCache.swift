import CryptoKit
import Foundation

struct RemoteMediaLease: Sendable {
    let fileURL: URL
    let descriptor: RemoteMediaDescriptor
    private let owner: RemoteMediaCache?
    private let token: UUID

    init(fileURL: URL, descriptor: RemoteMediaDescriptor, owner: RemoteMediaCache? = nil, token: UUID = UUID()) {
        self.fileURL = fileURL
        self.descriptor = descriptor
        self.owner = owner
        self.token = token
    }

    func release() async { await owner?.release(identity: descriptor.identity, token: token) }
}

actor RemoteMediaCache {
    typealias Fetch = @Sendable (RemoteMediaDescriptor, URL, Int64) async throws -> Void
    typealias Authority = @Sendable (RemoteMediaDescriptor) async -> Bool
    static let budgetBytes: Int64 = 134_217_728
    static let reserveBytes: Int64 = 67_108_864

    private struct Entry: Codable {
        let descriptor: RemoteMediaDescriptor
        let filename: String
        let bytes: Int64
        var accessed: Date
    }

    private struct Work {
        let id: UUID
        let descriptor: RemoteMediaDescriptor
        let reserved: Int64
        let partial: URL
        var priority: RemoteMediaPriority
        var task: Task<Void, Never>?
        var waiters: [UUID: CheckedContinuation<RemoteMediaLease, Error>]
    }

    private struct Queued {
        let descriptor: RemoteMediaDescriptor
        let priority: RemoteMediaPriority
        let token: UUID
        let continuation: CheckedContinuation<RemoteMediaLease, Error>
        let fetch: Fetch
        let authorized: Authority
    }

    let root: URL
    private let budget: Int64
    private let freeReserve: Int64
    private let freeSpace: @Sendable () -> Int64
    private var entries: [String: Entry] = [:]
    private var readers: [String: Set<UUID>] = [:]
    private var work: [String: Work] = [:]
    private var restored = false
    private var queued: [UUID: Queued] = [:]
    private var queueOrder: [UUID] = []

    init(root: URL, budget: Int64 = RemoteMediaCache.budgetBytes, freeReserve: Int64 = RemoteMediaCache.reserveBytes,
         freeSpace: (@Sendable () -> Int64)? = nil) {
        self.root = root
        self.budget = budget
        self.freeReserve = freeReserve
        self.freeSpace = freeSpace ?? {
            let path = root.deletingLastPathComponent().path
            return (try? FileManager.default.attributesOfFileSystem(forPath: path)[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        }
    }

    func acquire(_ descriptor: RemoteMediaDescriptor, priority: RemoteMediaPriority = .visible, fetch: @escaping Fetch, authorized: @escaping Authority) async throws -> RemoteMediaLease {
        try Task.checkCancellation()
        guard await authorized(descriptor) else { throw RemoteMediaError.revoked }
        try restore()
        if priority == .playback { await prioritize(descriptor) }
        try Task.checkCancellation()
        let token = UUID()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                enqueue(descriptor, priority: priority, token: token, continuation: continuation, fetch: fetch, authorized: authorized)
            }
        } onCancel: {
            Task { await self.cancel(identity: descriptor.identity, token: token) }
        }
    }

    private func enqueue(_ descriptor: RemoteMediaDescriptor, priority: RemoteMediaPriority, token: UUID, continuation: CheckedContinuation<RemoteMediaLease, Error>, fetch: @escaping Fetch, authorized: @escaping Authority) {
        let key = descriptor.identity
        if var entry = entries[key] {
            let url = root.appendingPathComponent(entry.filename)
            if fileSize(url) == entry.bytes {
                entry.accessed = Date()
                entries[key] = entry
                readers[key, default: []].insert(token)
                continuation.resume(returning: RemoteMediaLease(fileURL: url, descriptor: descriptor, owner: self, token: token))
                saveIndex()
                return
            }
            removeEntry(key)
        }
        if var pending = work[key] {
            guard pending.task?.isCancelled != true else { continuation.resume(throwing: RemoteMediaError.busy); return }
            pending.waiters[token] = continuation
            if priority.rawValue > pending.priority.rawValue { pending.priority = priority }
            work[key] = pending
            return
        }
        let reserved = descriptor.byteSize.flatMap { $0 > 0 ? $0 : nil } ?? budget
        let transportBusy = work.count >= 2 || (priority != .playback && work.values.contains(where: { $0.priority != .playback }))
        if transportBusy && priority != .background {
            queued[token] = Queued(descriptor: descriptor, priority: priority, token: token, continuation: continuation, fetch: fetch, authorized: authorized)
            queueOrder.append(token)
            return
        }
        do {
            guard reserved <= budget else { throw RemoteMediaError.tooLarge }
            guard work.count < 2, priority == .playback || !work.values.contains(where: { $0.priority != .playback }) else { throw RemoteMediaError.busy }
            try makeRoom(reserved)
            while freeSpace() < reserved + freeReserve {
                guard let victim = entries.filter({ readers[$0.key]?.isEmpty != false }).min(by: { $0.value.accessed < $1.value.accessed })?.key else { throw RemoteMediaError.lowSpace }
                removeEntry(victim)
            }
        } catch { continuation.resume(throwing: error); return }
        let id = UUID()
        let partial = root.appendingPathComponent(id.uuidString + ".partial")
        work[key] = Work(id: id, descriptor: descriptor, reserved: reserved, partial: partial, priority: priority, task: nil, waiters: [token: continuation])
        let task = Task {
            do {
                guard await authorized(descriptor) else { throw RemoteMediaError.revoked }
                try Task.checkCancellation()
                try await fetch(descriptor, partial, reserved)
                try Task.checkCancellation()
                guard await authorized(descriptor) else { throw RemoteMediaError.revoked }
                try Task.checkCancellation()
                complete(key: key, id: id, error: nil)
            } catch { complete(key: key, id: id, error: error) }
        }
        work[key]?.task = task
    }

    private func prioritize(_ descriptor: RemoteMediaDescriptor) async {
        let lower = work.values.filter { $0.priority != .playback && $0.descriptor.identity != descriptor.identity }
        for candidate in lower {
            guard var pending = work[candidate.descriptor.identity], pending.id == candidate.id else { continue }
            for waiter in pending.waiters.values { waiter.resume(throwing: RemoteMediaError.busy) }
            pending.waiters.removeAll()
            pending.task?.cancel()
            work[candidate.descriptor.identity] = pending
            await pending.task?.value
        }
    }

    private func complete(key: String, id: UUID, error: Error?) {
        guard let pending = work[key], pending.id == id else { return }
        work[key] = nil
        defer { drainQueue() }
        do {
            if let error { throw error }
            guard let bytes = fileSize(pending.partial), bytes > 0, bytes <= pending.reserved else { throw RemoteMediaError.tooLarge }
            guard !pending.waiters.isEmpty else { throw CancellationError() }
            let filename = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined() + ".media"
            let destination = root.appendingPathComponent(filename)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: pending.partial, to: destination)
            entries[key] = Entry(descriptor: pending.descriptor, filename: filename, bytes: bytes, accessed: Date())
            for (token, continuation) in pending.waiters {
                readers[key, default: []].insert(token)
                continuation.resume(returning: RemoteMediaLease(fileURL: destination, descriptor: pending.descriptor, owner: self, token: token))
            }
            saveIndex()
        } catch {
            try? FileManager.default.removeItem(at: pending.partial)
            for continuation in pending.waiters.values { continuation.resume(throwing: error) }
        }
    }

    private func cancel(identity: String, token: UUID) {
        if let request = queued.removeValue(forKey: token) {
            queueOrder.removeAll { $0 == token }
            request.continuation.resume(throwing: CancellationError())
            return
        }
        if var pending = work[identity], let continuation = pending.waiters.removeValue(forKey: token) {
            continuation.resume(throwing: CancellationError())
            if pending.waiters.isEmpty { pending.task?.cancel() }
            work[identity] = pending
        } else { release(identity: identity, token: token) }
    }

    private func drainQueue() {
        queueOrder.sort { (queued[$0]?.priority.rawValue ?? 0) > (queued[$1]?.priority.rawValue ?? 0) }
        while let token = queueOrder.first, let request = queued[token] {
            if work.count >= 2 || (request.priority != .playback && work.values.contains(where: { $0.priority != .playback })) { return }
            queueOrder.removeFirst()
            queued[token] = nil
            enqueue(request.descriptor, priority: request.priority, token: token, continuation: request.continuation, fetch: request.fetch, authorized: request.authorized)
        }
    }

    func release(identity: String, token: UUID) {
        readers[identity]?.remove(token)
        if readers[identity]?.isEmpty == true { readers[identity] = nil }
    }

    func usage() -> Int64 {
        if !restored { try? restore() }
        return entries.values.reduce(0) { $0 + $1.bytes } + work.values.reduce(0) { $0 + $1.reserved }
    }

    func clear() async {
        for request in queued.values { request.continuation.resume(throwing: CancellationError()) }
        queued.removeAll()
        queueOrder.removeAll()
        let active = work.values.compactMap(\.task)
        for task in active { task.cancel() }
        for task in active { await task.value }
        for key in Array(entries.keys) { removeEntry(key) }
        saveIndex()
    }

    func invalidateUnauthorized(_ authorized: @escaping Authority) async -> Bool {
        var changed = false
        let candidates = entries.values.map(\.descriptor) + work.values.map(\.descriptor)
        for descriptor in candidates where !(await authorized(descriptor)) {
            changed = true
            work[descriptor.identity]?.task?.cancel()
            removeEntry(descriptor.identity)
        }
        if changed { saveIndex() }
        return changed
    }

    private func restore() throws {
        guard !restored else { return }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var directory = root
        try directory.setResourceValues(values)
        if let data = try? Data(contentsOf: root.appendingPathComponent("index.json")), let saved = try? JSONDecoder().decode([String: Entry].self, from: data) {
            entries = saved.filter { fileSize(root.appendingPathComponent($0.value.filename)) == $0.value.bytes }
        }
        let filenames = Set(entries.values.map(\.filename)).union(["index.json"])
        for file in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] where !filenames.contains(file.lastPathComponent) {
            try? FileManager.default.removeItem(at: file)
        }
        restored = true
        try makeRoom(0)
    }

    private func makeRoom(_ bytes: Int64) throws {
        while usage() + bytes > budget {
            guard let victim = entries.filter({ readers[$0.key]?.isEmpty != false }).min(by: { $0.value.accessed < $1.value.accessed })?.key else { throw RemoteMediaError.busy }
            removeEntry(victim)
        }
    }

    private func removeEntry(_ key: String) {
        guard let entry = entries.removeValue(forKey: key) else { return }
        try? FileManager.default.removeItem(at: root.appendingPathComponent(entry.filename))
        readers[key] = nil
    }

    private func fileSize(_ url: URL) -> Int64? {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
    }

    private func saveIndex() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        try? data.write(to: root.appendingPathComponent("index.json"), options: .atomic)
    }
}
