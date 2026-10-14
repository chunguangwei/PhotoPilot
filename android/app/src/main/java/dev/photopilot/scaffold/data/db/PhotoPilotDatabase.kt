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
        const val SCHEMA_VERSION = 4
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
