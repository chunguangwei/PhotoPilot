import Foundation

enum NasSyncDiffAction: String, Sendable {
    case added
    case changed
    case unchanged
    case markSourceRemoved = "mark_source_removed"
    case deletionCandidate = "deletion_candidate"
}

enum NasSyncDiff {
    static func diff(
        old: [String: String?],
        new: [String: String?],
        mirrorDelete: Bool
    ) -> [String: NasSyncDiffAction] {
        var result: [String: NasSyncDiffAction] = [:]
        for id in new.keys {
            if old.keys.contains(id) {
                let oldRevision: String? = old[id] ?? nil
                let newRevision: String? = new[id] ?? nil
                result[id] = oldRevision == newRevision ? .unchanged : .changed
            } else {
                result[id] = .added
            }
        }
        for id in old.keys where !new.keys.contains(id) {
            result[id] = mirrorDelete ? .deletionCandidate : .markSourceRemoved
        }
        return result
    }
}
