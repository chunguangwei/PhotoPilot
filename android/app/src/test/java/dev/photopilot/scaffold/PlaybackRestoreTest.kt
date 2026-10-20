package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.PlaybackRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackRestoreTest {
    @Test
    fun retainsSavedAssetInsteadOfResettingToFirst() {
        val ids = listOf("first", "second", "third")
        val restored = PlaybackRestore.resolve(ids, ids, 2, 1200L)
        assertEquals("third", restored.queue[restored.index])
        assertEquals(1200L, restored.remainingHoldMs)
    }

    @Test
    fun removedAssetsCannotReturnThroughSnapshot() {
        val restored = PlaybackRestore.resolve(listOf("visible", "new"), listOf("hidden", "visible"), 1, 1200L)
        assertFalse(restored.queue.contains("hidden"))
        assertEquals("visible", restored.queue[restored.index])
        assertEquals(setOf("visible", "new"), restored.queue.toSet())
    }

    @Test
    fun emptyLiveSelectionCannotBeResurrected() {
        val restored = PlaybackRestore.resolve(emptyList(), listOf("hidden"), 0, 1200L)
        assertEquals(emptyList<String>(), restored.queue)
    }
}
