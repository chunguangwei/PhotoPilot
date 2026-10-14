package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.MediaStoreCursor
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreCursorTest {

    @Test
    fun parsesKeysetAndLegacyOffsets() {
        assertEquals(88L, MediaStoreCursor.parseLastId("keyset:88"))
        assertEquals(55L, MediaStoreCursor.parseLastId("offset:55"))
        assertEquals(MediaStoreCursor.START_LAST_ID, MediaStoreCursor.parseLastId("offset:0"))
        assertEquals(MediaStoreCursor.START_LAST_ID, MediaStoreCursor.parseLastId(null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGarbage() {
        MediaStoreCursor.parseLastId("bogus")
    }

    @Test
    fun formatRoundTrips() {
        assertEquals("keyset:123", MediaStoreCursor.format(123L))
        assertEquals(123L, MediaStoreCursor.parseLastId(MediaStoreCursor.format(123L)))
    }
}
