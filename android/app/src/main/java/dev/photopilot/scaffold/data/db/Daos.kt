package dev.photopilot.scaffold.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import dev.photopilot.scaffold.data.logic.FrameSelectionLogic
import dev.photopilot.scaffold.data.logic.FrameSelectionValues

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceEntity)

    @Update
    suspend fun update(entity: SourceEntity)

    @Delete
    suspend fun delete(entity: SourceEntity)

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun getById(id: String): SourceEntity?

    @Query("SELECT * FROM source")
    suspend fun getAll(): List<SourceEntity>

    @Query(
        "SELECT s.* FROM source s INNER JOIN source_asset sa ON sa.source_id = s.id " +
            "INNER JOIN asset_origin ao ON ao.source_asset_id = sa.id " +
            "WHERE ao.asset_id = :assetId LIMIT 1",
    )
    suspend fun sourceForAsset(assetId: String): SourceEntity?
}

@Dao
interface SourceAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceAssetEntity)

    @Update
    suspend fun update(entity: SourceAssetEntity)

    @Delete
    suspend fun delete(entity: SourceAssetEntity)

    @Query("SELECT * FROM source_asset WHERE id = :id")
    suspend fun getById(id: String): SourceAssetEntity?

    @Query("SELECT * FROM source_asset WHERE source_id = :sourceId")
    suspend fun getBySource(sourceId: String): List<SourceAssetEntity>

    @Query("SELECT * FROM source_asset WHERE source_id = :sourceId AND remote_key = :remoteKey")
    suspend fun getByRemoteKey(sourceId: String, remoteKey: String): SourceAssetEntity?

    @Query(
        "SELECT DISTINCT sa.* FROM source_asset sa " +
            "INNER JOIN import_item ii ON ii.source_asset_id = sa.id " +
            "INNER JOIN import_job ij ON ij.id = ii.job_id " +
            "WHERE ii.state NOT IN ('imported','failed','canceled','skipped') " +
            "AND ij.state IN ('running','paused','paused_auto') " +
            "AND NOT EXISTS (SELECT 1 FROM asset_origin ao WHERE ao.source_asset_id = sa.id)",
    )
    suspend fun pendingForActiveJobs(): List<SourceAssetEntity>
}

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AssetEntity)

    @Update
    suspend fun update(entity: AssetEntity)

    @Delete
    suspend fun delete(entity: AssetEntity)

    @Query("SELECT * FROM asset WHERE id = :id")
    suspend fun getById(id: String): AssetEntity?

    @Query("SELECT * FROM asset WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<AssetEntity>

    @Query("SELECT * FROM asset WHERE content_hash = :contentHash")
    suspend fun getByContentHash(contentHash: String): List<AssetEntity>

    @Query("SELECT * FROM asset WHERE readiness = :readiness")
    suspend fun getByReadiness(readiness: AssetReadiness): List<AssetEntity>

    @Query("SELECT * FROM asset ORDER BY captured_at IS NULL, captured_at, id")
    suspend fun playbackOrder(): List<AssetEntity>

    @Query("SELECT * FROM asset ORDER BY COALESCE(captured_at, imported_at), id")
    suspend fun timelineOrder(): List<AssetEntity>

    @Query(
        "SELECT * FROM asset WHERE readiness != 'deleted' AND deleted_at IS NULL " +
            "AND privacy_excluded = 0 " +
            "ORDER BY COALESCE(captured_at, imported_at) DESC, id LIMIT :limit OFFSET :offset",
    )
    suspend fun browsePage(limit: Int, offset: Int): List<AssetEntity>

    @Query(
        "SELECT a.* FROM asset a INNER JOIN collection_asset ca ON a.id = ca.asset_id " +
            "WHERE ca.collection_id = :collectionId AND a.readiness != 'deleted' " +
            "AND a.deleted_at IS NULL AND a.privacy_excluded = 0 " +
            "ORDER BY COALESCE(a.captured_at, a.imported_at) DESC, a.id LIMIT :limit OFFSET :offset",
    )
    suspend fun browseCollectionPage(collectionId: String, limit: Int, offset: Int): List<AssetEntity>

    @RawQuery(observedEntities = [AssetEntity::class])
    suspend fun rawAssets(query: SupportSQLiteQuery): List<AssetEntity>
}

