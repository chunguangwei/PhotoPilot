package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.ImportJobEntity
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.LocalFilesSourceAdapter
import dev.photopilot.scaffold.data.imports.LocalImportStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CreateJobPersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private var idSeq = 0
    private val ids: () -> String = { "id-${++idSeq}" }

    private fun newCoordinator(scope: FakeCommitScope): ImportCoordinator =
        ImportCoordinator(
            scope = scope,
            store = LocalImportStore(
                File(folder.root, "originals").apply { mkdirs() },
                File(folder.root, "tmp").apply { mkdirs() },
            ),
            clock = { now },
            idGen = ids,
        )

    @Test
    fun createJobPersistsJobRowAndBatchCollection() = runBlocking {
        val scope = FakeCommitScope()
        val coordinator = newCoordinator(scope)
        val sourceId = scope.seedSource().id

        val jobId = coordinator.createJob(sourceId, "{}")

        val job = scope.job(jobId)
        assertNotNull(job)
        assertEquals(ImportJobState.RUNNING, job?.state)
        assertEquals(sourceId, job?.sourceId)
        assertNotNull(scope.collections[ImportCoordinator.importBatchCollectionId(jobId)])
    }

    @Test
    fun enumeratePersistsItemRows() = runBlocking {
        val scope = FakeCommitScope()
        val coordinator = newCoordinator(scope)
        val sourceId = scope.seedSource().id
        val jobId = coordinator.createJob(sourceId, "{}")
        val root = File(folder.root, "source").apply { mkdirs() }
        File(root, "a.png").writeBytes(ByteArray(16) { 1 })
        File(root, "b.png").writeBytes(ByteArray(16) { 2 })

        coordinator.enumerate(
            jobId,
            LocalFilesSourceAdapter(root, "local_files:test-root"),
        )

        val items = scope.itemsForJob(jobId)
        assertEquals(2, items.size)
        items.forEach { assertNotNull(scope.item(it.id)) }
    }

    @Test
    fun updateOnMissingRowDoesNotCreateItMatchingRoomSemantics() = runBlocking {
        val scope = FakeCommitScope()
        val nowMillis = 1_700_000_000_000L
        scope.updateJob(
            ImportJobEntity(
                id = "ghost",
                sourceId = "source-1",
                scopeJson = "{}",
                epochAtCreate = 1L,
                cursor = null,
                totalKnown = null,
                countsJson = "{}",
                state = ImportJobState.RUNNING,
                createdAt = nowMillis,
                updatedAt = nowMillis,
            ),
        )
        assertNull(scope.job("ghost"))
    }
}
