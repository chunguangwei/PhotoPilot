package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NasScopeTest {

    @Test
    fun nestedDirectoriesAreDroppedWhenAncestorSelected() {
        assertEquals(
            listOf("photos"),
            NasScope.normalizeDirectories(listOf("photos/2024", "photos", "photos/raw")),
        )
    }

    @Test
    fun duplicatesAndOrderingNormalized() {
        assertEquals(
            listOf("a", "b/c"),
            NasScope.normalizeDirectories(listOf("b/c", "a", "a/", "./b/c")),
        )
    }

    @Test
    fun rootCoversEverything() {
        assertEquals(
            listOf(""),
            NasScope.normalizeDirectories(listOf("photos", "", "other")),
        )
        assertEquals(listOf(""), NasScope.normalizeDirectories(listOf("/")))
    }

    @Test
    fun emptyArrayIsIllegal() {
        assertNull(NasScope.normalizeDirectories(emptyList()))
    }

    @Test
    fun caseIsPreservedAndCaseSensitive() {
        assertEquals(
            listOf("Photos", "photos"),
            NasScope.normalizeDirectories(listOf("photos", "Photos")),
        )
        assertEquals(
            listOf("Photos", "photos/2024"),
            NasScope.normalizeDirectories(listOf("Photos", "photos/2024")),
        )
    }

    @Test
    fun invalidPathsRejectWholeInput() {
        assertNull(NasScope.normalizeDirectories(listOf("ok", "../escape")))
        assertNull(NasScope.normalizeDirectories(listOf("a\u0000b")))
    }

    @Test
    fun scopeJsonUsesDirectoriesKey() {
        assertEquals(
            "{\"directories\":[\"a\",\"b\"]}",
            NasScope.scopeJson(listOf("a", "b")),
        )
    }
}
