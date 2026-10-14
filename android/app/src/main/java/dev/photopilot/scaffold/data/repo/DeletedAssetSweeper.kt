package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.logic.DeletedSweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DeletedAssetSweeper(
    private val db: PhotoPilotDatabase,
    private val originalsDir: File,
    private val libraryVersion: LibraryVersionRepository,
) {

    suspend fun sweepExpired(now: Long): Int = withContext(Dispatchers.IO) {
        val expired = db.assetDao().getByReadiness(AssetReadiness.DELETED)
            .filter { asset ->
                asset.deletedAt?.let { DeletedSweep.isExpired(it, now) } == true
            }
        expired.forEach { asset ->
            File(originalsDir, asset.originalRelPath).delete()
            db.assetDao().delete(asset)
        }
        if (expired.isNotEmpty()) {
            libraryVersion.bump(now)
        }
        expired.size
    }
}
