package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetOriginEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CollectionAssetEntity
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.FrameSelectionEntity
import dev.photopilot.scaffold.data.db.ImportItemEntity
import dev.photopilot.scaffold.data.db.ImportJobEntity
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.imports.CommitScope
import dev.photopilot.scaffold.data.logic.TombstoneIdentity
import dev.photopilot.scaffold.data.logic.TombstoneMatchTier
import dev.photopilot.scaffold.data.logic.TombstoneMatcher
import dev.photopilot.scaffold.data.repo.DataValidators

class FakeCommitScope : CommitScope {

    val sources = mutableMapOf<String, SourceEntity>()
    val jobs = mutableMapOf<String, ImportJobEntity>()
    val items = mutableMapOf<String, ImportItemEntity>()
    val sourceAssets = mutableMapOf<String, SourceAssetEntity>()
    val assets = mutableMapOf<String, AssetEntity>()
    val origins = mutableMapOf<Pair<String, String>, AssetOriginEntity>()
    val selections = mutableListOf<FrameSelectionEntity>()
    val collections = mutableMapOf<String, CollectionEntity>()
    val collectionAssets = mutableMapOf<Pair<String, String>, CollectionAssetEntity>()
    val tombstones = mutableListOf<LocalTombstoneEntity>()
    var libraryVersion = 0L

    override suspend fun <T> transaction(block: suspend () -> T): T = block()

    override suspend fun source(sourceId: String): SourceEntity? = sources[sourceId]

    override suspend fun job(jobId: String): ImportJobEntity? = jobs[jobId]

    override suspend fun insertJob(entity: ImportJobEntity) {
        jobs[entity.id] = entity
    }

    override suspend fun updateJob(entity: ImportJobEntity) {
        if (jobs.containsKey(entity.id)) {
            jobs[entity.id] = entity
        }
    }

    override suspend fun item(itemId: String): ImportItemEntity? = items[itemId]

    override suspend fun insertItem(entity: ImportItemEntity) {
        items[entity.id] = entity
    }

    override suspend fun updateItem(entity: ImportItemEntity) {
        if (items.containsKey(entity.id)) {
            items[entity.id] = entity
        }
    }

    override suspend fun itemsForJob(jobId: String): List<ImportItemEntity> =
        items.values.filter { it.jobId == jobId }.sortedBy { it.id }

    override suspend fun sourceAsset(id: String): SourceAssetEntity? = sourceAssets[id]

    override suspend fun findSourceAsset(sourceId: String, remoteKey: String): SourceAssetEntity? =
        sourceAssets.values.firstOrNull { it.sourceId == sourceId && it.remoteKey == remoteKey }

    override suspend fun upsertSourceAsset(entity: SourceAssetEntity) {
        sourceAssets[entity.id] = entity
    }

    var existingAssetByHashOverride: ((String) -> AssetEntity?)? = null
    var insertAssetHook: ((AssetEntity) -> Unit)? = null

    override suspend fun existingAssetByHash(contentHash: String): AssetEntity? =
        existingAssetByHashOverride?.invoke(contentHash)
            ?: assets.values.firstOrNull { it.contentHash == contentHash && it.readiness != AssetReadiness.DELETED }

    override suspend fun insertAsset(entity: AssetEntity) {
        DataValidators.requireValidAsset(entity)
        insertAssetHook?.invoke(entity)
        assets[entity.id] = entity
    }

    override suspend fun findOrigin(assetId: String, sourceAssetId: String): AssetOriginEntity? =
        origins[assetId to sourceAssetId]

    override suspend fun insertOrigin(entity: AssetOriginEntity) {
        origins[entity.assetId to entity.sourceAssetId] = entity
    }

    override suspend fun repointSelections(sourceAssetId: String, assetId: String, now: Long) {
        selections.replaceAll { sel ->
            if (sel.sourceAssetId == sourceAssetId) {
                sel.copy(assetId = assetId, sourceAssetId = null, refKey = assetId, updatedAt = now)
            } else {
                sel
            }
        }
    }

    override suspend fun upsertCollection(entity: CollectionEntity) {
        collections[entity.id] = entity
    }

    override suspend fun upsertCollectionAsset(entity: CollectionAssetEntity) {
        collectionAssets[entity.collectionId to entity.assetId] = entity
    }

    override suspend fun sourceAssetsForSource(sourceId: String): List<SourceAssetEntity> =
        sourceAssets.values.filter { it.sourceId == sourceId }.sortedBy { it.id }

