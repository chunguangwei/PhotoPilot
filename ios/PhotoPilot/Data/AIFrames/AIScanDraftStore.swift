import Foundation

struct AIScanCategory: Codable, Sendable {
    var id = UUID().uuidString
    var configuration: AIFrameConfiguration
    var name: String
    var matches: [AIFrameMatch] = []
    var processedVersions: [String: String] = [:]
    var failedIDs: Set<String> = []
    var total = 0
    var selected: Set<String> = []
    var initiallySelected: Set<String> = []
    var selectionVersions: [String: String]? = nil
    var manualExcluded: Set<String>? = nil
    var frameID: String?
    var complete = false
}

struct AIScanDraft: Codable, Sendable {
    var id = UUID().uuidString
    var categories: [AIScanCategory]
    var status = "idle"
    var userPaused = false
    var lastError: String?
    var updatedAt = Date()
    var completedCount: Int { categories.reduce(0) { $0 + $1.matches.count } }
    var totalCount: Int { categories.reduce(0) { $0 + $1.total } }
}

struct AIFormDraft: Codable, Sendable {
    var updatedAt = Date()
    var scanDirty = false
    var modelID = ""
    var collections: [String] = []
    var categoryText = ""
    var includesText = ""
    var excludesText = ""
    var threshold: Float = 0.24
    var automatic = false
    var recursive = true
    var matchAll = true
    var frameID: String?
    var draftID: String?
}

actor AIScanDraftStore {
    let root: URL

    init(root: URL) { self.root = root.appendingPathComponent("ai-drafts-v2", isDirectory: true) }

    func load(_ id: String) throws -> AIScanDraft? {
        guard UUID(uuidString: id) != nil else { return nil }
        let file = root.appendingPathComponent(id + ".json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(AIScanDraft.self, from: Data(contentsOf: file))
    }

    func current() throws -> AIScanDraft? {
        guard let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return nil }
        return files.filter { $0.pathExtension == "json" }.compactMap { try? JSONDecoder().decode(AIScanDraft.self, from: Data(contentsOf: $0)) }
            .filter { $0.status != "saved" }.max { $0.updatedAt < $1.updatedAt }
    }

    func prepare(configurations: [AIFrameConfiguration], existingID: String?) throws -> AIScanDraft {
        let previous = try existingID.flatMap { try load($0) }
        var used: Set<String> = []
        let categories = configurations.map { configuration -> AIScanCategory in
            if var category = previous?.categories.first(where: {
                !used.contains($0.id) && $0.configuration.scanFingerprint == configuration.scanFingerprint
            }) {
                used.insert(category.id)
                category.configuration.automatic = configuration.automatic
                return category
            }
            return AIScanCategory(configuration: configuration, name: configuration.rule.category)
        }
        var draft = AIScanDraft(categories: categories)
        if let previous {
            draft.id = previous.id
            draft.userPaused = previous.userPaused
            draft.status = previous.status
        }
        try save(draft)
        return draft
    }

    func save(_ value: AIScanDraft) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var draft = value
        draft.updatedAt = Date()
        try JSONEncoder().encode(draft).write(to: root.appendingPathComponent(draft.id + ".json"), options: .atomic)
    }

    func update(_ id: String, change: @Sendable (inout AIScanDraft) throws -> Void) throws {
        guard var draft = try load(id) else { throw AIFrameError.invalidState }
        try change(&draft)
        try save(draft)
    }

    func reconcile(id: String, categoryID: String, versions: [String: String], contents: [String: String], total: Int, invalidateCompletion: Bool = true) throws {
        try update(id) { draft in
            guard let index = draft.categories.firstIndex(where: { $0.id == categoryID }) else { throw AIFrameError.staleRun }
            var category = draft.categories[index]
            let totalChanged = category.total != total
            let processedCount = category.processedVersions.count
            category.total = total
            category.processedVersions = category.processedVersions.filter { versions[$0.key] == $0.value }
            let invalid = Set(category.matches.filter { contents[$0.assetID] == nil || $0.contentVersion != contents[$0.assetID] }.map(\.assetID))
            category.matches.removeAll { invalid.contains($0.assetID) }
            category.selected.subtract(invalid)
            category.initiallySelected.subtract(invalid)
            category.selectionVersions = category.selectionVersions?.filter { !invalid.contains($0.key) }
            category.failedIDs = category.failedIDs.intersection(Set(contents.keys))
            if invalidateCompletion || totalChanged || !invalid.isEmpty || processedCount != category.processedVersions.count {
                category.complete = false
            }
            draft.categories[index] = category
        }
    }

    func recordResult(id: String, categoryID: String, match: AIFrameMatch, version: String?) throws {
        try update(id) { draft in
            guard let index = draft.categories.firstIndex(where: { $0.id == categoryID }) else { throw AIFrameError.staleRun }
            var category = draft.categories[index]
            let overridden = match.contentVersion != nil && category.selectionVersions?[match.assetID] == match.contentVersion
            category.matches.removeAll { $0.assetID == match.assetID }
            category.matches.append(match)
            category.processedVersions[match.assetID] = version
            category.failedIDs.remove(match.assetID)
            if !overridden {
                category.selectionVersions?.removeValue(forKey: match.assetID)
                if match.decision == .matched && category.manualExcluded?.contains(match.assetID) != true {
                    category.selected.insert(match.assetID)
                    category.initiallySelected.insert(match.assetID)
                } else {
                    category.selected.remove(match.assetID)
                    category.initiallySelected.remove(match.assetID)
                }
            }
            draft.categories[index] = category
        }
    }

    func presentation(id: String, categoryID: String, name: String, selected: Set<String>) throws {
        try update(id) { draft in
            guard let index = draft.categories.firstIndex(where: { $0.id == categoryID }) else { return }
            var category = draft.categories[index]
            var overrides = category.selectionVersions ?? [:]
            for assetID in category.selected.symmetricDifference(selected) {
                overrides[assetID] = category.matches.first(where: { $0.assetID == assetID })?.contentVersion
            }
            category.selectionVersions = overrides
            category.name = name
            category.selected = selected
            draft.categories[index] = category
        }
    }

    func saveForm(_ form: AIFormDraft) throws {
        if let previous = try loadForm(), previous.updatedAt > form.updatedAt { return }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try JSONEncoder().encode(form).write(to: root.appendingPathComponent("form.json"), options: .atomic)
    }

    func loadForm() throws -> AIFormDraft? {
        let file = root.appendingPathComponent("form.json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(AIFormDraft.self, from: Data(contentsOf: file))
    }

    func clearForm() throws {
        let file = root.appendingPathComponent("form.json")
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }

    func erase() throws {
        if FileManager.default.fileExists(atPath: root.path) { try FileManager.default.removeItem(at: root) }
    }
}
