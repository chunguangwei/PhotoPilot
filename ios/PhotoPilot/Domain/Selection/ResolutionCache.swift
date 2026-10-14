import Foundation

final class ResolutionCache: @unchecked Sendable {

    struct Key: Hashable {
        let frameId: String
        let selectionVersion: Int64
        let sourceEpochsKey: String
        let libraryVersion: Int64
    }

    private var entries: [Key: SelectionResolution] = [:]
    private let lock = NSLock()

    func get(_ key: Key) -> SelectionResolution? {
        lock.lock()
        defer { lock.unlock() }
        return entries[key]
    }

    func put(_ key: Key, resolution: SelectionResolution) {
        lock.lock()
        defer { lock.unlock() }
        entries[key] = resolution
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        entries.removeAll()
    }

    static func sourceEpochsKey(_ epochs: [String: Int64]) -> String {
        epochs.sorted { $0.key < $1.key }.map { "\($0.key):\($0.value)" }.joined(separator: ",")
    }
}
