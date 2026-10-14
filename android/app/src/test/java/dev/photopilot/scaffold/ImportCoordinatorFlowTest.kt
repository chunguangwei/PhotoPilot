package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.enumFromWire
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.enumFromWire
import dev.photopilot.scaffold.data.db.ImportItemEntity
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.LocalFilesSourceAdapter
import dev.photopilot.scaffold.data.imports.LocalImportStore
import dev.photopilot.scaffold.data.logic.ContentHash
import dev.photopilot.scaffold.data.logic.ImportJobAggregate
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportCoordinatorFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fingerprint = "local_files:test-root"
    private var now = 1_700_000_000_000L
    private var idSeq = 0
    private val ids: () -> String = { "id-${++idSeq}" }

    private lateinit var scope: FakeCommitScope
    private lateinit var store: LocalImportStore
    private lateinit var originals: File
    private lateinit var tmp: File
    private lateinit var coordinator: ImportCoordinator
    private var jobIdOfTest = ""

    private fun setUp(files: Map<String, ByteArray>) {
        val root = File(folder.root, "source").apply { mkdirs() }
        for ((rel, bytes) in files) {
            val target = File(root, rel)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
        originals = File(folder.root, "originals").apply { mkdirs() }
        tmp = File(folder.root, "tmp").apply { mkdirs() }
        scope = FakeCommitScope()
        store = LocalImportStore(originals, tmp)
        coordinator = ImportCoordinator(scope, store, clock = { now }, idGen = ids)
    }

    private fun adapter(rootName: String = "source") =
        LocalFilesSourceAdapter(File(folder.root, rootName), fingerprint, pageSize = 2)

    private val metadata = ImportCoordinator.AssetCommitMetadata(
        format = AssetFormat.PNG,
        pixelWidth = 100L,
        pixelHeight = 80L,
        exifOrientation = 1L,
        capturedAt = null,
        capturedAtSource = CapturedAtSource.UNKNOWN,
        originalFileName = null,
    )

    private fun shaOf(bytes: ByteArray) = ContentHash.sha256Hex(bytes)

    private fun pngBytes(seed: Int) = ByteArray(2048) { (it xor seed).toByte() }

    private suspend fun newJobWithItems(rootName: String = "source"): Pair<String, List<String>> {
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        coordinator.enumerate(jobId, adapter(rootName))
        val items = scope.itemsForJob(jobId).map { it.id }
        return jobId to items
    }

    @Test
    fun fullFlowImportsEveryItemAndCompletesJob() = runBlocking {
        val a = pngBytes(1)
        val b = pngBytes(2)
        setUp(mapOf("a.png" to a, "dir/b.png" to b))
        val (jobId, items) = newJobWithItems()

        assertEquals(2, items.size)
        assertEquals(ImportJobState.RUNNING, scope.jobState(jobId))
        assertEquals(2L, scope.jobs.getValue(jobId).totalKnown)

        val outcomes = items.map { coordinator.processItem(it, adapter(), metadata) }
        assertTrue(outcomes.all { it is ImportCoordinator.CommitOutcome.Imported && !it.merged })

        assertEquals(ImportJobState.COMPLETED, scope.jobState(jobId))
        assertEquals("{\"imported\":2}", scope.jobCounts(jobId))
        assertEquals(2, scope.assets.size)
        assertEquals(2, scope.origins.size)
        assertEquals(2L, scope.libraryVersion)
        assertTrue(scope.assets.values.all { it.readiness == AssetReadiness.READY })
        assertEquals(2, originals.walkTopDown().filter { it.isFile }.count())
        assertTrue(tmp.listFiles().isEmpty())
    }

    @Test
    fun duplicateBytesMergeIntoSingleAssetWithTwoOrigins() = runBlocking {
        val shared = pngBytes(42)
        setUp(mapOf("x.png" to shared, "y.png" to shared))
        val (jobId, items) = newJobWithItems()

        val first = coordinator.processItem(items[0], adapter(), metadata) as ImportCoordinator.CommitOutcome.Imported
        val second = coordinator.processItem(items[1], adapter(), metadata) as ImportCoordinator.CommitOutcome.Imported

        assertFalse(first.merged)
        assertTrue(second.merged)
        assertEquals(first.assetId, second.assetId)
        assertEquals(1, scope.assets.size)
        assertEquals(2, scope.origins.size)
        assertEquals(1, originals.walkTopDown().filter { it.isFile }.count())
        assertEquals(ImportJobState.COMPLETED, scope.jobState(jobId))
    }

    @Test
    fun mergeRepointsPendingSelectionAndPreservesManualOrder() = runBlocking {
        val shared = pngBytes(7)
        setUp(mapOf("x.png" to shared, "y.png" to shared))
        val (jobId, items) = newJobWithItems()
        val first = coordinator.processItem(items[0], adapter(), metadata) as ImportCoordinator.CommitOutcome.Imported

        val sa2 = scope.items.getValue(items[1]).sourceAssetId
        scope.addSelection(frameId = "frame-1", sourceAssetId = sa2, manualOrder = 5L)

        coordinator.processItem(items[1], adapter(), metadata)

        val sel = scope.selections.single()
        assertEquals(first.assetId, sel.assetId)
        assertNull(sel.sourceAssetId)
        assertEquals(first.assetId, sel.refKey)
        assertEquals(5L, sel.manualOrder)
    }

    @Test
    fun newAssetCommitAlsoRepointsSelections() = runBlocking {
        setUp(mapOf("only.png" to pngBytes(9)))
        val (_, items) = newJobWithItems()
        val sa = scope.items.getValue(items[0]).sourceAssetId
        scope.addSelection(frameId = "frame-1", sourceAssetId = sa, manualOrder = 3L)

        val outcome = coordinator.processItem(items[0], adapter(), metadata) as ImportCoordinator.CommitOutcome.Imported

        val sel = scope.selections.single()
        assertEquals(outcome.assetId, sel.assetId)
        assertNull(sel.sourceAssetId)
        assertEquals(3L, sel.manualOrder)
    }

    @Test
    fun tombstoneTier1SkipsWithoutFailure() = runBlocking {
        val bytes = pngBytes(3)
        setUp(mapOf("t.png" to bytes))
        scope.addTombstone(contentHash = shaOf(bytes))
        val (jobId, items) = newJobWithItems()

        val outcome = coordinator.processItem(items[0], adapter(), metadata)
        assertEquals(ImportCoordinator.CommitOutcome.SkippedByTombstone, outcome)

        val item = scope.items.getValue(items[0])
        assertEquals(ImportItemState.SKIPPED, item.state)
        assertEquals("SKIPPED_BY_TOMBSTONE", item.failureCode)
        assertTrue(scope.assets.isEmpty())
        assertTrue(tmp.listFiles().isEmpty())
        assertEquals(ImportJobState.COMPLETED, scope.jobState(jobId))
    }

    @Test
    fun tombstoneTier3SkipsByFingerprintSizeHead() = runBlocking {
        val bytes = ByteArray(100 * 1024) { it.toByte() }
        setUp(mapOf("big.png" to bytes))
        val streamed = ContentHash.stream(bytes.inputStream())
        scope.addTombstone(
            fingerprint = fingerprint,
            remoteId = "other-name.png",
            byteSize = streamed.bytesWritten,
            head64kHash = streamed.head64kHash,
        )
        val (_, items) = newJobWithItems()

        val outcome = coordinator.processItem(items[0], adapter(), metadata)
        assertEquals(ImportCoordinator.CommitOutcome.SkippedByTombstone, outcome)
        assertEquals(ImportItemState.SKIPPED, scope.items.getValue(items[0]).state)
        assertTrue(scope.assets.isEmpty())
    }

    @Test
    fun tombstoneTier2SkipsByFingerprintAndRemoteId() = runBlocking {
        setUp(mapOf("r.png" to pngBytes(11)))
        scope.addTombstone(fingerprint = fingerprint, remoteId = "r.png")
        val (_, items) = newJobWithItems()

        assertEquals(
            ImportCoordinator.CommitOutcome.SkippedByTombstone,
            coordinator.processItem(items[0], adapter(), metadata),
        )
    }

    @Test
    fun epochStaleCancelsCommitAndDiscardsStagedFile() = runBlocking {
        setUp(mapOf("e.png" to pngBytes(5)))
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        coordinator.enumerate(jobId, adapter())
        val itemId = scope.itemsForJob(jobId).single().id

        val stale = scope.sources.getValue(sourceId).copy(epoch = 2L)
        scope.sources[sourceId] = stale

        val outcome = coordinator.processItem(itemId, adapter(), metadata)
        assertEquals(ImportCoordinator.CommitOutcome.CanceledByEpoch, outcome)

        val item = scope.items.getValue(itemId)
        assertEquals(ImportItemState.CANCELED, item.state)
        assertEquals("CANCELLED_BY_EPOCH", item.failureCode)
        assertTrue(scope.assets.isEmpty())
        assertTrue(scope.origins.isEmpty())
        assertTrue(tmp.listFiles().isEmpty())
        assertTrue(originals.walkTopDown().filter { it.isFile }.none())
    }

    @Test
    fun enumerateResumesFromPersistedOffsetCursor() = runBlocking {
        setUp(
            mapOf(
                "a.png" to pngBytes(21),
                "b.png" to pngBytes(22),
                "c.png" to pngBytes(23),
                "d.png" to pngBytes(24),
            )
        )
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        scope.updateJob(scope.jobs.getValue(jobId).copy(cursor = "offset:2"))
        coordinator.enumerate(jobId, adapter())
        val job = scope.jobs.getValue(jobId)
        assertEquals(ImportCoordinator.CURSOR_DONE, job.cursor)
        assertEquals(2, scope.itemsForJob(jobId).size)
        assertEquals(setOf("c.png", "d.png"), scope.sourceAssets.values.map { it.remoteKey }.toSet())
    }

    @Test
    fun enumerateAfterDoneSentinelIsIdempotentNoReEnumeration() = runBlocking {
        setUp(mapOf("a.png" to pngBytes(25)))
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        coordinator.enumerate(jobId, adapter())
        val itemsBefore = scope.itemsForJob(jobId).size
        scope.updateJob(scope.jobs.getValue(jobId).copy(state = ImportJobState.PAUSED))
        coordinator.enumerate(jobId, adapter())
        assertEquals(itemsBefore, scope.itemsForJob(jobId).size)
        assertEquals(ImportCoordinator.CURSOR_DONE, scope.jobs.getValue(jobId).cursor)
    }

    @Test
    fun retryableFailuresBackoffOneFourFifteenThenStop() = runBlocking {
        setUp(mapOf("f.png" to pngBytes(6)))
        val (_, items) = newJobWithItems()
        val itemId = items[0]

        val expectedBackoff = listOf(1_000L, 4_000L, 15_000L)
        for (round in 0 until 3) {
            now += 1L
            if (round > 0) {
                assertTrue(coordinator.stage(itemId, adapter()) is ImportCoordinator.StageOutcome.Staged)
                now += 1L
            }
            coordinator.fail(itemId, "MEDIA_TRUNCATED", retryable = true)
            val item = scope.items.getValue(itemId)
            assertEquals(ImportItemState.FAILED, item.state)
            assertEquals("MEDIA_TRUNCATED", item.failureCode)
            assertEquals((round + 1).toLong(), item.retryCount)
            assertEquals(now + expectedBackoff[round], item.retryAt)
        }

        now += 1L
        assertTrue(coordinator.stage(itemId, adapter()) is ImportCoordinator.StageOutcome.Staged)
        now += 1L
        coordinator.fail(itemId, "MEDIA_TRUNCATED", retryable = true)
        val exhausted = scope.items.getValue(itemId)
        assertEquals(3L, exhausted.retryCount)
        assertNull(exhausted.retryAt)
    }

    @Test
    fun nonRetryableFailureHasNoRetryAt() = runBlocking {
        setUp(mapOf("g.png" to pngBytes(8)))
        val (_, items) = newJobWithItems()
        coordinator.fail(items[0], "MEDIA_DECODE_FAILED", retryable = false)
        val item = scope.items.getValue(items[0])
        assertEquals(ImportItemState.FAILED, item.state)
        assertNull(item.retryAt)
        assertEquals(0L, item.retryCount)
    }

    @Test
    fun partialFailedWhenSomeItemsFail() = runBlocking {
        setUp(mapOf("ok.png" to pngBytes(15), "bad.png" to pngBytes(16)))
        val (jobId, items) = newJobWithItems()
        coordinator.processItem(items[0], adapter(), metadata)
        coordinator.fail(items[1], "MEDIA_DECODE_FAILED", retryable = false)

        assertEquals(ImportJobState.PARTIAL_FAILED, scope.jobState(jobId))
        assertEquals("{\"failed\":1,\"imported\":1}", scope.jobCounts(jobId))
    }

    @Test
    fun enumerationPersistsDoneCursor() = runBlocking {
        setUp(mapOf("a.png" to pngBytes(17), "b.png" to pngBytes(18), "c.png" to pngBytes(19)))
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")

        coordinator.enumerate(jobId, adapter())

        val job = scope.jobs.getValue(jobId)
        assertEquals(ImportCoordinator.CURSOR_DONE, job.cursor)
        assertEquals(3L, job.totalKnown)
        assertEquals(3, scope.itemsForJob(jobId).size)
        assertEquals(3, scope.sourceAssets.size)
        assertEquals(ImportJobState.RUNNING, job.state)
    }

    @Test
    fun failedItemWithDueRetryCanBeStagedAgain() = runBlocking {
        setUp(mapOf("retry.png" to pngBytes(20)))
        val (_, items) = newJobWithItems()
        coordinator.fail(items[0], "NET_TIMEOUT", retryable = true)
        now += 2_000L

        val outcome = coordinator.processItem(items[0], adapter(), metadata)
        assertTrue(outcome is ImportCoordinator.CommitOutcome.Imported)
        assertEquals(ImportItemState.IMPORTED, scope.items.getValue(items[0]).state)
    }

    @Test
    fun emptyEnumerationCompleteCompletesEmptyJob() = runBlocking {
        val root = folder.newFolder("empty")
        originals = folder.newFolder("originals")
        tmp = folder.newFolder("tmp")
        scope = FakeCommitScope()
        store = LocalImportStore(originals, tmp)
        coordinator = ImportCoordinator(scope, store, clock = { now }, idGen = ids)
        val jobId = coordinator.createJob(scope.seedSource().id, "{}")
        coordinator.enumerate(jobId, LocalFilesSourceAdapter(root, fingerprint))
        assertEquals(ImportJobState.COMPLETED, scope.jobState(jobId))
    }

    @Test
    fun signalEffectsMatchSharedVectors() {
        val cases = Vectors.load("import-signal-effects.json").getJSONArray("cases")
        assertTrue("expected at least 8 signal vectors", cases.length() >= 8)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val fromStates = (0 until case.getJSONArray("items").length())
                .map { case.getJSONArray("items").getString(it) }
            val transitions = case.getJSONObject("itemTransitions")

            runSignalCase(fromStates, case)

            val expectedFinal = fromStates.map {
                if (transitions.has(it)) transitions.getString(it) else it
            }.sorted()
            assertEquals(id, expectedFinal, scope.items.values.map { it.state.wire }.sorted())
            assertEquals(
                id,
                enumFromWire<ImportJobState>(case.getString("expectedJobState")),
                scope.jobState(jobIdOfTest),
            )

            if (case.has("failureCode")) {
                val failed = scope.items.values.filter { it.state.wire == "failed" }
                assertTrue("$id: expected failed items", failed.isNotEmpty())
                failed.forEach { failedItem ->
                    assertEquals(id, case.getString("failureCode"), failedItem.failureCode)
                    if (case.has("retryable") && !case.getBoolean("retryable")) {
                        assertNull("$id: retryable=0 must not schedule retry_at", failedItem.retryAt)
                    }
                }
            }
        }
    }

    private fun runSignalCase(fromStates: List<String>, case: JSONObject) = runBlocking {
        setUp(mapOf("sig.png" to pngBytes(21)))
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        jobIdOfTest = jobId
        scope.items.clear()
        fromStates.forEachIndexed { index, wire ->
            val itemId = "vec-item-$index"
            scope.items[itemId] = ImportItemEntity(
                id = itemId,
                jobId = jobId,
                sourceAssetId = "sa-$index",
                state = enumFromWire(wire),
                tempRelPath = null,
                retryCount = 0L,
                retryAt = null,
                failureCode = null,
                bytesDone = 0L,
                bytesTotal = null,
                createdAt = now,
                updatedAt = now,
            )
        }
        scope.updateJob(scope.jobs.getValue(jobId).copy(cursor = ImportCoordinator.CURSOR_DONE))
        when (val wire = case.getString("signal")) {
            "resume" -> coordinator.resume(jobId)
            else -> coordinator.signal(jobId, signalFromWire(wire))
        }
    }

    private fun signalFromWire(wire: String): ImportJobAggregate.Signal = when (wire) {
        "none" -> ImportJobAggregate.Signal.NONE
        "user_cancel" -> ImportJobAggregate.Signal.USER_CANCEL
        "fatal_error" -> ImportJobAggregate.Signal.FATAL_ERROR
        "user_pause" -> ImportJobAggregate.Signal.USER_PAUSE
        "auto_pause" -> ImportJobAggregate.Signal.AUTO_PAUSE
        else -> error("unknown signal: $wire")
    }
}
