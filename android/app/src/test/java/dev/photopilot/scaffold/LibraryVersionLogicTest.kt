package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.LibraryVersionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryVersionLogicTest {

    @Test
    fun missingVersionStartsAtOne() {
        assertEquals(1L, LibraryVersionLogic.next(null))
    }

    @Test
    fun versionIncrementsMonotonically() {
        assertEquals(1L, LibraryVersionLogic.next(0L))
        assertEquals(6L, LibraryVersionLogic.next(5L))
        assertEquals(101L, LibraryVersionLogic.next(100L))
    }

    @Test
    fun parseStoredReadsPlainNumber() {
        assertEquals(7L, LibraryVersionLogic.parseStored("7"))
    }

    @Test
    fun parseStoredReadsQuotedJsonString() {
        assertEquals(7L, LibraryVersionLogic.parseStored("\"7\""))
    }

    @Test
    fun parseStoredReturnsNullForMissingOrInvalid() {
        assertNull(LibraryVersionLogic.parseStored(null))
        assertNull(LibraryVersionLogic.parseStored(""))
        assertNull(LibraryVersionLogic.parseStored("not-a-number"))
    }

    @Test
    fun nextFromStoredHandlesMissingAndExisting() {
        assertEquals(1L, LibraryVersionLogic.nextFromStored(null))
        assertEquals(8L, LibraryVersionLogic.nextFromStored("7"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeStoredVersionRejected() {
        LibraryVersionLogic.next(-1L)
    }

    @Test
    fun storedJsonRoundTripsThroughParse() {
        val json = LibraryVersionLogic.storedJson(42L)
        assertEquals(42L, LibraryVersionLogic.parseStored(json))
        assertEquals(43L, LibraryVersionLogic.nextFromStored(json))
    }
}
