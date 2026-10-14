package dev.photopilot.scaffold.data.repo

import android.content.Context
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetOriginEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.DatabaseFactory
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.logic.FrameSelectionValues
import dev.photopilot.scaffold.data.logic.SourceFingerprints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AssetRepository(
    private val db: PhotoPilotDatabase,
    private val libraryVersion: LibraryVersionRepository,
) {

    companion object {
        const val REASON_USER_DELETE = "user_delete"
        const val REASON_PRIVACY_HIDE = "privacy_hide"

        val USER_DELETE_REASONS = listOf(REASON_USER_DELETE)

        fun create(context: Context): AssetRepository {
            val db = DatabaseFactory.builder(context).build()
            return AssetRepository(db, LibraryVersionRepository(db.settingsKvDao()))
        }
    }

    suspend fun assetById(id: String): AssetEntity? = withIo {
        db.assetDao().getById(id)
    }

    suspend fun candidateAssets(frameId: String): List<AssetEntity> = withIo {
        db.frameSelectionDao().candidateAssets(frameId)
    }

    suspend fun playableAssets(frameId: String): List<AssetEntity> = withIo {
        db.frameSelectionDao().playableAssets(frameId)
    }

    suspend fun pendingIncludedSourceAssets(frameId: String): List<SourceAssetEntity> = withIo {
        db.frameSelectionDao().pendingIncludedSourceAssets(frameId)
    }

    suspend fun sourceAssetById(id: String): SourceAssetEntity? = withIo {
        db.sourceAssetDao().getById(id)
    }

    suspend fun selections(frameId: String) = withIo {
        db.frameSelectionDao().getByFrame(frameId)
    }

    suspend fun insertAsset(entity: AssetEntity): Unit = withIo {
        DataValidators.requireValidAsset(entity)
        db.assetDao().upsert(entity)
    }

    suspend fun markAssetReady(assetId: String, now: Long): Unit = withIo {
        val current = db.assetDao().getById(assetId) ?: return@withIo
        db.assetDao().update(
            current.copy(readiness = AssetReadiness.READY, failureCode = null, updatedAt = now),
        )
        libraryVersion.bump(now)
    }

    suspend fun markAssetFailed(assetId: String, failureCode: String, now: Long): Unit = withIo {
        val current = db.assetDao().getById(assetId) ?: return@withIo
        db.assetDao().update(
            current.copy(
                readiness = AssetReadiness.FAILED,
                failureCode = failureCode,
                updatedAt = now,
            ),
        )
        libraryVersion.bump(now)
    }

    suspend fun deleteAssetWithTombstone(
        assetId: String,
        reason: String,
        now: Long,
        head64kHash: String? = null,
    ): Unit = withIo {
        db.withTransaction {
            val asset = db.assetDao().getById(assetId)
                ?: throw IllegalArgumentException("asset not found")
            require(asset.readiness != AssetReadiness.DELETED) { "asset already deleted" }
            writeTombstone(asset, reason, now, head64kHash)
            db.assetDao().update(
                asset.copy(
                    readiness = AssetReadiness.DELETED,
                    deletedAt = now,
                    updatedAt = now,
                ),
            )
            libraryVersion.bump(now)
        }
    }

    suspend fun restoreDeletedAsset(assetId: String, now: Long): Unit = withIo {
        db.withTransaction {
            val asset = db.assetDao().getById(assetId) ?: return@withTransaction
            if (asset.deletedAt == null) return@withTransaction
            db.localTombstoneDao().getById("tomb-$assetId")?.let {
                db.localTombstoneDao().delete(it)
            }
            db.assetDao().update(
                asset.copy(
                    readiness = AssetReadiness.READY,
                    deletedAt = null,
                    updatedAt = now,
                ),
            )
            libraryVersion.bump(now)
        }
    }

    suspend fun hideAssetGlobally(assetId: String, reason: String, now: Long): Unit = withIo {
        db.withTransaction {
            val asset = db.assetDao().getById(assetId)
                ?: throw IllegalArgumentException("asset not found")
            writeTombstone(asset, reason, now, head64kHash = null)
            db.assetDao().update(
                asset.copy(privacyExcluded = true, updatedAt = now),
            )
            libraryVersion.bump(now)
        }
    }

    suspend fun deleteTombstonesByReasons(reasons: List<String>): Unit = withIo {
        db.localTombstoneDao().deleteByReasons(reasons)
    }

    private suspend fun writeTombstone(
        asset: AssetEntity,
        reason: String,
        now: Long,
        head64kHash: String?,
    ) {
        val origins = db.assetOriginDao().getByAsset(asset.id)
        val (fingerprint, remoteId) = origins.firstNotNullOfOrNull { origin ->
            db.sourceAssetDao().getById(origin.sourceAssetId)?.let { sa ->
                db.sourceDao().getById(sa.sourceId)?.let { SourceFingerprints.of(it) to sa.remoteKey }
            }
        } ?: (null to null)
        db.localTombstoneDao().upsert(
            LocalTombstoneEntity(
                id = "tomb-${asset.id}",
                contentHash = asset.contentHash,
                sourceFingerprint = fingerprint,
                remoteId = remoteId,
                byteSize = asset.byteSize,
                head64kHash = head64kHash,
                reason = reason,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun upsertSelection(values: FrameSelectionValues): Unit = withIo {
        DataValidators.requireValidSelection(values)
        db.frameSelectionDao().upsertResolved(values, values.addedAt)
    }

    suspend fun removeSelection(frameId: String, refKey: String): Unit = withIo {
        db.frameSelectionDao().deleteByRef(frameId, refKey)
    }

    suspend fun originsForAsset(assetId: String): List<AssetOriginEntity> = withIo {
        db.assetOriginDao().getByAsset(assetId)
    }

    private suspend fun <T> withIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
