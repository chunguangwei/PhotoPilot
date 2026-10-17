package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Migration45SqlTest {

    private lateinit var conn: Connection

    @Before
    fun openDb() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { st ->
            st.execute(
                "CREATE TABLE asset (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "content_hash TEXT NOT NULL, " +
                    "readiness TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL" +
                    ")",
            )
            st.execute(
                "CREATE TABLE asset_origin (" +
                    "asset_id TEXT NOT NULL, " +
                    "source_asset_id TEXT NOT NULL, " +
                    "imported_at INTEGER NOT NULL, " +
                    "policy_state TEXT NOT NULL, " +
                    "origin_state TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(asset_id, source_asset_id)" +
                    ")",
            )
            st.execute(
                "CREATE TABLE frame_selection (" +
                    "frame_id TEXT NOT NULL, " +
                    "asset_id TEXT, " +
                    "source_asset_id TEXT, " +
                    "mode TEXT NOT NULL, " +
                    "manual_order INTEGER, " +
                    "added_at INTEGER NOT NULL, " +
                    "ref_key TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(frame_id, ref_key)" +
                    ")",
            )
            st.execute(
                "CREATE TABLE collection_asset (" +
                    "collection_id TEXT NOT NULL, " +
                    "asset_id TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(collection_id, asset_id)" +
                    ")",
            )
            st.execute(
                "CREATE TABLE frame (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "cover_asset_id TEXT" +
                    ")",
            )
            st.execute(
                "CREATE TABLE playback_snapshot (" +
                    "frame_id TEXT PRIMARY KEY NOT NULL, " +
                    "asset_id TEXT" +
                    ")",
            )
            st.execute(
                "CREATE TABLE source (" +
                    "id TEXT PRIMARY KEY NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "endpoint_key TEXT" +
                    ")",
            )
        }
    }

    @After
    fun closeDb() {
        conn.close()
    }

    private fun exec(sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    private fun insertAsset(id: String, hash: String, readiness: String, createdAt: Long) {
        exec("INSERT INTO asset (id, content_hash, readiness, created_at) " +
            "VALUES ('$id', '$hash', '$readiness', $createdAt)")
    }

    private fun insertOrigin(assetId: String, sourceAssetId: String) {
        exec("INSERT INTO asset_origin (asset_id, source_asset_id, imported_at, policy_state, origin_state, created_at, updated_at) " +
            "VALUES ('$assetId', '$sourceAssetId', 1, 'allowed', 'active', 1, 1)")
    }

    private fun insertSelection(frameId: String, assetId: String, mode: String = "include") {
        exec("INSERT INTO frame_selection (frame_id, asset_id, source_asset_id, mode, manual_order, added_at, ref_key, created_at, updated_at) " +
            "VALUES ('$frameId', '$assetId', NULL, '$mode', NULL, 1, '$assetId', 1, 1)")
    }

    private fun queryStrings(sql: String): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            val cols = rs.metaData.columnCount
            while (rs.next()) {
                rows.add((1..cols).map { rs.getString(it) })
            }
        }
        return rows
    }

    private fun runMigration() {
        for (statement in PhotoPilotDatabase.MIGRATION_4_5_STATEMENTS) {
            exec(statement)
        }
    }

    private fun runOnOpenDdl() {
        for (statement in PhotoPilotDatabase.CREATE_PARTIAL_INDEXES) {
            exec(statement)
        }
    }

    @Test
    fun migrationDropsContractPartialIndexesAndDoesNotRecreateThem() {
        val statements = PhotoPilotDatabase.MIGRATION_4_5_STATEMENTS
        assertEquals(
            PhotoPilotDatabase.PARTIAL_INDEX_DROPS,
            statements.take(PhotoPilotDatabase.PARTIAL_INDEX_DROPS.size),
        )
        assertTrue(statements.none { it.contains("CREATE UNIQUE INDEX") })
    }

    @Test
    fun mergePrefersReadyKeeperAndRepointsAllReferences() {
        insertAsset("a1", "H1", "pending", 100)
        insertAsset("a2", "H1", "ready", 200)
        insertAsset("solo", "H3", "ready", 300)
        insertOrigin("a1", "sa1")
        insertOrigin("a1", "sa2")
        insertOrigin("a2", "sa1")
        insertSelection("f1", "a1")
        insertSelection("f1", "a2")
        insertSelection("f2", "a1", mode = "exclude")
        exec("INSERT INTO collection_asset (collection_id, asset_id, created_at, updated_at) VALUES ('col1', 'a1', 1, 1)")
        exec("INSERT INTO collection_asset (collection_id, asset_id, created_at, updated_at) VALUES ('col1', 'a2', 1, 1)")
        exec("INSERT INTO frame (id, cover_asset_id) VALUES ('f1', 'a1')")
        exec("INSERT INTO playback_snapshot (frame_id, asset_id) VALUES ('f1', 'a1')")

        runMigration()

        assertEquals(
            listOf(listOf("a2", "H1", "ready"), listOf("solo", "H3", "ready")),
            queryStrings("SELECT id, content_hash, readiness FROM asset ORDER BY id"),
        )
        assertEquals(
            listOf(listOf("a2", "sa1"), listOf("a2", "sa2")),
            queryStrings("SELECT asset_id, source_asset_id FROM asset_origin ORDER BY source_asset_id"),
        )
        assertEquals(
            listOf(listOf("f1", "a2", "include")),
            queryStrings("SELECT frame_id, asset_id, mode FROM frame_selection WHERE frame_id = 'f1'"),
        )
        assertEquals(
            listOf(listOf("f2", "a2", "exclude")),
            queryStrings("SELECT frame_id, asset_id, mode FROM frame_selection WHERE frame_id = 'f2'"),
        )
        assertEquals(
            listOf(listOf("col1", "a2")),
            queryStrings("SELECT collection_id, asset_id FROM collection_asset"),
        )
        assertEquals(listOf(listOf("a2")), queryStrings("SELECT cover_asset_id FROM frame WHERE id = 'f1'"))
        assertEquals(listOf(listOf("a2")), queryStrings("SELECT asset_id FROM playback_snapshot WHERE frame_id = 'f1'"))
    }

    @Test
    fun mergeFallsBackToOldestCreatedAtWhenNoReadyRow() {
        insertAsset("b1", "H2", "failed", 50)
        insertAsset("b2", "H2", "pending", 60)
        insertAsset("b3", "H2", "pending", 40)
        insertSelection("f1", "b2")

        runMigration()

        assertEquals(
            listOf(listOf("b3")),
            queryStrings("SELECT id FROM asset"),
        )
        assertEquals(
            listOf(listOf("b3")),
            queryStrings("SELECT asset_id FROM frame_selection WHERE frame_id = 'f1'"),
        )
    }

    @Test
    fun deletedRowsAreOutOfScopeAndKeepTheirHash() {
        insertAsset("d1", "H4", "deleted", 10)
        insertAsset("d2", "H4", "deleted", 20)
        insertAsset("e1", "H5", "ready", 30)
        insertAsset("e2", "H5", "deleted", 40)

        runMigration()

        assertEquals(
            listOf(listOf("d1"), listOf("d2"), listOf("e1"), listOf("e2")),
            queryStrings("SELECT id FROM asset ORDER BY id"),
        )
    }

    @Test
    fun uniqueIndexRecreatedByOnOpenDdlBlocksSecondLiveHashButAllowsDeleted() {
        insertAsset("a2", "H1", "ready", 200)
        runMigration()
        runOnOpenDdl()

        val index = queryStrings(
            "SELECT name FROM sqlite_master WHERE type = 'index' " +
                "AND name = 'index_asset_content_hash_not_deleted'",
        )
        assertEquals(1, index.size)

        try {
            insertAsset("intruder", "H1", "pending", 500)
            fail("expected unique constraint violation")
        } catch (expected: java.sql.SQLException) {
            assertTrue(expected.message?.contains("UNIQUE") == true)
        }

        insertAsset("revived", "H1", "deleted", 600)
        assertEquals(
            listOf(listOf("a2"), listOf("revived")),
            queryStrings("SELECT id FROM asset ORDER BY id"),
        )
    }

    @Test
    fun migrationIsIdempotentWhenNoDuplicates() {
        insertAsset("a2", "H1", "ready", 200)
        insertOrigin("a2", "sa1")
        insertSelection("f1", "a2")

        runMigration()
        runMigration()

        assertEquals(listOf(listOf("a2")), queryStrings("SELECT id FROM asset"))
        assertEquals(listOf(listOf("a2", "sa1")), queryStrings("SELECT asset_id, source_asset_id FROM asset_origin"))
        assertEquals(listOf(listOf("a2")), queryStrings("SELECT asset_id FROM frame_selection"))
    }
}
