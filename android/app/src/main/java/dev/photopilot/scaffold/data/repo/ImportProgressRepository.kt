package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.ImportJobEntity
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.logic.ImportCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportProgressRepository(private val db: PhotoPilotDatabase) {

    data class Snapshot(
        val jobId: String,
        val sourceId: String,
        val state: ImportJobState,
        val enumerationComplete: Boolean,
        val totalKnown: Long?,
        val counts: Map<String, Int>,
        val bytesTotal: Long?,
        val hasLowSpaceFailure: Boolean,
    )

    suspend fun latest(): Snapshot? = withContext(Dispatchers.IO) {
        db.importJobDao().latest()?.let { snapshotOf(it) }
    }

    suspend fun byId(jobId: String): Snapshot? = withContext(Dispatchers.IO) {
        db.importJobDao().getById(jobId)?.let { snapshotOf(it) }
    }

    private suspend fun snapshotOf(job: ImportJobEntity): Snapshot {
        val items = db.importItemDao().getByJob(job.id)
        val sizes = items.mapNotNull { it.bytesTotal }
        val bytesTotal = if (items.isNotEmpty() && sizes.size == items.size) sizes.sum() else null
        return Snapshot(
            jobId = job.id,
            sourceId = job.sourceId,
            state = job.state,
            enumerationComplete = job.cursor == ImportCoordinator.CURSOR_DONE,
            totalKnown = job.totalKnown,
            counts = ImportCounts.parse(job.countsJson),
            bytesTotal = bytesTotal,
            hasLowSpaceFailure = items.any { it.failureCode == LOW_SPACE_FAILURE_CODE },
        )
    }

    companion object {
        const val LOW_SPACE_FAILURE_CODE = "STORE_LOW_SPACE"
    }
}