@Dao
interface AssetOriginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AssetOriginEntity)

    @Update
    suspend fun update(entity: AssetOriginEntity)

    @Delete
    suspend fun delete(entity: AssetOriginEntity)

    @Query("SELECT * FROM asset_origin WHERE asset_id = :assetId")
    suspend fun getByAsset(assetId: String): List<AssetOriginEntity>

    @Query("SELECT * FROM asset_origin WHERE source_asset_id IN (:sourceAssetIds)")
    suspend fun getBySourceAssets(sourceAssetIds: List<String>): List<AssetOriginEntity>

    @Query("SELECT * FROM asset_origin WHERE asset_id = :assetId AND source_asset_id = :sourceAssetId")
    suspend fun getByAssetAndSourceAsset(assetId: String, sourceAssetId: String): AssetOriginEntity?

    @Query("UPDATE asset_origin SET origin_state = 'detached', updated_at = :now WHERE source_asset_id IN (SELECT id FROM source_asset WHERE source_id = :sourceId)")
    suspend fun markDetachedBySource(sourceId: String, now: Long)

    @Query(
        "SELECT DISTINCT asset_id FROM asset_origin " +
            "WHERE policy_state = 'allowed' AND origin_state = 'active' AND asset_id IN (:assetIds)",
    )
    suspend fun allowedAssetIds(assetIds: List<String>): List<String>
}

@Dao
interface CollectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CollectionEntity)

    @Update
    suspend fun update(entity: CollectionEntity)

    @Delete
    suspend fun delete(entity: CollectionEntity)

    @Query("SELECT * FROM collection WHERE id = :id")
    suspend fun getById(id: String): CollectionEntity?

    @Query("SELECT * FROM collection ORDER BY sort_key DESC, id ASC")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collection WHERE source_id = :sourceId AND rel_path = :relPath")
    suspend fun getBySourceAndPath(sourceId: String, relPath: String): CollectionEntity?

    @Query("SELECT * FROM collection WHERE source_id = :sourceId")
    suspend fun getBySource(sourceId: String): List<CollectionEntity>
}

@Dao
interface CollectionAssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CollectionAssetEntity)

    @Delete
    suspend fun delete(entity: CollectionAssetEntity)

    @Query("DELETE FROM collection_asset WHERE collection_id = :collectionId AND asset_id = :assetId")
    suspend fun deleteByPair(collectionId: String, assetId: String)

    @Query("SELECT * FROM collection_asset WHERE collection_id = :collectionId")
    suspend fun getByCollection(collectionId: String): List<CollectionAssetEntity>

    @Query("SELECT * FROM collection_asset WHERE collection_id IN (:collectionIds)")
    suspend fun getByCollections(collectionIds: List<String>): List<CollectionAssetEntity>

    @Query("SELECT * FROM collection_asset WHERE asset_id = :assetId")
    suspend fun getByAsset(assetId: String): List<CollectionAssetEntity>

    @Query(
        "SELECT ca.asset_id AS asset_id, sa.remote_key AS remote_key FROM collection_asset ca " +
            "INNER JOIN asset_origin ao ON ao.asset_id = ca.asset_id " +
            "INNER JOIN source_asset sa ON sa.id = ao.source_asset_id " +
            "WHERE ca.collection_id = :collectionId",
    )
    suspend fun remoteKeysForCollection(collectionId: String): List<AssetRemoteKey>
}

data class AssetRemoteKey(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "remote_key") val remoteKey: String,
)

@Dao
interface FrameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FrameEntity)

    @Update
    suspend fun update(entity: FrameEntity)

    @Delete
    suspend fun delete(entity: FrameEntity)

    @Query("SELECT * FROM frame WHERE id = :id")
    suspend fun getById(id: String): FrameEntity?

    @Query("SELECT * FROM frame")
    suspend fun getAll(): List<FrameEntity>

    @Query("UPDATE frame SET selection_version = selection_version + 1, updated_at = :now WHERE id = :frameId")
    suspend fun bumpSelectionVersion(frameId: String, now: Long)
}

