package dev.photopilot.scaffold

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.room.util.TableInfo
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RoomIndexValidationProbe {

    private class JdbcConnection(private val jdbc: Connection) : SQLiteConnection {
        override fun prepare(sql: String): SQLiteStatement = JdbcStatement(jdbc, sql)
        override fun close() = Unit
    }

    private class JdbcStatement(jdbc: Connection, sql: String) : SQLiteStatement {
        private val stmt = jdbc.createStatement()
        private val rs: ResultSet = stmt.executeQuery(sql)
        private val meta = rs.metaData

        override fun bindBlob(index: Int, value: ByteArray) = error("unsupported")
        override fun bindDouble(index: Int, value: Double) = error("unsupported")
        override fun bindLong(index: Int, value: Long) = error("unsupported")
        override fun bindText(index: Int, value: String) = error("unsupported")
        override fun bindNull(index: Int) = error("unsupported")
        override fun getBlob(index: Int): ByteArray = rs.getBytes(index + 1)
        override fun getDouble(index: Int): Double = rs.getDouble(index + 1)
        override fun getLong(index: Int): Long = rs.getLong(index + 1)
        override fun getText(index: Int): String = rs.getString(index + 1)
        override fun isNull(index: Int): Boolean {
            rs.getObject(index + 1)
            return rs.wasNull()
        }

        override fun getColumnCount(): Int = meta.columnCount
        override fun getColumnName(index: Int): String = meta.getColumnName(index + 1)
        override fun getColumnType(index: Int): Int = meta.getColumnType(index + 1)
        override fun step(): Boolean = rs.next()
        override fun reset() = Unit
        override fun clearBindings() = Unit
        override fun close() {
            rs.close()
            stmt.close()
        }
    }

    private fun expectedAssetIndices(): Set<TableInfo.Index> = setOf(
        TableInfo.Index("index_asset_captured_at_id", false, listOf("captured_at", "id"), listOf("ASC", "ASC")),
        TableInfo.Index("index_asset_readiness", false, listOf("readiness"), listOf("ASC")),
        TableInfo.Index("index_asset_orientation_class", false, listOf("orientation_class"), listOf("ASC")),
        TableInfo.Index("index_asset_original_file_name", false, listOf("original_file_name"), listOf("ASC")),
    )

    private fun newDb(): Connection {
        val jdbc = DriverManager.getConnection("jdbc:sqlite::memory:")
        jdbc.createStatement().use { st ->
            st.execute("CREATE TABLE asset (id TEXT PRIMARY KEY NOT NULL, content_hash TEXT NOT NULL, captured_at INTEGER, readiness TEXT NOT NULL, orientation_class TEXT NOT NULL, original_file_name TEXT)")
            st.execute("CREATE INDEX index_asset_captured_at_id ON asset (captured_at, id)")
            st.execute("CREATE INDEX index_asset_readiness ON asset (readiness)")
            st.execute("CREATE INDEX index_asset_orientation_class ON asset (orientation_class)")
            st.execute("CREATE INDEX index_asset_original_file_name ON asset (original_file_name)")
        }
        return jdbc
    }

    private fun readAssetIndices(jdbc: Connection): Set<*> {
        val method = Class.forName("androidx.room.util.SchemaInfoUtilKt")
            .getMethod("readTableInfo", SQLiteConnection::class.java, String::class.java)
        val info = method.invoke(null, JdbcConnection(jdbc), "asset") as TableInfo
        return info.indices.orEmpty()
    }

    @Test
    fun extraPartialIndexIsVisibleToRoomSchemaValidation() {
        val jdbc = newDb()
        jdbc.createStatement().use {
            it.execute("CREATE UNIQUE INDEX index_asset_content_hash_not_deleted ON asset (content_hash) WHERE readiness != 'deleted'")
        }
        val actual = readAssetIndices(jdbc)
        assertNotEquals(expectedAssetIndices(), actual)
        jdbc.close()
    }

    @Test
    fun afterDroppingPartialIndexValidationSetMatchesEntitySchema() {
        val jdbc = newDb()
        jdbc.createStatement().use {
            it.execute("CREATE UNIQUE INDEX index_asset_content_hash_not_deleted ON asset (content_hash) WHERE readiness != 'deleted'")
        }
        jdbc.createStatement().use {
            it.execute("DROP INDEX IF EXISTS index_asset_content_hash_not_deleted")
        }
        val actual = readAssetIndices(jdbc)
        assertEquals(expectedAssetIndices(), actual)
        jdbc.close()
    }
}
