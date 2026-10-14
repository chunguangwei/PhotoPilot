package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.AccessState
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.LocalFilesSourceAdapter
import dev.photopilot.scaffold.data.imports.LocalImportStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NasSyncWiringTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fingerprint = "webdav://nas.local:445/photos"
    private var now = 1_700_000_000_000L
    private var idSeq = 0
    private val ids: () -> String = { "id-${++idSeq}" }

    private lateinit var scope: FakeCommitScope
    private lateinit var coordinator: ImportCoordinator
    private lateinit var root: File

    private val metadata = ImportCoordinator.AssetCommitMetadata(
        format = AssetFormat.PNG,
        pixelWidth = 100L,
        pixelHeight = 80L,
        exifOrientation = 1L,
        capturedAt = null,
        capturedAtSource = CapturedAtSource.UNKNOWN,
        originalFileName = null,
    )

    private fun setUp(fileCount: Int) {
        root = File(folder.root, "src").apply { mkdirs() }
        for (i in 0 until fileCount) {
            File(root, "f$i.png").writeBytes(ByteArray(64) { (it + i).toByte() })
        }
        scope = FakeCommitScope()
        coordinator = ImportCoordinator(
            scope = scope,
            store = LocalImportStore(
                File(folder.root, "originals").apply { mkdirs() },
                File(folder.root, "tmp").apply { mkdirs() },
            ),
            clock = { now },
            idGen = ids,
        )
        scope.seedSource(id = "src").let { source ->
            scope.sources["src"] = source.copy(endpointKey = fingerprint)
        }
    }

    private fun adapter() = LocalFilesSourceAdapter(root, fingerprint, pageSize = 50)

    private suspend fun syncOnce(scopeJson: String, mirrorDelete: Boolean): ImportCoordinator.SyncFinalizeOutcome {
        val jobId = coordinator.createJob("src", scopeJson)
        coordinator.enumerate(jobId, adapter())
        importAll(jobId)
        return coordinator.finalizeSync(jobId, mirrorDelete)
    }

    private suspend fun importAll(jobId: String) {
        scope.itemsForJob(jobId).forEach { coordinator.processItem(it.id, adapter(), metadata) }
    }

    @Test
    fun unchangedRescanRefreshesSeenWithoutNewItems() = runBlocking {
        setUp(2)
        val first = coordinator.createJob("src", "{}")
        coordinator.enumerate(first, adapter())
        importAll(first)
        now += 5_000
        val second = coordinator.createJob("src", "{}")
        coordinator.enumerate(second, adapter())
        assertEquals(0, scope.itemsForJob(second).size)
        assertTrue(scope.sourceAssets.values.all { it.missingScanCount == 0L && it.lastSeenAt == now })
        Unit
    }

    @Test
    fun revisionChangeRequeuesAndSameHashMerges() = runBlocking {
        setUp(1)
        val first = coordinator.createJob("src", "{}")
        coordinator.enumerate(first, adapter())
        importAll(first)
        val assetId = scope.assets.keys.single()
        scope.addSelection(frameId = "frame-1", assetId = assetId, manualOrder = null)
        val file = File(root, "f0.png")
        now += 1_000
        file.setLastModified(now + 60_000)
        val second = coordinator.createJob("src", "{}")
        coordinator.enumerate(second, adapter())
        assertEquals(1, scope.itemsForJob(second).size)
        val outcome = coordinator.processItem(
            scope.itemsForJob(second).single().id,
            adapter(),
            metadata,
        )
        assertTrue(outcome is ImportCoordinator.CommitOutcome.Imported)
        assertEquals(true, (outcome as ImportCoordinator.CommitOutcome.Imported).merged)
        assertEquals(assetId, outcome.assetId)
        assertEquals(assetId, scope.selections.single().assetId)
        Unit
    }

    @Test
    fun revisionChangeWithDifferentHashCreatesNewAssetAndKeepsStaticSelection() = runBlocking {
        setUp(1)
        val first = coordinator.createJob("src", "{}")
        coordinator.enumerate(first, adapter())
        importAll(first)
        val oldAssetId = scope.assets.keys.single()
        now += 1_000
        File(root, "f0.png").writeBytes(ByteArray(96) { (it * 3).toByte() })
        val second = coordinator.createJob("src", "{}")
        coordinator.enumerate(second, adapter())
        val outcome = coordinator.processItem(
            scope.itemsForJob(second).single().id,
            adapter(),
            metadata,
        ) as ImportCoordinator.CommitOutcome.Imported
        assertEquals(false, outcome.merged)
        assertNotEquals(oldAssetId, outcome.assetId)
        assertEquals(2, scope.assets.size)
        Unit
    }

    @Test
    fun mirrorOffMarksMissingWithoutCounting() = runBlocking {
        setUp(2)
        syncOnce("{}", mirrorDelete = false)
        File(root, "f0.png").delete()
        now += 1_000
        val outcome = syncOnce("{}", mirrorDelete = false)
        assertTrue(outcome is ImportCoordinator.SyncFinalizeOutcome.MissingMarked)
        val missing = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        assertEquals(AccessState.MISSING, missing.accessState)
        assertEquals(0L, missing.missingScanCount)
        Unit
    }

    @Test
    fun enablingMirrorResetsMissingHistory() = runBlocking {
        setUp(2)
        syncOnce("{}", mirrorDelete = false)
        File(root, "f0.png").delete()
        now += 1_000
        syncOnce("{}", mirrorDelete = true)
        val saBeforeReset = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        assertEquals(1L, saBeforeReset.missingScanCount)
        now = saBeforeReset.updatedAt + 61_000
        coordinator.resetMissingCounts("src")
        val reset = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        assertEquals(0L, reset.missingScanCount)
        val outcome = syncOnce("{}", mirrorDelete = true)
        assertTrue(outcome is ImportCoordinator.SyncFinalizeOutcome.MissingMarked)
        assertTrue(scope.assets.values.none { it.readiness == AssetReadiness.DELETED })
        val after = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        assertEquals(1L, after.missingScanCount)
        Unit
    }

    @Test
    fun mirrorOnCountsThenDeletesWithinRatio() = runBlocking {
        setUp(5)
        syncOnce("{}", mirrorDelete = true)
        File(root, "f0.png").delete()
        now += 1_000
        syncOnce("{}", mirrorDelete = true)
        val sa = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        assertEquals(1L, sa.missingScanCount)
        val firstMissAt = sa.updatedAt
        now = firstMissAt + 60_000
        val outcome = syncOnce("{}", mirrorDelete = true)
        assertTrue(outcome is ImportCoordinator.SyncFinalizeOutcome.Deleted)
        val saF0 = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }
        val origin = scope.origins.values.first { it.sourceAssetId == saF0.id }
        assertEquals(AssetReadiness.DELETED, scope.assets[origin.assetId]?.readiness)
        assertEquals(
            "mirror_delete",
            scope.tombstones.first { it.remoteId == "f0.png" }.reason,
        )
        Unit
    }

    @Test
    fun mirrorOnAboveRatioRequiresConfirmation() = runBlocking {
        setUp(3)
        syncOnce("{}", mirrorDelete = true)
        File(root, "f0.png").delete()
        now += 1_000
        syncOnce("{}", mirrorDelete = true)
        val firstMissAt = scope.sourceAssets.values.first { it.remoteKey == "f0.png" }.updatedAt
        File(root, "f1.png").delete()
        now = firstMissAt + 61_000
        val outcome = syncOnce("{}", mirrorDelete = true)
        assertTrue(outcome is ImportCoordinator.SyncFinalizeOutcome.PendingConfirmation)
        assertEquals(1, (outcome as ImportCoordinator.SyncFinalizeOutcome.PendingConfirmation).count)
        assertTrue(scope.assets.values.none { it.readiness == AssetReadiness.DELETED })
        val executed = coordinator.executeMirrorDeletion("src")
        assertEquals(1, executed)
        assertEquals(1, scope.tombstones.count { it.reason == "mirror_delete" })
        val saF1 = scope.sourceAssets.values.first { it.remoteKey == "f1.png" }
        assertEquals(1L, saF1.missingScanCount)
        val originF1 = scope.origins.values.first { it.sourceAssetId == saF1.id }
        assertEquals(AssetReadiness.READY, scope.assets[originF1.assetId]?.readiness)
        Unit
    }

    @Test
    fun commitMapsAssetToNearestScopeDirectoryCollection() = runBlocking {
        root = File(folder.root, "deep").apply { mkdirs() }
        File(root, "2024").mkdirs()
        File(root, "2024/a.png").writeBytes(ByteArray(64) { 9 })
        scope = FakeCommitScope()
        coordinator = ImportCoordinator(
            scope = scope,
            store = LocalImportStore(
                File(folder.root, "originals").apply { mkdirs() },
                File(folder.root, "tmp").apply { mkdirs() },
            ),
            clock = { now },
            idGen = ids,
        )
        scope.seedSource(id = "src").let { scope.sources["src"] = it.copy(endpointKey = fingerprint) }
        val deepAdapter = LocalFilesSourceAdapter(root, fingerprint)
        val jobId = coordinator.createJob("src", "{\"directories\":[\"\"]}")
        coordinator.enumerate(jobId, deepAdapter)
        scope.itemsForJob(jobId).forEach { coordinator.processItem(it.id, deepAdapter, metadata) }
        val collection = scope.collections.values.first { it.type == CollectionType.SOURCE_FOLDER }
        assertEquals("", collection.relPath)
        val assetId = scope.assets.keys.single()
        assertTrue(scope.collectionAssets.keys.contains(collection.id to assetId))
        Unit
    }
}