    override suspend fun assetById(assetId: String): AssetEntity? = assets[assetId]

    override suspend fun collectionByPath(sourceId: String, relPath: String): CollectionEntity? =
        collections.values.firstOrNull { it.sourceId == sourceId && it.relPath == relPath }

    override suspend fun collectionsForSource(sourceId: String): List<CollectionEntity> =
        collections.values.filter { it.sourceId == sourceId }

    override suspend fun remoteKeysForCollection(collectionId: String): List<Pair<String, String>> {
        val members = collectionAssets.values.filter { it.collectionId == collectionId }
        return members.mapNotNull { member ->
            val origin = origins.values.firstOrNull { it.assetId == member.assetId }
            val sa = origin?.let { sourceAssets[it.sourceAssetId] }
            sa?.let { member.assetId to it.remoteKey }
        }
    }

    override suspend fun deleteCollectionAsset(collectionId: String, assetId: String) {
        collectionAssets.remove(collectionId to assetId)
    }

    override suspend fun originsForSourceAsset(sourceAssetId: String): List<AssetOriginEntity> =
        origins.values.filter { it.sourceAssetId == sourceAssetId }

    override suspend fun writeTombstone(entity: LocalTombstoneEntity) {
        tombstones.removeAll { it.id == entity.id }
        tombstones.add(entity)
    }

    override suspend fun softDeleteAsset(assetId: String, now: Long) {
        assets[assetId]?.let {
            assets[assetId] = it.copy(
                readiness = AssetReadiness.DELETED,
                deletedAt = now,
                updatedAt = now,
            )
        }
    }

    override suspend fun tombstoneTierMatch(candidate: TombstoneIdentity): TombstoneMatchTier {
        val identities = tombstones.map {
            TombstoneIdentity(
                contentHash = it.contentHash,
                sourceFingerprint = it.sourceFingerprint,
                remoteId = it.remoteId,
                byteSize = it.byteSize,
                head64kHash = it.head64kHash,
            )
        }
        return TombstoneMatcher.bestMatch(candidate, identities)?.second ?: TombstoneMatchTier.NONE
    }

    override suspend fun bumpLibraryVersion(now: Long): Long {
        libraryVersion += 1L
        return libraryVersion
    }

    fun seedSource(id: String = "source-1", epoch: Long = 1L): SourceEntity {
        val now = 1_700_000_000_000L
        val source = SourceEntity(
            id = id,
            type = dev.photopilot.scaffold.data.db.SourceType.LOCAL_FILES,
            displayName = "fixture",
            endpointKey = "local_files:fixture-root",
            credentialRef = null,
            accountKey = null,
            capabilityFlags = 0L,
            retentionPolicy = dev.photopilot.scaffold.data.db.RetentionPolicy.USER_MANAGED,
            status = dev.photopilot.scaffold.data.db.SourceStatus.ACTIVE,
            epoch = epoch,
            lastSyncAt = null,
            settingsJson = null,
            createdAt = now,
            updatedAt = now,
        )
        sources[id] = source
        return source
    }

    fun addTombstone(
        contentHash: String? = null,
        fingerprint: String? = null,
        remoteId: String? = null,
        byteSize: Long? = null,
        head64kHash: String? = null,
    ) {
        val now = 1L
        val entity = LocalTombstoneEntity(
            id = "tomb-${tombstones.size}",
            contentHash = contentHash,
            sourceFingerprint = fingerprint,
            remoteId = remoteId,
            byteSize = byteSize,
            head64kHash = head64kHash,
            reason = "user_delete",
            createdAt = now,
            updatedAt = now,
        )
        DataValidators.requireValidTombstone(entity)
        tombstones.add(entity)
    }

    fun addSelection(
        frameId: String,
        sourceAssetId: String? = null,
        assetId: String? = null,
        mode: dev.photopilot.scaffold.data.db.FrameSelectionMode = dev.photopilot.scaffold.data.db.FrameSelectionMode.INCLUDE,
        manualOrder: Long? = null,
    ) {
        val key = assetId ?: sourceAssetId ?: error("exactly one ref required")
        selections.add(
            FrameSelectionEntity(
                frameId = frameId,
                assetId = assetId,
                sourceAssetId = sourceAssetId,
                mode = mode,
                manualOrder = manualOrder,
                addedAt = 1L,
                refKey = key,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    fun jobState(jobId: String) = jobs.getValue(jobId).state

    fun jobCounts(jobId: String) = jobs.getValue(jobId).countsJson
}
