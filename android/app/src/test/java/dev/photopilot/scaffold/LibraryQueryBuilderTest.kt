package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.LibraryCriteria
import dev.photopilot.scaffold.data.logic.LibraryQueryBuilder
import dev.photopilot.scaffold.data.logic.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryBuilderTest {

    @Test
    fun defaultCriteriaProducesBaseWhereAndPaging() {
        val (sql, args) = LibraryQueryBuilder.build(LibraryCriteria(), 120, 240)
        assertTrue(sql.contains("readiness != 'deleted'"))
        assertTrue(sql.contains("privacy_excluded = 0"))
        assertTrue(sql.contains("COALESCE(a.captured_at, a.imported_at) DESC"))
        assertFalse(sql.contains("LIKE"))
        assertEquals(listOf(120, 240), args.toList())
    }

    @Test
    fun combinedCriteriaBindAllArgumentsInOrder() {
        val criteria = LibraryCriteria(
            search = "trip",
            sourceIds = setOf("s1", "s2"),
            orientations = setOf("landscape"),
            statuses = setOf("ready"),
            noDateOnly = true,
            sort = LibrarySort.CAPTURED_ASC,
        )
        val (sql, args) = LibraryQueryBuilder.build(criteria, 50, 0)
        assertTrue(sql.contains("co.name LIKE '%' || ? || '%'"))
        assertTrue(sql.contains("sa.remote_key LIKE '%' || ? || '%'"))
        assertTrue(sql.contains("sa2.source_id IN (?,?)"))
        assertTrue(sql.contains("a.orientation_class IN (?)"))
        assertTrue(sql.contains("a.readiness IN (?)"))
        assertTrue(sql.contains("a.captured_at IS NULL"))
        assertTrue(sql.contains("COALESCE(a.captured_at, a.imported_at) ASC"))
        assertEquals(
            listOf("trip", "trip", "s1", "s2", "landscape", "ready", 50, 0),
            args.toList(),
        )
    }

    @Test
    fun nameSortUsesRemoteKeySubquery() {
        val (sql, _) = LibraryQueryBuilder.build(
            LibraryCriteria(sort = LibrarySort.NAME_ASC),
            10,
            0,
        )
        assertTrue(sql.contains("MIN(sa3.remote_key)"))
    }

    @Test
    fun importedSortUsesImportedAtDescending() {
        val (sql, _) = LibraryQueryBuilder.build(
            LibraryCriteria(sort = LibrarySort.IMPORTED_DESC),
            10,
            0,
        )
        assertTrue(sql.contains("ORDER BY a.imported_at DESC, a.id"))
    }

    @Test
    fun blankSearchIsIgnored() {
        val (sql, args) = LibraryQueryBuilder.build(
            LibraryCriteria(search = "   "),
            10,
            0,
        )
        assertFalse(sql.contains("LIKE"))
        assertEquals(listOf(10, 0), args.toList())
    }
}
