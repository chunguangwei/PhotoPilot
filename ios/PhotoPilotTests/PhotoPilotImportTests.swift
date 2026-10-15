import CoreData
import XCTest
@testable import PhotoPilot

final class PhotoPilotImportTests: XCTestCase {
    var controller: PersistenceController!
    var adapter: LocalFilesFixtureAdapter!
    var repository: AssetRepository!
    var coordinator: ImportCoordinator!

    private var fixtureRoot: URL {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("tests/fixtures/smoke-v1")
    }

    private var libraryRoot: URL {
        controller.storeURL.deletingLastPathComponent()
    }

    override func setUp() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        self.controller = controller
        adapter = LocalFilesFixtureAdapter(rootURL: fixtureRoot)
        repository = AssetRepository(controller: controller)
        coordinator = ImportCoordinator(controller: controller, adapter: adapter)
    }

    private func loadVector(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: name, withExtension: "json"),
            "contracts vector bundle resource missing: \(name)"
        )
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: try Data(contentsOf: url)) as? [String: Any]
        )
    }

    private func vectorCases(_ name: String) throws -> [[String: Any]] {
        try XCTUnwrap(try loadVector(name)["cases"] as? [[String: Any]])
    }

    private var expectedFixtureCount: Int {
        get throws {
            try XCTUnwrap((try loadVector("fixture-enumeration")["expected_count"] as? NSNumber)?.intValue)
        }
    }

    @discardableResult
    private func insert(_ entityName: String, _ values: [String: Any?]) -> NSManagedObject {
        let object = NSEntityDescription.insertNewObject(
            forEntityName: entityName,
            into: controller.container.viewContext
        )
        for (key, value) in values {
            if let value {
                object.setValue(value, forKey: key)
            }
        }
        return object
    }

    private func save() throws {
        try controller.container.viewContext.save()
    }

    @discardableResult
    private func seedSource(id: String, epoch: Int = 1, endpointKey: String? = nil) -> NSManagedObject {
        insert("Source", [
            "id": id,
            "type": "local_files",
            "display_name": id,
            "endpoint_key": endpointKey,
            "capability_flags": 0,
            "retention_policy": "user_managed",
            "status": "active",
            "epoch": epoch,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedSourceAsset(
        id: String,
        source: NSManagedObject,
        remoteKey: String,
        byteSize: Int? = nil
    ) -> NSManagedObject {
        insert("SourceAsset", [
            "id": id,
            "source": source,
            "remote_key": remoteKey,
            "revision": "1",
            "byte_size": byteSize,
            "last_seen_at": 0,
            "access_state": "accessible",
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedFrame(id: String) -> NSManagedObject {
        insert("Frame", [
            "id": id,
            "name": id,
            "settings_json": "{}",
            "order_mode": "manual",
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedAsset(
        id: String,
        contentHash: String,
        readiness: String = "ready",
        privacyExcluded: Bool = false,
        capturedAt: Int? = nil,
        importedAt: Int = 0,
        byteSize: Int = 2493,
        format: String = "jpeg",
        pixelWidth: Int = 240,
        pixelHeight: Int = 160
    ) -> NSManagedObject {
        insert("Asset", [
            "id": id,
            "content_hash": contentHash,
            "byte_size": byteSize,
            "format": format,
            "pixel_width": pixelWidth,
            "pixel_height": pixelHeight,
            "exif_orientation": 1,
            "orientation_class": pixelWidth > pixelHeight ? "landscape" : "portrait",
            "captured_at": capturedAt,
            "captured_at_source": capturedAt == nil ? "unknown" : "exif",
            "imported_at": importedAt,
            "original_rel_path": "ab/\(id).jpg",
            "readiness": readiness,
            "privacy_excluded": privacyExcluded,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedSelection(
        frame: NSManagedObject,
        asset: NSManagedObject?,
        sourceAsset: NSManagedObject?,
        refKey: String,
        mode: String,
        manualOrder: Int? = nil
    ) -> NSManagedObject {
        insert("FrameSelection", [
            "frame": frame,
            "asset": asset,
            "sourceAsset": sourceAsset,
            "ref_key": refKey,
            "mode": mode,
            "manual_order": manualOrder,
            "added_at": 0,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedJob(
        id: String,
        source: NSManagedObject,
        epochAtCreate: Int = 1,
        state: String = "running"
    ) -> NSManagedObject {
        insert("ImportJob", [
            "id": id,
            "source": source,
            "scope_json": "{}",
            "epoch_at_create": epochAtCreate,
            "counts_json": "{}",
            "state": state,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    @discardableResult
    private func seedItem(
        id: String,
        job: NSManagedObject,
        sourceAsset: NSManagedObject,
        state: String = "queued"
    ) -> NSManagedObject {
        insert("ImportItem", [
            "id": id,
            "job": job,
            "sourceAsset": sourceAsset,
            "state": state,
            "retry_count": 0,
            "bytes_done": 0,
            "created_at": 0,
            "updated_at": 0,
        ])
    }

    private func collectAllPages(of adapter: LocalFilesFixtureAdapter) async throws -> [String] {
        var cursor: String?
        var seen: [String] = []
        repeat {
            let page = try await adapter.enumerateAssets(cursor: cursor)
            seen += page.assets.map(\.remoteID)
            cursor = page.nextCursor
        } while cursor != nil
        return seen
    }

    private func manifestEntry(for file: String) throws -> [String: Any] {
        let url = fixtureRoot.appendingPathComponent("manifest.json")
        let payload = try JSONSerialization.jsonObject(with: try Data(contentsOf: url)) as? [String: Any]
        let entries = try XCTUnwrap(payload?["entries"] as? [[String: Any]])
        return try XCTUnwrap(entries.first { ($0["file"] as? String) == file })
    }

    func testFixtureEnumerationSharedVector() async throws {
        let vector = try loadVector("fixture-enumeration")
        let mediaExtensions = try XCTUnwrap(vector["media_extensions"] as? [String])
        XCTAssertEqual(Set(mediaExtensions), LocalFilesFixtureAdapter.mediaExtensions)
        let excluded = try XCTUnwrap(vector["excluded_examples"] as? [String])
        let cases = try vectorCases("fixture-enumeration")
        XCTAssertEqual(cases.count, 3)

        for testCase in cases {
            let caseId = try XCTUnwrap(testCase["id"] as? String)
            switch caseId {
            case "ENUM-01":
                let expected = try XCTUnwrap((testCase["expected_count"] as? NSNumber)?.intValue)
                let mustNotContain = Set(
                    try XCTUnwrap(testCase["must_not_contain"] as? [String]) + excluded
                )
                let all = try await collectAllPages(of: adapter)
                XCTAssertEqual(all.count, expected)
                XCTAssertEqual(Set(all).count, expected)
                for excludedName in mustNotContain {
                    XCTAssertFalse(all.contains(excludedName), "\(excludedName) 不得进入导入候选")
                }
            case "ENUM-02":
                let all = try await collectAllPages(of: adapter)
                let byName = Dictionary(grouping: all) { ($0 as NSString).lastPathComponent }
                XCTAssertTrue(
                    byName.values.contains { $0.count >= 2 },
                    "嵌套目录同名文件必须以不同 remote_id 同时存在"
                )
            case "ENUM-03":
                let full = Set(try await collectAllPages(of: adapter))
                let pageSizes = try XCTUnwrap(testCase["page_sizes"] as? [Int])
                for size in pageSizes {
                    let paged = LocalFilesFixtureAdapter(rootURL: fixtureRoot, pageSize: size)
                    var cursor: String?
                    var seen: [String] = []
                    repeat {
                        let page = try await paged.enumerateAssets(cursor: cursor)
                        XCTAssertLessThanOrEqual(page.assets.count, size)
                        seen += page.assets.map(\.remoteID)
                        cursor = page.nextCursor
                    } while cursor != nil
                    XCTAssertEqual(Set(seen), full, "\(caseId) page_size=\(size)")
                    XCTAssertEqual(Set(seen).count, seen.count, "\(caseId) page_size=\(size) 不得重复")
                }
            default:
                XCTFail("unknown case \(caseId)")
            }
        }
    }

    func testFixtureAdapterPayloadMatchesManifestIdentity() async throws {
        let entry = try manifestEntry(for: "F01/baseline.jpg")
        let payload = try await adapter.assetPayload(remoteID: "F01/baseline.jpg")
        let entrySha = try XCTUnwrap(entry["sha256"] as? String)
        XCTAssertEqual(payload.contentHash, "sha256:" + entrySha)
        XCTAssertEqual(payload.byteSize, (try XCTUnwrap(entry["bytes"] as? NSNumber)).intValue)
        XCTAssertEqual(payload.format, (try XCTUnwrap(entry["format"] as? String)).lowercased())
        XCTAssertEqual(payload.pixelWidth, (try XCTUnwrap(entry["displayWidth"] as? NSNumber)).intValue)
        XCTAssertEqual(payload.pixelHeight, (try XCTUnwrap(entry["displayHeight"] as? NSNumber)).intValue)
        XCTAssertEqual(payload.exifOrientation, (try XCTUnwrap(entry["orientation"] as? NSNumber)).intValue)
        if entry["capturedAt"] is NSNull {
            XCTAssertNil(payload.capturedAtMillis)
        }
    }

    func testEnqueueItemsCreatesUniqueItemsAndPersistsBreakPoint() async throws {
        let expected = try expectedFixtureCount
        _ = seedSource(id: "s1")
        try save()
        let job = try await coordinator.createJob(sourceID: "s1", scopeJSON: "{}")
        XCTAssertEqual(job.state, "running")
        XCTAssertEqual(job.sourceId, "s1")

        let first = try await coordinator.enqueueItems(jobID: job.id)
        XCTAssertEqual(first, expected)
        var jobDTO = try await coordinator.fetchJob(id: job.id)
        XCTAssertEqual(jobDTO?.totalKnown, expected)
        XCTAssertEqual(jobDTO?.cursor, ImportCoordinator.enumerationDoneCursor)
        XCTAssertEqual(jobDTO?.counts["queued"], expected)
        XCTAssertEqual(jobDTO?.state, "running")

        _ = try await coordinator.enqueueItems(jobID: job.id)
        jobDTO = try await coordinator.fetchJob(id: job.id)
        XCTAssertEqual(jobDTO?.counts["queued"], expected)
    }

    func testEnqueueItemsRequeuesSourceAssetsLeftWithoutOriginsByInterruptedJob() async throws {
        let expected = try expectedFixtureCount
        _ = seedSource(id: "s1")
        try save()
        let interrupted = try await coordinator.createJob(sourceID: "s1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: interrupted.id)

        let followUp = try await coordinator.createJob(sourceID: "s1", scopeJSON: "{}")
        _ = try await coordinator.enqueueItems(jobID: followUp.id)

        let followUpDTO = try await coordinator.fetchJob(id: followUp.id)
        XCTAssertEqual(followUpDTO?.counts["queued"], expected)
    }

    func testImportBatchDisplayNameComposedOnlyForBatches() {
        let batch = CollectionDisplayName.display(name: "2026-10-15 21:27", type: "import_batch")
        XCTAssertTrue(batch.hasSuffix("2026-10-15 21:27"))
        XCTAssertNotEqual(batch, "2026-10-15 21:27")
        XCTAssertEqual(
            CollectionDisplayName.display(name: "DCIM/Vacation", type: "source_folder"),
            "DCIM/Vacation"
        )
    }

    func testOffsetCursorFormatMatchesVector() async throws {
        let meta = try XCTUnwrap(try loadVector("fixture-enumeration")["meta"] as? [String: Any])
        XCTAssertEqual(try XCTUnwrap(meta["cursor_prefix"] as? String), OffsetCursor.prefix)
        XCTAssertEqual(try XCTUnwrap(meta["done_sentinel"] as? String), OffsetCursor.doneSentinel)
        XCTAssertEqual(OffsetCursor.encode(5), "offset:5")
        XCTAssertEqual(try OffsetCursor.decode("offset:5"), 5)
        XCTAssertEqual(try OffsetCursor.decode(nil), 0)
        XCTAssertNil(try OffsetCursor.decode("__done__"))
        XCTAssertThrowsError(try OffsetCursor.decode("5"))
        XCTAssertThrowsError(try OffsetCursor.decode("offset:x"))
        XCTAssertThrowsError(try OffsetCursor.decode("offset:-5"))
        let page = try await adapter.enumerateAssets(pageSize: 5, cursor: nil)
        XCTAssertEqual(page.nextCursor, "offset:5")
    }

    func testEnqueueItemsResumesFromPersistedOffsetCursor() async throws {
        let expected = try expectedFixtureCount
        let source = seedSource(id: "s1")
        try save()
        let job = seedJob(id: "job-resume", source: source)
        job.setValue("offset:10", forKey: "cursor")
        try save()
        let enqueued = try await coordinator.enqueueItems(jobID: "job-resume")
        XCTAssertEqual(enqueued, expected - 10)
        let jobDTO = try await coordinator.fetchJob(id: "job-resume")
        XCTAssertEqual(jobDTO?.cursor, ImportCoordinator.enumerationDoneCursor)
        XCTAssertEqual(jobDTO?.counts["queued"], expected - 10)
    }

    private let baselineHash = "sha256:d2dda116da2b1a7ffedfb924ff556c8b22ba54404ed48be3ece4d4101696d7ac"

    func testCommitMergesDuplicateContentAndRepointsSelectionPreservingManualOrder() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let frame = seedFrame(id: "f1")
        seedSelection(
            frame: frame,
            asset: nil,
            sourceAsset: sourceAsset,
            refKey: "sa1",
            mode: "include",
            manualOrder: 7
        )
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        seedAsset(id: "a-existing", contentHash: baselineHash)
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")
        let outcome = try await coordinator.commitItem(itemID: "item1")
        XCTAssertEqual(outcome, .merged(assetId: "a-existing"))

        let selections = try await repository.frameSelections(frameID: "f1")
        XCTAssertEqual(selections.count, 1)
        XCTAssertEqual(selections[0].assetId, "a-existing")
        XCTAssertNil(selections[0].sourceAssetId)
        XCTAssertEqual(selections[0].refKey, "a-existing")
        XCTAssertEqual(selections[0].manualOrder, 7)
        XCTAssertEqual(selections[0].mode, "include")

        let item = try await coordinator.fetchItem(id: "item1")
        XCTAssertEqual(item?.state, "imported")
        XCTAssertNil(item?.failureCode)

        let jobDTO = try await coordinator.fetchJob(id: "job1")
        XCTAssertEqual(jobDTO?.state, "completed")
        XCTAssertEqual(jobDTO?.counts["imported"], 1)

        let versionAfterReady = try await repository.libraryVersion()
        XCTAssertEqual(versionAfterReady, 1)
    }

    func testCommitCreatesNewAssetWithoutFabricatingCapturedAt() async throws {
        let entry = try manifestEntry(for: "F02/png-rgb.png")
        let entrySha2 = try XCTUnwrap(entry["sha256"] as? String)
        let pngHash = "sha256:" + entrySha2
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(
            id: "sa1",
            source: source,
            remoteKey: "F02/png-rgb.png",
            byteSize: (try XCTUnwrap(entry["bytes"] as? NSNumber)).intValue
        )
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")
        let outcome = try await coordinator.commitItem(itemID: "item1")
        guard case let .imported(assetId) = outcome else {
            return XCTFail("应为新建 asset，实际 \(outcome)")
        }

        let asset = try await repository.fetchAsset(id: assetId)
        XCTAssertEqual(asset?.contentHash, pngHash)
        XCTAssertEqual(asset?.format, "png")
        XCTAssertEqual(asset?.readiness, "ready")
        XCTAssertEqual(asset?.pixelWidth, (try XCTUnwrap(entry["displayWidth"] as? NSNumber)).intValue)
        XCTAssertEqual(asset?.pixelHeight, (try XCTUnwrap(entry["displayHeight"] as? NSNumber)).intValue)
        XCTAssertEqual(asset?.orientationClass, "landscape")
        XCTAssertNil(asset?.capturedAtMillis)
        XCTAssertEqual(asset?.capturedAtSource, "unknown")
        XCTAssertTrue(asset?.originalRelPath.hasPrefix("\(assetId.prefix(2))/") ?? false)

        let fileURL = libraryRoot
            .appendingPathComponent("originals", isDirectory: true)
            .appendingPathComponent(asset?.originalRelPath ?? "")
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))

        let sourceAsset2 = seedSourceAsset(
            id: "sa2",
            source: source,
            remoteKey: "F25/lossless.png",
            byteSize: (try XCTUnwrap(entry["bytes"] as? NSNumber)).intValue
        )
        seedItem(id: "item2", job: job, sourceAsset: sourceAsset2)
        try save()
        try await coordinator.stageItem(itemID: "item2")
        try await coordinator.verifyItem(itemID: "item2")
        let second = try await coordinator.commitItem(itemID: "item2")
        XCTAssertEqual(second, .merged(assetId: assetId))
    }

    func testTombstoneLevelOneSuppressesImport() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        insert("LocalTombstone", [
            "id": "t1",
            "content_hash": baselineHash,
            "reason": "user_delete",
            "created_at": 0,
            "updated_at": 0,
        ])
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")

        let item = try await coordinator.fetchItem(id: "item1")
        XCTAssertEqual(item?.state, "skipped")
        XCTAssertEqual(item?.failureCode, "SKIPPED_BY_TOMBSTONE")

        let jobDTO = try await coordinator.fetchJob(id: "job1")
        XCTAssertEqual(jobDTO?.state, "completed")
        XCTAssertEqual(jobDTO?.counts["skipped"], 1)

        let level = try await repository.tombstoneSuppressionLevel(probe: TombstoneMatcher.Probe(
            contentHash: baselineHash,
            sourceFingerprint: nil,
            remoteId: nil,
            byteSize: nil,
            head64kHash: nil
        ))
        XCTAssertEqual(level, 1)
    }

    func testTombstoneLevelTwoSuppressesByFingerprintAndRemoteId() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        insert("LocalTombstone", [
            "id": "t2",
            "source_fingerprint": "local_files:s1",
            "remote_id": "F01/baseline.jpg",
            "reason": "user_delete",
            "created_at": 0,
            "updated_at": 0,
        ])
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")

        let item = try await coordinator.fetchItem(id: "item1")
        XCTAssertEqual(item?.state, "skipped")
        let level = try await repository.tombstoneSuppressionLevel(probe: TombstoneMatcher.Probe(
            contentHash: nil,
            sourceFingerprint: "local_files:s1",
            remoteId: "F01/baseline.jpg",
            byteSize: nil,
            head64kHash: nil
        ))
        XCTAssertEqual(level, 2)
    }

    func testTombstoneLevelThreeSuppressesByFingerprintSizeHead() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        let head64k = try AssetFileProber.head64kHash(of: fixtureRoot.appendingPathComponent("F01/baseline.jpg"))
        insert("LocalTombstone", [
            "id": "t3",
            "source_fingerprint": "local_files:s1",
            "byte_size": 2493,
            "head64k_hash": head64k,
            "reason": "user_delete",
            "created_at": 0,
            "updated_at": 0,
        ])
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")

        let item = try await coordinator.fetchItem(id: "item1")
        XCTAssertEqual(item?.state, "skipped")
        let level = try await repository.tombstoneSuppressionLevel(probe: TombstoneMatcher.Probe(
            contentHash: nil,
            sourceFingerprint: "local_files:s1",
            remoteId: nil,
            byteSize: 2493,
            head64kHash: head64k
        ))
        XCTAssertEqual(level, 3)
    }

    func testCommitCancelsWhenEpochStale() async throws {
        let source = seedSource(id: "s1", epoch: 1)
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let job = seedJob(id: "job1", source: source, epochAtCreate: 1)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        try save()

        try await coordinator.stageItem(itemID: "item1")
        try await coordinator.verifyItem(itemID: "item1")

        source.setValue(2, forKey: "epoch")
        try save()

        let outcome = try await coordinator.commitItem(itemID: "item1")
        XCTAssertEqual(outcome, .canceledByEpoch)
        let item = try await coordinator.fetchItem(id: "item1")
        XCTAssertEqual(item?.state, "canceled")
        XCTAssertEqual(item?.failureCode, "CANCELLED_BY_EPOCH")
        let asset = try await repository.fetchAsset(id: baselineHash)
        XCTAssertNil(asset)
    }
    func testImportSignalEffectsSharedVector() async throws {
        let cases = try vectorCases("import-signal-effects")
        XCTAssertEqual(cases.count, 8)
        for testCase in cases {
            let caseId = try XCTUnwrap(testCase["id"] as? String)
            let items = try XCTUnwrap(testCase["items"] as? [String])
            let signalRaw = try XCTUnwrap(testCase["signal"] as? String)
            let transitions = try XCTUnwrap(testCase["itemTransitions"] as? [String: String])
            let expectedJobState = try XCTUnwrap(testCase["expectedJobState"] as? String)
            let failureCode = testCase["failureCode"] as? String
            let retryable = testCase["retryable"] as? Bool

            let signal: ImportJobSignal
            switch signalRaw {
            case "user_cancel": signal = .userCancel
            case "user_pause": signal = .userPause
            case "auto_pause": signal = .autoPause
            case "fatal_error": signal = .fatalError
            case "resume": signal = .none
            default:
                XCTFail("unknown signal \(signalRaw) in \(caseId)")
                continue
            }

            let source = seedSource(id: "src-\(caseId)")
            let jobId = "job-\(caseId)"
            let job = seedJob(id: jobId, source: source)
            var itemIds: [String] = []
            for (index, state) in items.enumerated() {
                let sourceAsset = seedSourceAsset(
                    id: "sa-\(caseId)-\(index)",
                    source: source,
                    remoteKey: "F01/baseline-\(index).jpg"
                )
                let itemId = "item-\(caseId)-\(index)"
                seedItem(id: itemId, job: job, sourceAsset: sourceAsset, state: state)
                itemIds.append(itemId)
            }
            try save()

            let dto = try await coordinator.handleSignal(jobID: jobId, signal: signal)
            XCTAssertEqual(dto.state, expectedJobState, caseId)

            for (index, itemId) in itemIds.enumerated() {
                let original = items[index]
                let actual = try await coordinator.fetchItem(id: itemId)
                if let expected = transitions[original] {
                    XCTAssertEqual(actual?.state, expected, "\(caseId): \(original) item")
                    if let failureCode {
                        XCTAssertEqual(actual?.failureCode, failureCode, "\(caseId): failureCode")
                    }
                    if let retryable, !retryable {
                        XCTAssertNil(actual?.retryAt, "\(caseId): retryable=false 不得排定 retry_at")
                    }
                } else {
                    XCTAssertEqual(actual?.state, original, "\(caseId): 未列出的 item 必须原状态保持")
                }
            }
        }
    }

    func testRetryableFailureSchedulesBackoffAndRetryRequeues() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg")
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        try save()

        let expectedBackoff = [1000, 4000, 15000]
        for round in 0..<3 {
            let before = Int(Date().timeIntervalSince1970 * 1000)
            try await coordinator.markItemFailed(itemID: "item1", code: .mediaTruncated, retryable: true)
            let fetched = try await coordinator.fetchItem(id: "item1")
            let item = try XCTUnwrap(fetched)
            XCTAssertEqual(item.state, "failed")
            XCTAssertEqual(item.retryCount, round + 1)
            XCTAssertEqual(item.failureCode, "MEDIA_TRUNCATED")
            let retryAt = try XCTUnwrap(item.retryAt)
            XCTAssertGreaterThanOrEqual(retryAt, before + expectedBackoff[round] - 2000)
            XCTAssertLessThanOrEqual(retryAt, before + expectedBackoff[round] + 2000)
            let requeued = try await coordinator.retryDueItems(jobID: "job1", now: .max)
            XCTAssertEqual(requeued, 1)
        }
        try await coordinator.markItemFailed(itemID: "item1", code: .mediaTruncated, retryable: true)
        let exhaustedFetched = try await coordinator.fetchItem(id: "item1")
        let exhausted = try XCTUnwrap(exhaustedFetched)
        XCTAssertEqual(exhausted.state, "failed")
        XCTAssertEqual(exhausted.retryCount, 3)
        XCTAssertNil(exhausted.retryAt)
    }

    func testManualRetryResetsRetryCountAndFailureCode() async throws {
        let source = seedSource(id: "s1")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg")
        let job = seedJob(id: "job1", source: source)
        seedItem(id: "item1", job: job, sourceAsset: sourceAsset)
        try save()

        try await coordinator.markItemFailed(itemID: "item1", code: .mediaTruncated, retryable: false)
        let failedFetched = try await coordinator.fetchItem(id: "item1")
        let failed = try XCTUnwrap(failedFetched)
        XCTAssertEqual(failed.state, "failed")
        XCTAssertEqual(failed.failureCode, "MEDIA_TRUNCATED")

        let requeued = try await coordinator.requeueFailedItems(jobID: "job1")
        XCTAssertEqual(requeued, 1)
        let requeuedItem = try await coordinator.fetchItem(id: "item1")
        let item = try XCTUnwrap(requeuedItem)
        XCTAssertEqual(item.state, "queued")
        XCTAssertEqual(item.retryCount, 0)
        XCTAssertNil(item.failureCode)
        XCTAssertNil(item.retryAt)
    }

    func testSelectionWriteRejectsAmbiguousReferences() async throws {
        _ = seedFrame(id: "f1")
        let source = seedSource(id: "s1")
        _ = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg")
        _ = seedAsset(id: "a1", contentHash: "sha256:other")
        try save()

        do {
            _ = try await repository.upsertFrameSelection(
                frameID: "f1", assetId: nil, sourceAssetId: nil, mode: .include
            )
            XCTFail("双 nil 必须被拦截")
        } catch let error as DataValidationFailure {
            XCTAssertEqual(error, .selectionBothNil)
        }

        do {
            _ = try await repository.upsertFrameSelection(
                frameID: "f1", assetId: "a1", sourceAssetId: "sa1", mode: .include
            )
            XCTFail("双引用必须被拦截")
        } catch let error as DataValidationFailure {
            XCTAssertEqual(error, .selectionBothSet)
        }

        let viaSourceAsset = try await repository.upsertFrameSelection(
            frameID: "f1", assetId: nil, sourceAssetId: "sa1", mode: .include, manualOrder: 3
        )
        XCTAssertEqual(viaSourceAsset.refKey, "sa1")
        XCTAssertEqual(viaSourceAsset.manualOrder, 3)
        let viaAsset = try await repository.upsertFrameSelection(
            frameID: "f1", assetId: "a1", sourceAssetId: nil, mode: .exclude
        )
        XCTAssertEqual(viaAsset.refKey, "a1")

        let selections = try await repository.frameSelections(frameID: "f1")
        XCTAssertEqual(selections.count, 2)
    }

    func testCandidatesApplyExclusionAndPrivacyFiltersWithBrowseOrdering() async throws {
        let frame = seedFrame(id: "f1")
        let a1 = seedAsset(id: "a1", contentHash: "sha256:1", capturedAt: 5000, importedAt: 0)
        let a2 = seedAsset(id: "a2", contentHash: "sha256:2", capturedAt: nil, importedAt: 300)
        let a3 = seedAsset(id: "a3", contentHash: "sha256:3", capturedAt: 100, importedAt: 0)
        let a4 = seedAsset(id: "a4", contentHash: "sha256:4", privacyExcluded: true, capturedAt: nil, importedAt: 200)
        let a5 = seedAsset(id: "a5", contentHash: "sha256:5", readiness: "deleted")
        let a6 = seedAsset(id: "a6", contentHash: "sha256:6", readiness: "pending", importedAt: 400)
        for asset in [a1, a2, a3, a4, a5, a6] {
            seedSelection(
                frame: frame,
                asset: asset,
                sourceAsset: nil,
                refKey: asset.value(forKey: "id") as? String ?? "",
                mode: "include"
            )
        }
        seedSelection(frame: frame, asset: a3, sourceAsset: nil, refKey: "a3", mode: "exclude")
        try save()

        let hidden = try await repository.candidates(frameID: "f1", privacyHidden: true)
        XCTAssertEqual(hidden.map(\.id), ["a2", "a6", "a1"])
        XCTAssertTrue(hidden.contains { $0.id == "a6" })

        let visible = try await repository.candidates(frameID: "f1", privacyHidden: false)
        XCTAssertEqual(visible.map(\.id), ["a4", "a2", "a6", "a1"])
    }

    func testDeleteAssetWritesFullTombstoneAndMarksDeleted() async throws {
        let source = seedSource(id: "s1", endpointKey: "smb://nas/share")
        let sourceAsset = seedSourceAsset(id: "sa1", source: source, remoteKey: "F01/baseline.jpg", byteSize: 2493)
        let asset = seedAsset(id: "a1", contentHash: baselineHash, byteSize: 2493)
        insert("AssetOrigin", [
            "asset": asset,
            "sourceAsset": sourceAsset,
            "imported_at": 0,
            "policy_state": "allowed",
            "origin_state": "active",
            "created_at": 0,
            "updated_at": 0,
        ])
        try save()

        let fileURL = libraryRoot
            .appendingPathComponent("originals", isDirectory: true)
            .appendingPathComponent("ab/a1.jpg")
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try FileManager.default.copyItem(
            at: fixtureRoot.appendingPathComponent("F01/baseline.jpg"),
            to: fileURL
        )

        try await repository.deleteAsset(id: "a1", reason: .userDelete)

        let deleted = try await repository.fetchAsset(id: "a1")
        XCTAssertEqual(deleted?.readiness, "deleted")
        XCTAssertNotNil(deleted?.deletedAt)

        let head64k = try AssetFileProber.head64kHash(of: fileURL)
        let probe = TombstoneMatcher.Probe(
            contentHash: baselineHash,
            sourceFingerprint: "smb://nas/share",
            remoteId: "F01/baseline.jpg",
            byteSize: 2493,
            head64kHash: head64k
        )
        let level = try await repository.tombstoneSuppressionLevel(probe: probe)
        XCTAssertEqual(level, 1)

        let versionAfterDelete = try await repository.libraryVersion()
        XCTAssertEqual(versionAfterDelete, 1)
    }
}
