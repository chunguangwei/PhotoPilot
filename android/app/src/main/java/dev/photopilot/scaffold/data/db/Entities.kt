package dev.photopilot.scaffold.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: SourceType,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "endpoint_key") val endpointKey: String?,
    @ColumnInfo(name = "credential_ref") val credentialRef: String?,
    @ColumnInfo(name = "account_key") val accountKey: String?,
    @ColumnInfo(name = "capability_flags") val capabilityFlags: Long,
    @ColumnInfo(name = "retention_policy") val retentionPolicy: RetentionPolicy,
    @ColumnInfo(name = "status") val status: SourceStatus,
    @ColumnInfo(name = "epoch", defaultValue = "1") val epoch: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
    @ColumnInfo(name = "settings_json") val settingsJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "source_asset",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "last_seen_at"]),
        Index(value = ["stable_id"]),
        Index(value = ["source_id", "remote_key"], unique = true),
    ],
)
data class SourceAssetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "remote_key") val remoteKey: String,
    @ColumnInfo(name = "stable_id") val stableId: String?,
    @ColumnInfo(name = "revision") val revision: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long?,
    @ColumnInfo(name = "remote_taken_at") val remoteTakenAt: Long?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "missing_scan_count", defaultValue = "0") val missingScanCount: Long,
    @ColumnInfo(name = "access_state") val accessState: AccessState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "asset",
    indices = [
        Index(value = ["captured_at", "id"]),
        Index(value = ["readiness"]),
        Index(value = ["orientation_class"]),
        Index(value = ["original_file_name"]),
    ],
)
data class AssetEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "format") val format: AssetFormat,
    @ColumnInfo(name = "pixel_width") val pixelWidth: Long,
    @ColumnInfo(name = "pixel_height") val pixelHeight: Long,
    @ColumnInfo(name = "exif_orientation") val exifOrientation: Long,
    @ColumnInfo(name = "orientation_class") val orientationClass: OrientationClass,
    @ColumnInfo(name = "captured_at") val capturedAt: Long?,
    @ColumnInfo(name = "captured_at_source") val capturedAtSource: CapturedAtSource,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "original_rel_path") val originalRelPath: String,
    @ColumnInfo(name = "original_file_name") val originalFileName: String?,
    @ColumnInfo(name = "readiness") val readiness: AssetReadiness,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "privacy_excluded", defaultValue = "0") val privacyExcluded: Boolean,
    @ColumnInfo(name = "edge_color") val edgeColor: String?,
    @ColumnInfo(name = "media_kind", defaultValue = "image") val mediaKind: MediaKind = MediaKind.IMAGE,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "asset_origin",
    primaryKeys = ["asset_id", "source_asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class AssetOriginEntity(
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "source_asset_id") val sourceAssetId: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "policy_state") val policyState: PolicyState,
    @ColumnInfo(name = "origin_state", defaultValue = "'active'") val originState: OriginState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "collection",
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "rel_path"], unique = true),
    ],
)
data class CollectionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "source_id") val sourceId: String?,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "rel_path") val relPath: String?,
    @ColumnInfo(name = "type") val type: CollectionType,
    @ColumnInfo(name = "sort_key") val sortKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "collection_asset",
    primaryKeys = ["collection_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["asset_id"]),
    ],
)
data class CollectionAssetEntity(
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "asset_id") val assetId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "frame",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["cover_asset_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class FrameEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cover_asset_id") val coverAssetId: String?,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    @ColumnInfo(name = "selection_version", defaultValue = "0") val selectionVersion: Long,
    @ColumnInfo(name = "order_mode") val orderMode: FrameOrderMode,
    @ColumnInfo(name = "random_seed", defaultValue = "0") val randomSeed: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "frame_selection",
    primaryKeys = ["frame_id", "ref_key"],
    foreignKeys = [
        ForeignKey(
            entity = FrameEntity::class,
            parentColumns = ["id"],
            childColumns = ["frame_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FrameSelectionEntity(
    @ColumnInfo(name = "frame_id") val frameId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "source_asset_id") val sourceAssetId: String?,
    @ColumnInfo(name = "mode") val mode: FrameSelectionMode,
    @ColumnInfo(name = "manual_order") val manualOrder: Long?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "ref_key") val refKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "frame_directory_rule",
    primaryKeys = ["frame_id", "collection_id"],
    foreignKeys = [
        ForeignKey(
            entity = FrameEntity::class,
            parentColumns = ["id"],
            childColumns = ["frame_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collection_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FrameDirectoryRuleEntity(
    @ColumnInfo(name = "frame_id") val frameId: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "recursive", defaultValue = "0") val recursive: Boolean,
    @ColumnInfo(name = "include_future", defaultValue = "0") val includeFuture: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "import_job",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ImportJobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "epoch_at_create") val epochAtCreate: Long,
    @ColumnInfo(name = "cursor") val cursor: String?,
    @ColumnInfo(name = "total_known") val totalKnown: Long?,
    @ColumnInfo(name = "counts_json", defaultValue = "'{}'") val countsJson: String,
    @ColumnInfo(name = "state") val state: ImportJobState,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "import_item",
    foreignKeys = [
        ForeignKey(
            entity = ImportJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_asset_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["job_id", "state"]),
        Index(value = ["retry_at"]),
    ],
)
data class ImportItemEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "job_id") val jobId: String,
    @ColumnInfo(name = "source_asset_id") val sourceAssetId: String,
    @ColumnInfo(name = "state") val state: ImportItemState,
    @ColumnInfo(name = "temp_rel_path") val tempRelPath: String?,
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Long,
    @ColumnInfo(name = "retry_at") val retryAt: Long?,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "bytes_done", defaultValue = "0") val bytesDone: Long,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "local_tombstone")
data class LocalTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "content_hash") val contentHash: String?,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String?,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "byte_size") val byteSize: Long?,
    @ColumnInfo(name = "head64k_hash") val head64kHash: String?,
    @ColumnInfo(name = "reason") val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "playback_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = FrameEntity::class,
            parentColumns = ["id"],
            childColumns = ["frame_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["asset_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class PlaybackSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "frame_id") val frameId: String,
    @ColumnInfo(name = "asset_id") val assetId: String?,
    @ColumnInfo(name = "remaining_hold_ms") val remainingHoldMs: Long,
    @ColumnInfo(name = "cursor_json") val cursorJson: String,
    @ColumnInfo(name = "seed") val seed: Long,
    @ColumnInfo(name = "selection_version") val selectionVersion: Long,
    @ColumnInfo(name = "saved_at") val savedAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "weather_snapshot")
data class WeatherSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "place_id") val placeId: String,
    @ColumnInfo(name = "condition_code") val conditionCode: String,
    @ColumnInfo(name = "temperature_milli") val temperatureMilli: Long,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "attribution") val attribution: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "settings_kv")
data class SettingsKvEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "schema_meta")
data class SchemaMetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "holiday_schedule",
    foreignKeys = [
        ForeignKey(
            entity = FrameEntity::class,
            parentColumns = ["id"],
            childColumns = ["frame_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HolidayScheduleEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "frame_id") val frameId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
    @ColumnInfo(name = "start_time") val startTime: Long?,
    @ColumnInfo(name = "end_time") val endTime: Long?,
    @ColumnInfo(name = "position", defaultValue = "0") val position: Long,
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
