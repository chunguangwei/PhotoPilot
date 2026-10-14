import Foundation

struct SaveIncludeRow: Sendable, Equatable {
    let ref: String
    let manualOrder: Int64?
    let addedAt: Int64
}

struct SaveRuleRow: Sendable, Equatable {
    let collectionId: String
    let recursive: Bool
    let includeFuture: Bool
}

struct AddInclude: Sendable {
    let ref: String
    let addedAt: Int64
}

struct RuleChange: Sendable {
    let collectionId: String
    let recursive: Bool
    let includeFuture: Bool
    let confirmed: Bool
    let members: [String]
    let addedAt: Int64
}

struct SelectionSaveRequest: Sendable {
    var addIncludes: [AddInclude] = []
    var removeRefs: [String] = []
    var addExcludes: [String] = []
    var ruleChanges: [RuleChange] = []
    var removeRules: [String] = []
}

struct SelectionSaveInput: Sendable {
    let currentVersion: Int64
    let baseVersion: Int64
    let orderMode: String
    let existingIncludes: [SaveIncludeRow]
    let existingExcludes: [String]
    let existingRules: [SaveRuleRow]
    let request: SelectionSaveRequest
}

enum SelectionSaveResult: Sendable, Equatable {
    case ok(newVersion: Int64, includeRows: [SaveIncludeRow], excludeRows: [String], ruleRows: [SaveRuleRow])
    case conflict(error: String)
    case rejected
}

enum SelectionSavePlanner {
    static let errorConflict = "SELECTION_CONFLICT"

    private struct MutableRow {
        let mode: String
        let manualOrder: Int64?
        let addedAt: Int64
    }

    static func plan(_ input: SelectionSaveInput) -> SelectionSaveResult {
        guard input.baseVersion == input.currentVersion else {
            return .conflict(error: errorConflict)
        }
        if input.request.ruleChanges.contains(where: { $0.includeFuture && !$0.confirmed }) {
            return .rejected
        }

        var rows: [String: MutableRow] = [:]
        for row in input.existingIncludes {
            rows[row.ref] = MutableRow(mode: SelectionResolver.modeInclude, manualOrder: row.manualOrder, addedAt: row.addedAt)
        }
        for ref in input.existingExcludes {
            rows[ref] = MutableRow(mode: SelectionResolver.modeExclude, manualOrder: nil, addedAt: 0)
        }
        var rules = Dictionary(uniqueKeysWithValues: input.existingRules.map { ($0.collectionId, $0) })

        for ref in input.request.removeRefs {
            rows.removeValue(forKey: ref)
        }
        for add in input.request.addIncludes {
            let existing = rows[add.ref]
            rows[add.ref] = MutableRow(
                mode: SelectionResolver.modeInclude,
                manualOrder: existing?.manualOrder,
                addedAt: existing?.addedAt ?? add.addedAt
            )
        }
        for ref in input.request.addExcludes {
            let existing = rows[ref]
            rows[ref] = MutableRow(
                mode: SelectionResolver.modeExclude,
                manualOrder: nil,
                addedAt: existing?.addedAt ?? 0
            )
        }
        for change in input.request.ruleChanges {
            if change.includeFuture {
                rules[change.collectionId] = SaveRuleRow(
                    collectionId: change.collectionId,
                    recursive: change.recursive,
                    includeFuture: true
                )
            } else {
                for member in change.members {
                    let existing = rows[member]
                    if existing?.mode == SelectionResolver.modeExclude { continue }
                    rows[member] = MutableRow(
                        mode: SelectionResolver.modeInclude,
                        manualOrder: existing?.manualOrder,
                        addedAt: existing?.addedAt ?? change.addedAt
                    )
                }
            }
        }
        for collectionId in input.request.removeRules {
            rules.removeValue(forKey: collectionId)
        }

        var includeRows = rows
            .filter { $0.value.mode == SelectionResolver.modeInclude }
            .map { SaveIncludeRow(ref: $0.key, manualOrder: $0.value.manualOrder, addedAt: $0.value.addedAt) }
        if input.orderMode == PlaybackQueue.orderManual {
            includeRows.sort { lhs, rhs in
                switch (lhs.manualOrder, rhs.manualOrder) {
                case let (l?, r?):
                    if l != r { return l < r }
                case (_?, nil):
                    return true
                case (nil, _?):
                    return false
                case (nil, nil):
                    break
                }
                if lhs.addedAt != rhs.addedAt { return lhs.addedAt < rhs.addedAt }
                return lhs.ref < rhs.ref
            }
            includeRows = includeRows.enumerated().map { index, row in
                SaveIncludeRow(ref: row.ref, manualOrder: Int64(index + 1), addedAt: row.addedAt)
            }
        }

        return .ok(
            newVersion: input.currentVersion + 1,
            includeRows: includeRows.sorted { $0.ref < $1.ref },
            excludeRows: rows
                .filter { $0.value.mode == SelectionResolver.modeExclude }
                .map(\.key)
                .sorted(),
            ruleRows: rules.values.sorted { $0.collectionId < $1.collectionId }
        )
    }
}
