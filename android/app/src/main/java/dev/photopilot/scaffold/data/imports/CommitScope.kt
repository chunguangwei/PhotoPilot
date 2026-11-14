package dev.photopilot.scaffold.data.imports

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetOriginEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CollectionAssetEntity
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.ImportItemEntity
import dev.photopilot.scaffold.data.db.ImportJobEntity
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.SettingsKvEntity
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.logic.LibraryVersionLogic
import dev.photopilot.scaffold.data.logic.TombstoneIdentity
import dev.photopilot.scaffold.data.logic.TombstoneMatchTier
import dev.photopilot.scaffold.data.logic.TombstoneMatcher
import dev.photopilot.scaffold.data.repo.DataValidators

interface CommitScope {

    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun source(sourceId: String): SourceEntity?
    suspend fun job(jobId: String): ImportJobEntity?
    suspend fun insertJob(entity: ImportJobEntity)
    suspend fun updateJob(entity: ImportJobEntity)
    suspend fun item(itemId: String): ImportItemEntity?
    suspend fun insertItem(entity: ImportItemEntity)
    suspend fun updateItem(entity: ImportItemEntity)
    suspend fun itemsForJob(jobId: String): List<ImportItemEntity>

    suspend fun sourceAsset(id: String): SourceAssetEntity?
    suspend fun findSourceAsset(sourceId: String, remoteKey: String): SourceAssetEntity?
    suspend fun upsertSourceAsset(entity: SourceAssetEntity)

    suspend fun existingAssetByHash(contentHash: String): AssetEntity?

    suspend fun markOriginalReady(assetId: String, contentHash: String, now: Long): Boolean {
        val current = assetById(assetId) ?: return false
        if (current.contentHash != contentHash || current.privacyExcluded || current.deletedAt != null || current.readiness == AssetReadiness.DELETED) return false
        insertAsset(current.copy(readiness = AssetReadiness.READY, failureCode = null, updatedAt = now))
        return true
    }
    suspend fun insertAsset(entity: AssetEntity)
    suspend fun findOrigin(assetId: String, sourceAssetId: String): AssetOriginEntity?
    suspend fun insertOrigin(entity: AssetOriginEntity)

    suspend fun frameScopeValid(scopeJson: String): Boolean = true
    suspend fun recordProvenance(assetId: String, sourceAsset: SourceAssetEntity, contentHash: String, epoch: Long, now: Long) = Unit

    suspend fun repointSelections(sourceAssetId: String, assetId: String, now: Long)

    suspend fun upsertCollection(entity: CollectionEntity)

    suspend fun upsertCollectionAsset(entity: CollectionAssetEntity)

    suspend fun sourceAssetsForSource(sourceId: String): List<SourceAssetEntity>

    suspend fun assetById(assetId: String): AssetEntity?

    suspend fun collectionByPath(sourceId: String, relPath: String): CollectionEntity?

    suspend fun collectionsForSource(sourceId: String): List<CollectionEntity>

    suspend fun remoteKeysForCollection(collectionId: String): List<Pair<String, String>>

    suspend fun deleteCollectionAsset(collectionId: String, assetId: String)

    suspend fun originsForSourceAsset(sourceAssetId: String): List<AssetOriginEntity>

    suspend fun writeTombstone(entity: LocalTombstoneEntity)

    suspend fun softDeleteAsset(assetId: String, now: Long)

    suspend fun tombstoneTierMatch(candidate: TombstoneIdentity): TombstoneMatchTier

    suspend fun bumpLibraryVersion(now: Long): Long
}

class RoomCommitScope(private val db: PhotoPilotDatabase) : CommitScope {

    override suspend fun <T> transaction(block: suspend () -> T): T =
        db.withTransaction { block() }

    override suspend fun source(sourceId: String): SourceEntity? = db.sourceDao().getById(sourceId)

    override suspend fun job(jobId: String): ImportJobEntity? = db.importJobDao().getById(jobId)

    override suspend fun insertJob(entity: ImportJobEntity) = db.importJobDao().upsert(entity)

    override suspend fun updateJob(entity: ImportJobEntity) = db.importJobDao().update(entity)

    override suspend fun item(itemId: String): ImportItemEntity? = db.importItemDao().getById(itemId)

    override suspend fun insertItem(entity: ImportItemEntity) = db.importItemDao().upsert(entity)

    override suspend fun updateItem(entity: ImportItemEntity) = db.importItemDao().update(entity)

    override suspend fun itemsForJob(jobId: String): List<ImportItemEntity> =
        db.importItemDao().getByJob(jobId)

    override suspend fun sourceAsset(id: String): SourceAssetEntity? = db.sourceAssetDao().getById(id)

    override suspend fun findSourceAsset(sourceId: String, remoteKey: String): SourceAssetEntity? =
        db.sourceAssetDao().getByRemoteKey(sourceId, remoteKey)

    override suspend fun upsertSourceAsset(entity: SourceAssetEntity) =
        db.sourceAssetDao().upsert(entity)

    override suspend fun existingAssetByHash(contentHash: String): AssetEntity? =
        db.assetDao().getByContentHash(contentHash)
            .firstOrNull { it.readiness != AssetReadiness.DELETED }

    override suspend fun insertAsset(entity: AssetEntity) {
        DataValidators.requireValidAsset(entity)
        db.assetDao().upsert(entity)
    }

    override suspend fun markOriginalReady(assetId: String, contentHash: String, now: Long): Boolean =
        db.assetDao().markOriginalReady(assetId, contentHash, now) == 1

