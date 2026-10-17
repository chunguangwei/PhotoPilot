package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.PageDedup
import org.junit.Assert.assertEquals
import org.junit.Test

class PageDedupTest {

    data class Row(val id: String)

    @Test
    fun dropsPageRowsAlreadyLoaded() {
        val existing = listOf(Row("a"), Row("b"))
        val page = listOf(Row("b"), Row("c"), Row("d"))
        assertEquals(listOf(Row("c"), Row("d")), PageDedup.filterNew(existing, page) { it.id })
    }

    @Test
    fun keepsEverythingWhenNoOverlap() {
        val existing = listOf(Row("a"))
        val page = listOf(Row("x"), Row("y"))
        assertEquals(page, PageDedup.filterNew(existing, page) { it.id })
    }

    @Test
    fun emptyPageOrEmptyExistingArePassThrough() {
        assertEquals(emptyList<Row>(), PageDedup.filterNew(listOf(Row("a")), emptyList()) { it.id })
        val page = listOf(Row("a"))
        assertEquals(page, PageDedup.filterNew(emptyList(), page) { it.id })
    }

    @Test
    fun duplicatesWithinPageItselfAreKeptOncePerExistingSemantics() {
        val existing = listOf(Row("a"))
        val page = listOf(Row("b"), Row("b"))
        assertEquals(listOf(Row("b"), Row("b")), PageDedup.filterNew(existing, page) { it.id })
    }
}
