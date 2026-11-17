import XCTest
@testable import PhotoPilot

final class AIScanDraftTests: XCTestCase {
    func testCategoryIdentityAndPartialResultsSurviveReorderAndReopen() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = AIScanDraftStore(root: root)
        let first = AIFrameConfiguration(frameID: "first", modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cat"))
        var second = first
        second.frameID = "second"
        second.rule.category = "beach"
        var draft = try await store.prepare(configurations: [first, second], existingID: nil)
        let id = draft.categories[0].id
        draft.categories[0].name = "My cats"
        draft.categories[0].processedVersions["photo"] = "version"
        draft.userPaused = true
        draft.status = "userPaused"
        try await store.save(draft)
        let reordered = try await AIScanDraftStore(root: root).prepare(configurations: [second, first], existingID: draft.id)
        XCTAssertEqual(reordered.categories[1].id, id)
        XCTAssertEqual(reordered.categories[1].name, "My cats")
        XCTAssertEqual(reordered.categories[1].processedVersions["photo"], "version")
        XCTAssertTrue(reordered.userPaused)
        XCTAssertEqual(reordered.status, "userPaused")
    }
}

extension AIScanDraftTests {
    func testUnvalidatedFormSurvivesModelManagementRoundTrip() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = AIScanDraftStore(root: root)
        var form = AIFormDraft()
        form.modelID = "selected-model"
        form.categoryText = "猫\n尚未填写完整的类别"
        form.includesText = "海边"
        form.automatic = true
        try await store.saveForm(form)
        let restored = try await AIScanDraftStore(root: root).loadForm()
        XCTAssertEqual(restored?.categoryText, form.categoryText)
        XCTAssertEqual(restored?.modelID, form.modelID)
        XCTAssertEqual(restored?.automatic, true)
    }

    func testByteDistinctRuleChangeDoesNotReuseCompletedCheckpoint() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = AIScanDraftStore(root: root)
        var configuration = AIFrameConfiguration(frameID: "category", modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "caf\u{e9}"))
        var draft = try await store.prepare(configurations: [configuration], existingID: nil)
        draft.categories[0].processedVersions["photo"] = "old"
        try await store.save(draft)
        configuration.rule.category = "cafe\u{301}"
        let changed = try await store.prepare(configurations: [configuration], existingID: draft.id)
        XCTAssertTrue(changed.categories[0].processedVersions.isEmpty)
        XCTAssertNotEqual(changed.categories[0].id, draft.categories[0].id)
    }
}

extension AIScanDraftTests {
    func testReconcilePreservesPauseNameAndInvalidatesVersionBoundSelections() async throws {
        let store = AIScanDraftStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let configuration = AIFrameConfiguration(frameID: "category", modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cat"))
        let draft = try await store.prepare(configurations: [configuration], existingID: nil)
        let category = draft.categories[0].id
        try await store.recordResult(id: draft.id, categoryID: category,
            match: AIFrameMatch(assetID: "asset", score: 1, decision: .matched, themes: [], contentVersion: "v1"), version: "processed-v1")
        try await store.update(draft.id) { $0.userPaused = true; $0.status = "userPaused" }
        try await store.presentation(id: draft.id, categoryID: category, name: "My name", selected: ["asset"])
        try await store.reconcile(id: draft.id, categoryID: category, versions: ["asset": "processed-v2"], contents: ["asset": "v2"], total: 1)
        try await store.recordResult(id: draft.id, categoryID: category,
            match: AIFrameMatch(assetID: "asset", score: 0, decision: .rejected, themes: [], contentVersion: "v2"), version: "processed-v2")
        let loaded = try await store.load(draft.id)
        XCTAssertTrue(loaded?.userPaused == true)
        XCTAssertEqual(loaded?.categories[0].name, "My name")
        XCTAssertTrue(loaded?.categories[0].selected.isEmpty == true)
        XCTAssertTrue(loaded?.categories[0].initiallySelected.isEmpty == true)
    }
}

extension AIScanDraftTests {
    func testChangedContentInvalidatesExplicitReviewSelectionAndSameVersionKeepsIt() async throws {
        let store = AIScanDraftStore(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        let configuration = AIFrameConfiguration(frameID: "category", modelID: "fixture", modelRevision: "fixed", collectionIDs: ["folder"], rule: AIFrameRule(category: "cat"))
        let draft = try await store.prepare(configurations: [configuration], existingID: nil)
        let id = draft.categories[0].id
        let review = AIFrameMatch(assetID: "asset", score: 0.18, decision: .review, themes: [], contentVersion: "v1")
        try await store.recordResult(id: draft.id, categoryID: id, match: review, version: "one")
        try await store.presentation(id: draft.id, categoryID: id, name: "Named", selected: ["asset"])
        try await store.recordResult(id: draft.id, categoryID: id, match: review, version: "one")
        let same = try await store.load(draft.id)
        XCTAssertTrue(same?.categories[0].selected.contains("asset") == true)
        try await store.reconcile(id: draft.id, categoryID: id, versions: ["asset": "two"], contents: ["asset": "v2"], total: 1)
        try await store.recordResult(id: draft.id, categoryID: id,
            match: AIFrameMatch(assetID: "asset", score: 0.18, decision: .review, themes: [], contentVersion: "v2"), version: "two")
        let changed = try await store.load(draft.id)
        XCTAssertTrue(changed?.categories[0].selected.isEmpty == true)
        XCTAssertTrue(changed?.categories[0].selectionVersions?.isEmpty == true)
    }
}