@Dao
interface FrameSelectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRaw(entity: FrameSelectionEntity)

    @Delete
    suspend fun delete(entity: FrameSelectionEntity)

    @Query("SELECT * FROM frame_selection WHERE frame_id = :frameId")
    suspend fun getByFrame(frameId: String): List<FrameSelectionEntity>

    @Query("SELECT * FROM frame_selection WHERE frame_id = :frameId AND ref_key = :refKey")
    suspend fun getByRef(frameId: String, refKey: String): FrameSelectionEntity?

    @Query("SELECT * FROM frame_selection WHERE asset_id = :assetId")
    suspend fun getByAsset(assetId: String): List<FrameSelectionEntity>

    @Query(
        "UPDATE frame_selection SET asset_id = :assetId, source_asset_id = NULL, ref_key = :assetId, updated_at = :now " +
            "WHERE source_asset_id = :sourceAssetId",
    )
    suspend fun repointToAsset(sourceAssetId: String, assetId: String, now: Long)

    @Query("DELETE FROM frame_selection WHERE frame_id = :frameId AND ref_key = :refKey")
    suspend fun deleteByRef(frameId: String, refKey: String)

    @Query(
        "SELECT a.* FROM asset a INNER JOIN frame_selection fs ON a.id = fs.asset_id " +
            "WHERE fs.frame_id = :frameId AND fs.mode = 'include' " +
            "AND a.readiness != 'deleted' AND a.deleted_at IS NULL AND a.privacy_excluded = 0 " +
            "AND a.id NOT IN (" +
            "SELECT asset_id FROM frame_selection " +
            "WHERE frame_id = :frameId AND mode = 'exclude' AND asset_id IS NOT NULL" +
            ") ORDER BY a.id",
    )
    suspend fun candidateAssets(frameId: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM frame_selection WHERE asset_id = :assetId")
    suspend fun countForAsset(assetId: String): Long

    @Query(
        "SELECT a.* FROM asset a INNER JOIN frame_selection fs ON a.id = fs.asset_id " +
            "WHERE fs.frame_id = :frameId AND fs.mode = 'include' " +
            "AND a.readiness = 'ready' AND a.deleted_at IS NULL AND a.privacy_excluded = 0 " +
            "AND a.id NOT IN (" +
            "SELECT asset_id FROM frame_selection " +
            "WHERE frame_id = :frameId AND mode = 'exclude' AND asset_id IS NOT NULL" +
            ") AND EXISTS (" +
            "SELECT 1 FROM asset_origin o " +
            "WHERE o.asset_id = a.id AND o.policy_state = 'allowed' AND o.origin_state = 'active'" +
            ") ORDER BY a.id",
    )
    suspend fun playableAssets(frameId: String): List<AssetEntity>

    @Query(
        "SELECT sa.* FROM source_asset sa INNER JOIN frame_selection fs ON sa.id = fs.source_asset_id " +
            "WHERE fs.frame_id = :frameId AND fs.mode = 'include' ORDER BY sa.id",
    )
    suspend fun pendingIncludedSourceAssets(frameId: String): List<SourceAssetEntity>

    suspend fun upsertResolved(
        frameId: String,
        assetId: String?,
        sourceAssetId: String?,
        mode: FrameSelectionMode,
        manualOrder: Long?,
        now: Long,
    ) {
        val key = FrameSelectionLogic.refKey(assetId, sourceAssetId)
        upsertRaw(
            FrameSelectionEntity(
                frameId = frameId,
                assetId = assetId,
                sourceAssetId = sourceAssetId,
                mode = mode,
                manualOrder = manualOrder,
                addedAt = now,
                refKey = key,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun upsertResolved(values: FrameSelectionValues, now: Long) {
        upsertResolved(
            frameId = values.frameId,
            assetId = values.assetId,
            sourceAssetId = values.sourceAssetId,
            mode = enumFromWire(values.mode),
            manualOrder = values.manualOrder,
            now = values.addedAt,
        )
    }
}

@Dao
interface FrameDirectoryRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FrameDirectoryRuleEntity)

    @Delete
    suspend fun delete(entity: FrameDirectoryRuleEntity)

    @Query("SELECT * FROM frame_directory_rule WHERE frame_id = :frameId")
    suspend fun getByFrame(frameId: String): List<FrameDirectoryRuleEntity>
}

@Dao
interface ImportJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportJobEntity)

    @Update
    suspend fun update(entity: ImportJobEntity)

    @Delete
    suspend fun delete(entity: ImportJobEntity)

    @Query("SELECT * FROM import_job WHERE id = :id")
    suspend fun getById(id: String): ImportJobEntity?

    @Query("SELECT * FROM import_job WHERE state = :state")
    suspend fun getByState(state: ImportJobState): List<ImportJobEntity>

    @Query("SELECT * FROM import_job WHERE source_id = :sourceId")
    suspend fun getBySource(sourceId: String): List<ImportJobEntity>

    @Query("SELECT * FROM import_job ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(): ImportJobEntity?
}

@Dao
interface ImportItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportItemEntity)

    @Update
    suspend fun update(entity: ImportItemEntity)

    @Delete
    suspend fun delete(entity: ImportItemEntity)

    @Query("SELECT * FROM import_item WHERE id = :id")
    suspend fun getById(id: String): ImportItemEntity?

    @Query("SELECT * FROM import_item WHERE job_id = :jobId")
    suspend fun getByJob(jobId: String): List<ImportItemEntity>

    @Query("SELECT * FROM import_item WHERE job_id = :jobId AND state = :state")
    suspend fun getByJobAndState(jobId: String, state: ImportItemState): List<ImportItemEntity>

    @Query(
        "SELECT * FROM import_item WHERE retry_at IS NOT NULL AND retry_at <= :nowMillis " +
            "AND state = 'failed' ORDER BY retry_at",
    )
    suspend fun dueForRetry(nowMillis: Long): List<ImportItemEntity>

    @Query("SELECT state, COUNT(*) AS count FROM import_item WHERE job_id = :jobId GROUP BY state")
    suspend fun stateCounts(jobId: String): List<ItemStateCount>

    @Query(
        "UPDATE import_item SET state = :state, retry_at = :retryAt, failure_code = :failureCode, " +
            "retry_count = 0, updated_at = :now WHERE id = :id",
    )
    suspend fun transition(
        id: String,
        state: ImportItemState,
        retryAt: Long?,
        failureCode: String?,
        now: Long,
    )
}

