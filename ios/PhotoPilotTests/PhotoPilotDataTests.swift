import SQLite3
import CoreData
import XCTest
@testable import PhotoPilot

final class PhotoPilotDataTests: XCTestCase {
    var model: NSManagedObjectModel!
    var coordinator: NSPersistentStoreCoordinator!
    var context: NSManagedObjectContext!

    override func setUpWithError() throws {
        model = PersistenceSchema.makeModel()
        coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        try coordinator.addPersistentStore(ofType: NSInMemoryStoreType, configurationName: nil, at: nil, options: nil)
        context = NSManagedObjectContext(concurrencyType: .mainQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
    }

    private func insert(_ entityName: String, _ values: [String: Any?]) -> NSManagedObject {
        let object = NSEntityDescription.insertNewObject(forEntityName: entityName, into: context)
        for (key, value) in values {
            if let value {
                object.setValue(value, forKey: key)
            }
        }
        return object
    }

    private func makeSource(id: String, type: String = "smb", endpointKey: String? = nil) -> NSManagedObject {
        insert("Source", [
            "id": id,
            "type": type,
            "display_name": id,
            "endpoint_key": endpointKey,
            "capability_flags": 0,
            "retention_policy": "user_managed",
            "status": "active",
            "epoch": 1,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    private func makeAsset(id: String, contentHash: String, readiness: String = "ready") -> NSManagedObject {
        insert("Asset", [
            "id": id,
            "content_hash": contentHash,
            "byte_size": 1,
            "format": "jpeg",
            "pixel_width": 1,
            "pixel_height": 1,
            "exif_orientation": 1,
            "orientation_class": "square",
            "captured_at_source": "unknown",
            "imported_at": 0,
            "original_rel_path": "ab/\(id).jpg",
            "readiness": readiness,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    func testFrameSelectionRejectsBothNil() {
        XCTAssertThrowsError(try FrameSelectionValidator.validateExactlyOneReference(assetId: nil, sourceAssetId: nil)) { error in
            XCTAssertEqual(error as? DataValidationFailure, .selectionBothNil)
        }
    }

    func testFrameSelectionRejectsBothSet() {
        XCTAssertThrowsError(try FrameSelectionValidator.validateExactlyOneReference(assetId: "a", sourceAssetId: "s")) { error in
            XCTAssertEqual(error as? DataValidationFailure, .selectionBothSet)
        }
    }

    func testFrameSelectionAcceptsExactlyOne() throws {
        try FrameSelectionValidator.validateExactlyOneReference(assetId: "a", sourceAssetId: nil)
        try FrameSelectionValidator.validateExactlyOneReference(assetId: nil, sourceAssetId: "s")
    }

    func testRefKeyPrefersAssetId() throws {
        let key = try FrameSelectionValidator.deriveRefKey(assetId: "asset-1", sourceAssetId: nil)
        XCTAssertEqual(key, "asset-1")
    }

    func testRefKeyFallsBackToSourceAssetId() throws {
        let key = try FrameSelectionValidator.deriveRefKey(assetId: nil, sourceAssetId: "src-1")
        XCTAssertEqual(key, "src-1")
    }

    func testRefKeyRejectsAmbiguousInput() {
        XCTAssertThrowsError(try FrameSelectionValidator.deriveRefKey(assetId: "a", sourceAssetId: "s"))
        XCTAssertThrowsError(try FrameSelectionValidator.deriveRefKey(assetId: nil, sourceAssetId: nil))
    }

    func testStoredRefKeyMustMatchDerived() throws {
        try FrameSelectionValidator.validateStoredRefKey(assetId: "a", sourceAssetId: nil, refKey: "a")
        XCTAssertThrowsError(try FrameSelectionValidator.validateStoredRefKey(assetId: "a", sourceAssetId: nil, refKey: "b")) { error in
            XCTAssertEqual(error as? DataValidationFailure, .selectionRefKeyMismatch)
        }
    }

    func testTombstoneMatchSharedVector() throws {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: "tombstone-match", withExtension: "json"),
            "contracts vector bundle resource missing"
        )
        let payload = try JSONSerialization.jsonObject(with: try Data(contentsOf: url)) as? [String: Any]
        let tombstones = try XCTUnwrap(payload?["tombstones"] as? [[String: Any]])
        let cases = try XCTUnwrap(payload?["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 9)
        for tombstone in tombstones {
            _ = insert("LocalTombstone", [
                "id": try XCTUnwrap(tombstone["id"] as? String),
                "content_hash": tombstone["content_hash"] as? String,
                "source_fingerprint": tombstone["source_fingerprint"] as? String,
                "remote_id": tombstone["remote_id"] as? String,
                "byte_size": (tombstone["byte_size"] as? NSNumber)?.intValue,
                "head64k_hash": tombstone["head64k_hash"] as? String,
                "reason": "user_delete",
                "created_at": 0,
                "updated_at": 0,
            ])
        }
        try context.save()

        for testCase in cases {
            let caseId = try XCTUnwrap(testCase["id"] as? String)
            let probeJSON = try XCTUnwrap(testCase["probe"] as? [String: Any])
            let expectedTier = try XCTUnwrap((testCase["expected_tier"] as? NSNumber)?.intValue)
            let expectedTombstone = testCase["expected_tombstone"] as? String
            let probe = TombstoneMatcher.Probe(
                contentHash: probeJSON["content_hash"] as? String,
                sourceFingerprint: probeJSON["source_fingerprint"] as? String,
                remoteId: probeJSON["remote_id"] as? String,
                byteSize: (probeJSON["byte_size"] as? NSNumber)?.intValue,
                head64kHash: probeJSON["head64k_hash"] as? String
            )
            let request = NSFetchRequest<NSManagedObject>(entityName: "LocalTombstone")
            request.predicate = TombstoneMatcher.matchPredicate(probe: probe)
            let matched = try context.fetch(request)
            XCTAssertEqual(
                matched.compactMap { $0.value(forKey: "id") as? String }.sorted(),
                expectedTombstone.map { [$0] } ?? [],
                caseId
            )
            var best: Int?
            for row in matched {
                guard let rank = TombstoneMatcher.rankMatch(tombstone: row, probe: probe) else { continue }
                if best == nil || rank < best! { best = rank }
            }
            XCTAssertEqual(best, expectedTier == 0 ? nil : expectedTier, caseId)
        }
    }

    func testCollectionParentCycleRejected() throws {
        let a = insert("Collection", [
            "id": "cA", "name": "A", "type": "user_folder", "sort_key": "a", "created_at": 0, "updated_at": 0,
        ])
        let b = insert("Collection", [
            "id": "cB", "name": "B", "type": "user_folder", "sort_key": "b", "created_at": 0, "updated_at": 0,
        ])
        b.setValue(a, forKey: "parent")
        try context.save()
        XCTAssertThrowsError(try CollectionValidator.validateParentIsNotInSubtree(
            context: context,
            collectionID: "cA",
            proposedParentID: "cB"
        )) { error in
            XCTAssertEqual(error as? DataValidationFailure, .collectionCycle)
        }
        try CollectionValidator.validateParentIsNotInSubtree(
            context: context,
            collectionID: "cB",
            proposedParentID: "cA"
        )
    }

    func testSourceEndpointUniquenessExcludesSelf() throws {
        _ = makeSource(id: "s1", endpointKey: "smb://nas/share")
        try context.save()
        try SourceValidator.validateEndpointUnique(
            context: context,
            type: "smb",
            endpointKey: "smb://nas/share",
            excludingSourceId: "s1"
        )
        XCTAssertThrowsError(try SourceValidator.validateEndpointUnique(
            context: context,
            type: "smb",
            endpointKey: "smb://nas/share",
            excludingSourceId: nil
        )) { error in
            XCTAssertEqual(error as? DataValidationFailure, .duplicateSourceEndpoint)
        }
        try SourceValidator.validateEndpointUnique(
            context: context,
            type: "local_photos",
            endpointKey: nil,
            excludingSourceId: nil
        )
    }

    func testContentHashUniquenessIgnoresDeletedAssets() throws {
        _ = makeAsset(id: "a1", contentHash: "sha256:dup")
        _ = makeAsset(id: "a2", contentHash: "sha256:dup", readiness: "deleted")
        try context.save()
        try AssetValidator.validateContentHashUniqueAmongActive(
            context: context,
            contentHash: "sha256:dup",
            excludingAssetId: "a1"
        )
        XCTAssertThrowsError(try AssetValidator.validateContentHashUniqueAmongActive(
            context: context,
            contentHash: "sha256:dup",
            excludingAssetId: "other"
        )) { error in
            XCTAssertEqual(error as? DataValidationFailure, .duplicateActiveContentHash)
        }
        try AssetValidator.validateContentHashUniqueAmongActive(
            context: context,
            contentHash: "sha256:brand-new",
            excludingAssetId: nil
        )
    }

    func testIncludeFutureRequiresExplicitConfirmation() {
        XCTAssertThrowsError(try FrameDirectoryRuleValidator.validateIncludeFuture(true, confirmedByUser: false)) { error in
            XCTAssertEqual(error as? DataValidationFailure, .includeFutureWithoutConfirmation)
        }
        XCTAssertNoThrow(try FrameDirectoryRuleValidator.validateIncludeFuture(true, confirmedByUser: true))
        XCTAssertNoThrow(try FrameDirectoryRuleValidator.validateIncludeFuture(false, confirmedByUser: false))
    }

    func testImportJobAggregateStateVectors() throws {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: "import-job-aggregate", withExtension: "json"),
            "contracts vector bundle resource missing"
        )
        let data = try Data(contentsOf: url)
        let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let cases = try XCTUnwrap(payload?["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 18)
        for testCase in cases {
            let id = try XCTUnwrap(testCase["id"] as? String)
            let items = try XCTUnwrap(testCase["items"] as? [String])
            let signalRaw = try XCTUnwrap(testCase["signal"] as? String)
            let expected = try XCTUnwrap(testCase["expected"] as? String)
            let enumerationComplete = (testCase["enumeration_complete"] as? Bool) ?? true
            let signal = try XCTUnwrap(
                ImportJobSignal(rawValue: signalRaw),
                "unknown signal \(signalRaw) in \(id)"
            )
            XCTAssertEqual(
                ImportJobStateValidator.derive(
                    itemStates: items,
                    signal: signal,
                    enumerationComplete: enumerationComplete
                ),
                expected,
                id
            )
        }
    }

    func testImportJobAggregateEmptyItemsFollowsEnumerationComplete() {
        XCTAssertEqual(
            ImportJobStateValidator.derive(itemStates: [], signal: .none, enumerationComplete: false),
            "running"
        )
        XCTAssertEqual(
            ImportJobStateValidator.derive(itemStates: [], signal: .none, enumerationComplete: true),
            "completed"
        )
    }

    func testImportJobStateWriteMustMatchDerived() throws {
        try ImportJobStateValidator.validateStateWrite(state: "completed", derived: "completed")
        XCTAssertThrowsError(try ImportJobStateValidator.validateStateWrite(state: "running", derived: "completed")) { error in
            XCTAssertEqual(error as? DataValidationFailure, .stateWriteNotDerived)
        }
    }

    func testLibraryVersionIncrements() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        XCTAssertEqual(try controller.bumpLibraryVersion(), 1)
        XCTAssertEqual(try controller.bumpLibraryVersion(), 2)
        XCTAssertEqual(try controller.bumpLibraryVersion(), 3)
    }

    func testSchemaVersionRoundTrip() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        XCTAssertNil(try controller.schemaVersion())
        try controller.setSchemaVersion(1)
        XCTAssertEqual(try controller.schemaVersion(), 1)
    }

    func testContractIndexesExistInStore() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storeURL = directory.appendingPathComponent("library.db")
        _ = PersistenceController(storeURL: storeURL)
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open_v2(storeURL.path, &db, SQLITE_OPEN_READONLY, nil), SQLITE_OK)
        defer { sqlite3_close_v2(db) }
        let expected = Set(ContractIndexes.statements(for: model).compactMap { sql -> String? in
            let parts = sql.split(separator: " ")
            guard let nameIndex = parts.firstIndex(of: "EXISTS") else { return nil }
            return String(parts[parts.index(after: nameIndex)])
        })
        XCTAssertFalse(expected.isEmpty)
        var statement: OpaquePointer?
        XCTAssertEqual(
            sqlite3_prepare_v2(db, "SELECT name FROM sqlite_master WHERE type = 'index'", -1, &statement, nil),
            SQLITE_OK
        )
        var actual = Set<String>()
        while sqlite3_step(statement) == SQLITE_ROW,
              let cString = sqlite3_column_text(statement, 0) {
            actual.insert(String(cString: cString))
        }
        sqlite3_finalize(statement)
        XCTAssertTrue(expected.isSubset(of: actual), "missing \(expected.subtracting(actual))")
    }

    func testModelContainsSeventeenEntities() {
        let names = Set(model.entities.compactMap(\.name))
        XCTAssertEqual(model.entities.count, 17)
        XCTAssertTrue(names.contains("WeatherSnapshot"))
        XCTAssertTrue(names.contains("HolidaySchedule"))
        XCTAssertTrue(names.contains("Source"))
        XCTAssertTrue(names.contains("SchemaMeta"))
    }

    func testWeatherSearchFallbackStripsAdminSuffixes() {
        XCTAssertEqual(OpenMeteoProvider.searchFallbackQuery("朝阳区"), "朝阳")
        XCTAssertEqual(OpenMeteoProvider.searchFallbackQuery("海淀区"), "海淀")
        XCTAssertEqual(OpenMeteoProvider.searchFallbackQuery("Fulton County"), "Fulton")
        XCTAssertEqual(OpenMeteoProvider.searchFallbackQuery(" 朝阳区"), "朝阳")
        XCTAssertNil(OpenMeteoProvider.searchFallbackQuery("海淀"))
        XCTAssertNil(OpenMeteoProvider.searchFallbackQuery("海淀 "))
        XCTAssertNil(OpenMeteoProvider.searchFallbackQuery("区"))
    }
}
