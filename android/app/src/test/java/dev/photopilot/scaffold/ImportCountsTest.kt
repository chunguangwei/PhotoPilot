package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ImportCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCountsTest {

    @Test
    fun parsesTypicalCountsJson() {
        val counts = ImportCounts.parse("{\"failed\":1,\"imported\":2,\"skipped\":3}")
        assertEquals(2, ImportCounts.imported(counts))
        assertEquals(1, ImportCounts.failed(counts))
        assertEquals(3, ImportCounts.skipped(counts))
        assertEquals(0, ImportCounts.canceled(counts))
    }

    @Test
    fun emptyJsonYieldsZeros() {
        val counts = ImportCounts.parse("{}")
        assertEquals(0, ImportCounts.imported(counts))
        assertEquals(0, ImportCounts.failed(counts))
        assertEquals(0, ImportCounts.terminal(counts))
    }

    @Test
    fun terminalSumsFinishedStates() {
        val counts = ImportCounts.parse("{\"canceled\":2,\"failed\":1,\"imported\":4,\"queued\":7,\"skipped\":1}")
        assertEquals(8, ImportCounts.terminal(counts))
    }

    @Test
    fun toleratesUnknownStatesAndWhitespace() {
        val counts = ImportCounts.parse("{ \"paused\" : 2, \"imported\":5 }".replace(" ", ""))
        assertEquals(5, ImportCounts.imported(counts))
        assertEquals(5, ImportCounts.terminal(counts))
    }
}