data class ItemStateCount(
    val state: ImportItemState,
    val count: Long,
)

@Dao
interface LocalTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalTombstoneEntity)

    @Delete
    suspend fun delete(entity: LocalTombstoneEntity)

    @Query("SELECT * FROM local_tombstone WHERE id = :id")
    suspend fun getById(id: String): LocalTombstoneEntity?

    @Query("DELETE FROM local_tombstone WHERE reason IN (:reasons)")
    suspend fun deleteByReasons(reasons: List<String>)

    @Query("SELECT * FROM local_tombstone WHERE content_hash = :contentHash")
    suspend fun getByContentHash(contentHash: String): List<LocalTombstoneEntity>

    @Query("SELECT * FROM local_tombstone WHERE source_fingerprint = :fingerprint AND remote_id = :remoteId")
    suspend fun getByFingerprintAndRemoteId(fingerprint: String, remoteId: String): List<LocalTombstoneEntity>

    @Query(
        "SELECT * FROM local_tombstone WHERE source_fingerprint = :fingerprint " +
            "AND byte_size = :byteSize AND head64k_hash = :head64kHash",
    )
    suspend fun getByFingerprintSizeHead(
        fingerprint: String,
        byteSize: Long,
        head64kHash: String,
    ): List<LocalTombstoneEntity>

    @Query(
        "SELECT * FROM local_tombstone WHERE " +
            "(:contentHash IS NOT NULL AND content_hash = :contentHash) " +
            "OR (:fingerprint IS NOT NULL AND :remoteId IS NOT NULL " +
            "AND source_fingerprint = :fingerprint AND remote_id = :remoteId) " +
            "OR (:fingerprint IS NOT NULL AND :byteSize IS NOT NULL AND :head64kHash IS NOT NULL " +
            "AND source_fingerprint = :fingerprint AND byte_size = :byteSize " +
            "AND head64k_hash = :head64kHash)",
    )
    suspend fun matchAnyTier(
        contentHash: String?,
        fingerprint: String?,
        remoteId: String?,
        byteSize: Long?,
        head64kHash: String?,
    ): List<LocalTombstoneEntity>
}

@Dao
interface PlaybackSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackSnapshotEntity)

    @Delete
    suspend fun delete(entity: PlaybackSnapshotEntity)

    @Query("SELECT * FROM playback_snapshot WHERE frame_id = :frameId")
    suspend fun getByFrame(frameId: String): PlaybackSnapshotEntity?
}

@Dao
interface WeatherSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeatherSnapshotEntity)

    @Query("SELECT * FROM weather_snapshot WHERE place_id = :placeId")
    suspend fun get(placeId: String): WeatherSnapshotEntity?
}

@Dao
interface SettingsKvDao {    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingsKvEntity)

    @Delete
    suspend fun delete(entity: SettingsKvEntity)

    @Query("SELECT * FROM settings_kv WHERE key = :key")
    suspend fun get(key: String): SettingsKvEntity?
}

@Dao
interface SchemaMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SchemaMetaEntity)

    @Query("SELECT * FROM schema_meta WHERE key = :key")
    suspend fun get(key: String): SchemaMetaEntity?
}

@Dao
interface HolidayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HolidayScheduleEntity)

    @Update
    suspend fun update(entity: HolidayScheduleEntity)

    @Delete
    suspend fun delete(entity: HolidayScheduleEntity)

    @Query("SELECT * FROM holiday_schedule WHERE id = :id")
    suspend fun getById(id: String): HolidayScheduleEntity?

    @Query("SELECT * FROM holiday_schedule ORDER BY position ASC, id ASC")
    suspend fun getAll(): List<HolidayScheduleEntity>

    @Query("SELECT * FROM holiday_schedule WHERE enabled = 1 ORDER BY position ASC, id ASC")
    suspend fun getEnabled(): List<HolidayScheduleEntity>

    @Query("SELECT COUNT(*) FROM holiday_schedule")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM holiday_schedule")
    suspend fun maxPosition(): Long
}
