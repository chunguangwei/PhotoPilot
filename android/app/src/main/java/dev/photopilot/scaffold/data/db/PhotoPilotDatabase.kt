package dev.photopilot.scaffold.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        SourceAssetEntity::class,
        AssetEntity::class,
        AssetOriginEntity::class,
        CollectionEntity::class,
        CollectionAssetEntity::class,
        FrameEntity::class,
        FrameSelectionEntity::class,
        FrameDirectoryRuleEntity::class,
        ImportJobEntity::class,
        ImportItemEntity::class,
        LocalTombstoneEntity::class,
        PlaybackSnapshotEntity::class,
        WeatherSnapshotEntity::class,
        SettingsKvEntity::class,
        SchemaMetaEntity::class,
        HolidayScheduleEntity::class,
    ],
    version = PhotoPilotDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class PhotoPilotDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun sourceAssetDao(): SourceAssetDao
    abstract fun assetDao(): AssetDao
    abstract fun assetOriginDao(): AssetOriginDao
    abstract fun collectionDao(): CollectionDao
    abstract fun collectionAssetDao(): CollectionAssetDao
    abstract fun frameDao(): FrameDao
    abstract fun frameSelectionDao(): FrameSelectionDao
    abstract fun frameDirectoryRuleDao(): FrameDirectoryRuleDao
    abstract fun importJobDao(): ImportJobDao
    abstract fun importItemDao(): ImportItemDao
    abstract fun localTombstoneDao(): LocalTombstoneDao
    abstract fun playbackSnapshotDao(): PlaybackSnapshotDao
    abstract fun weatherSnapshotDao(): WeatherSnapshotDao
    abstract fun settingsKvDao(): SettingsKvDao
    abstract fun schemaMetaDao(): SchemaMetaDao
    abstract fun holidayDao(): HolidayDao

    companion object {
        const val SCHEMA_VERSION = 5
        const val SCHEMA_VERSION_KEY = "schema_version"

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weather_snapshot` (" +
                        "`place_id` TEXT NOT NULL, " +
                        "`condition_code` TEXT NOT NULL, " +
                        "`temperature_milli` INTEGER NOT NULL, " +
                        "`observed_at` INTEGER NOT NULL, " +
                        "`fetched_at` INTEGER NOT NULL, " +
                        "`expires_at` INTEGER NOT NULL, " +
                        "`attribution` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`place_id`)" +
                        ")",
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE asset ADD COLUMN media_kind TEXT NOT NULL DEFAULT 'image'")
                db.execSQL("ALTER TABLE asset ADD COLUMN duration_ms INTEGER")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `holiday_schedule` (" +
                        "`id` TEXT NOT NULL, " +
                        "`frame_id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`start_date` TEXT NOT NULL, " +
                        "`end_date` TEXT NOT NULL, " +
                        "`start_time` INTEGER, " +
                        "`end_time` INTEGER, " +
                        "`position` INTEGER NOT NULL DEFAULT 0, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`frame_id`) REFERENCES `frame`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
            }
        }

        val PARTIAL_INDEX_DROPS = listOf(
            "DROP INDEX IF EXISTS index_asset_content_hash_not_deleted",
            "DROP INDEX IF EXISTS index_source_type_endpoint_key_not_null",
        )

        val MIGRATION_4_5_STATEMENTS = PARTIAL_INDEX_DROPS + listOf(
            "DROP TABLE IF EXISTS dup_asset_keep",
            "CREATE TEMP TABLE dup_asset_keep AS " +
                "SELECT a1.content_hash AS content_hash, (" +
                "SELECT a2.id FROM asset a2 " +
                "WHERE a2.content_hash = a1.content_hash AND a2.readiness != 'deleted' " +
                "ORDER BY CASE WHEN a2.readiness = 'ready' THEN 0 ELSE 1 END, a2.created_at ASC, a2.id ASC " +
                "LIMIT 1" +
                ") AS keeper_id " +
                "FROM asset a1 WHERE a1.readiness != 'deleted' " +
                "GROUP BY a1.content_hash HAVING COUNT(*) > 1",
            "CREATE TEMP TABLE dup_asset_map AS " +
                "SELECT a.id AS dup_id, k.keeper_id AS keeper_id " +
                "FROM asset a INNER JOIN dup_asset_keep k ON k.content_hash = a.content_hash " +
                "WHERE a.readiness != 'deleted' AND a.id != k.keeper_id",
            "INSERT OR IGNORE INTO frame_selection " +
                "(frame_id, asset_id, source_asset_id, mode, manual_order, added_at, ref_key, created_at, updated_at) " +
                "SELECT fs.frame_id, m.keeper_id, NULL, fs.mode, fs.manual_order, fs.added_at, m.keeper_id, " +
                "fs.created_at, fs.updated_at " +
                "FROM frame_selection fs INNER JOIN dup_asset_map m ON m.dup_id = fs.asset_id",
            "DELETE FROM frame_selection WHERE asset_id IN (SELECT dup_id FROM dup_asset_map)",
            "INSERT OR IGNORE INTO collection_asset (collection_id, asset_id, created_at, updated_at) " +
                "SELECT ca.collection_id, m.keeper_id, ca.created_at, ca.updated_at " +
                "FROM collection_asset ca INNER JOIN dup_asset_map m ON m.dup_id = ca.asset_id",
            "DELETE FROM collection_asset WHERE asset_id IN (SELECT dup_id FROM dup_asset_map)",
            "INSERT OR IGNORE INTO asset_origin " +
                "(asset_id, source_asset_id, imported_at, policy_state, origin_state, created_at, updated_at) " +
                "SELECT m.keeper_id, ao.source_asset_id, ao.imported_at, ao.policy_state, ao.origin_state, " +
                "ao.created_at, ao.updated_at " +
                "FROM asset_origin ao INNER JOIN dup_asset_map m ON m.dup_id = ao.asset_id",
            "DELETE FROM asset_origin WHERE asset_id IN (SELECT dup_id FROM dup_asset_map)",
            "UPDATE frame SET cover_asset_id = (" +
                "SELECT keeper_id FROM dup_asset_map WHERE dup_id = frame.cover_asset_id" +
                ") WHERE cover_asset_id IN (SELECT dup_id FROM dup_asset_map)",
            "UPDATE playback_snapshot SET asset_id = (" +
                "SELECT keeper_id FROM dup_asset_map WHERE dup_id = playback_snapshot.asset_id" +
                ") WHERE asset_id IN (SELECT dup_id FROM dup_asset_map)",
            "DELETE FROM asset WHERE id IN (SELECT dup_id FROM dup_asset_map)",
            "DROP TABLE IF EXISTS dup_asset_map",
            "DROP TABLE IF EXISTS dup_asset_keep",
        )

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (statement in MIGRATION_4_5_STATEMENTS) {
                    db.execSQL(statement)
                }
            }
        }

        val CREATE_PARTIAL_INDEXES = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_asset_content_hash_not_deleted " +
                "ON asset (content_hash) WHERE readiness != 'deleted'",
            "CREATE UNIQUE INDEX IF NOT EXISTS index_source_type_endpoint_key_not_null " +
                "ON source (type, endpoint_key) WHERE endpoint_key IS NOT NULL",
        )

        const val RECREATE_FRAME_SELECTION_WITH_CHECK =
            "DROP TABLE IF EXISTS frame_selection"
        const val CREATE_FRAME_SELECTION_WITH_CHECK =
            "CREATE TABLE IF NOT EXISTS frame_selection (" +
                "frame_id TEXT NOT NULL, " +
                "asset_id TEXT, " +
                "source_asset_id TEXT, " +
                "mode TEXT NOT NULL, " +
                "manual_order INTEGER, " +
                "added_at INTEGER NOT NULL, " +
                "ref_key TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "PRIMARY KEY(frame_id, ref_key), " +
                "FOREIGN KEY(frame_id) REFERENCES frame(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(asset_id) REFERENCES asset(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(source_asset_id) REFERENCES source_asset(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "CHECK ((asset_id IS NULL) != (source_asset_id IS NULL))" +
                ")"

        fun seedSchemaMeta(db: SupportSQLiteDatabase, nowMillis: Long) {
            db.execSQL(
                "INSERT OR REPLACE INTO schema_meta (key, value, created_at, updated_at) " +
                    "VALUES ('$SCHEMA_VERSION_KEY', '$SCHEMA_VERSION', $nowMillis, $nowMillis)",
            )
        }

        fun applyContractDdl(db: SupportSQLiteDatabase, nowMillis: Long) {
            db.execSQL(RECREATE_FRAME_SELECTION_WITH_CHECK)
            db.execSQL(CREATE_FRAME_SELECTION_WITH_CHECK)
            for (statement in CREATE_PARTIAL_INDEXES) {
                db.execSQL(statement)
            }
            seedSchemaMeta(db, nowMillis)
        }
    }
}