    override suspend fun findOrigin(assetId: String, sourceAssetId: String): AssetOriginEntity? =
        db.assetOriginDao().getByAssetAndSourceAsset(assetId, sourceAssetId)

    override suspend fun insertOrigin(entity: AssetOriginEntity) = db.assetOriginDao().upsert(entity)

    override suspend fun repointSelections(sourceAssetId: String, assetId: String, now: Long) {
        for (frame in db.frameDao().getAll()) {
            val mode = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(frame.settingsJson)["mediaStorageMode"]
            if (mode == "local" || mode == "remote") continue
            val selection = db.frameSelectionDao().getByFrame(frame.id).firstOrNull { it.sourceAssetId == sourceAssetId } ?: continue
            db.frameSelectionDao().delete(selection)
            val existing = db.frameSelectionDao().getByRef(frame.id, assetId)
            if (existing == null || selection.mode == dev.photopilot.scaffold.data.db.FrameSelectionMode.EXCLUDE) {
                db.frameSelectionDao().upsertRaw(selection.copy(assetId = assetId, sourceAssetId = null, refKey = assetId, updatedAt = now))
            }
        }
    }

    override suspend fun frameScopeValid(scopeJson: String): Boolean {
        val scope = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(scopeJson)
        val frameId = scope["frameId"] as? String ?: return true
        val frame = db.frameDao().getById(frameId) ?: return false
        if (dev.photopilot.scaffold.data.logic.FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode != "local" || frame.selectionVersion != (scope["selectionVersion"] as? Number)?.toLong()) return false
        val revisions = scope["sourceRevisions"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return revisions.all { (id, revision) -> db.sourceAssetDao().getById(id as? String ?: "")?.revision == revision }
    }

    override suspend fun recordProvenance(assetId: String, sourceAsset: SourceAssetEntity, contentHash: String, epoch: Long, now: Long) {
        db.settingsKvDao().upsert(SettingsKvEntity(
            dev.photopilot.scaffold.data.remote.RemoteMediaRepository.provenanceKey(assetId, sourceAsset.id),
            dev.photopilot.scaffold.data.logic.JsonMini.toJson(mapOf("version" to 1, "epoch" to epoch, "revision" to sourceAsset.revision, "contentHash" to contentHash)), now, now))
    }

    override suspend fun upsertCollection(entity: CollectionEntity) =
        db.collectionDao().upsert(entity)

    override suspend fun upsertCollectionAsset(entity: CollectionAssetEntity) =
        db.collectionAssetDao().upsert(entity)

    override suspend fun sourceAssetsForSource(sourceId: String): List<SourceAssetEntity> =
        db.sourceAssetDao().getBySource(sourceId)

    override suspend fun assetById(assetId: String): AssetEntity? =
        db.assetDao().getById(assetId)

    override suspend fun collectionByPath(sourceId: String, relPath: String): CollectionEntity? =
        db.collectionDao().getBySourceAndPath(sourceId, relPath)

    override suspend fun collectionsForSource(sourceId: String): List<CollectionEntity> =
        db.collectionDao().getBySource(sourceId)

    override suspend fun remoteKeysForCollection(collectionId: String): List<Pair<String, String>> =
        db.collectionAssetDao().remoteKeysForCollection(collectionId).map { it.assetId to it.remoteKey }

    override suspend fun deleteCollectionAsset(collectionId: String, assetId: String) =
        db.collectionAssetDao().deleteByPair(collectionId, assetId)

    override suspend fun originsForSourceAsset(sourceAssetId: String): List<AssetOriginEntity> =
        db.assetOriginDao().getBySourceAssets(listOf(sourceAssetId))

    override suspend fun writeTombstone(entity: LocalTombstoneEntity) =
        db.localTombstoneDao().upsert(entity)

    override suspend fun softDeleteAsset(assetId: String, now: Long) {
        val asset = db.assetDao().getById(assetId) ?: return
        db.assetDao().update(
            asset.copy(
                readiness = AssetReadiness.DELETED,
                deletedAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun tombstoneTierMatch(candidate: TombstoneIdentity): TombstoneMatchTier {
        val candidates = db.localTombstoneDao().matchAnyTier(
            contentHash = candidate.contentHash,
            fingerprint = candidate.sourceFingerprint,
            remoteId = candidate.remoteId,
            byteSize = candidate.byteSize,
            head64kHash = candidate.head64kHash,
        )
        if (candidates.isEmpty()) return TombstoneMatchTier.NONE
        val best = TombstoneMatcher.bestMatch(candidate, candidates.map(::toIdentity))
        return best?.second ?: TombstoneMatchTier.NONE
    }

    override suspend fun bumpLibraryVersion(now: Long): Long {
        val dao = db.settingsKvDao()
        val next = LibraryVersionLogic.nextFromStored(dao.get(LibraryVersionLogic.KEY)?.valueJson)
        dao.upsert(
            SettingsKvEntity(
                key = LibraryVersionLogic.KEY,
                valueJson = LibraryVersionLogic.storedJson(next),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return next
    }

    private fun toIdentity(entity: LocalTombstoneEntity): TombstoneIdentity =
        TombstoneIdentity(
            contentHash = entity.contentHash,
            sourceFingerprint = entity.sourceFingerprint,
            remoteId = entity.remoteId,
            byteSize = entity.byteSize,
            head64kHash = entity.head64kHash,
        )
}
