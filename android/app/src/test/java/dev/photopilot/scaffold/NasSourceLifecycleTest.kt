package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasSourceLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NasSourceLifecycleTest {

    @Test
    fun disconnectedRowReactivatesOnReconnect() {
        assertTrue(NasSourceLifecycle.shouldReactivate("disconnected"))
        assertFalse(NasSourceLifecycle.shouldReactivate("active"))
        assertFalse(NasSourceLifecycle.shouldReactivate("needs_auth"))
    }

    @Test
    fun recursiveSettingRoundtripsWithDefaultFalse() {
        assertTrue(NasSourceLifecycle.recursiveFromSettings("{\"recursive\":true}"))
        assertFalse(NasSourceLifecycle.recursiveFromSettings("{\"recursive\":false}"))
        assertFalse(NasSourceLifecycle.recursiveFromSettings(null))
        assertFalse(NasSourceLifecycle.recursiveFromSettings("{}"))
        assertFalse(NasSourceLifecycle.recursiveFromSettings("garbage"))
        val written = NasSourceLifecycle.settingsJsonWithRecursive(true)
        assertTrue(NasSourceLifecycle.recursiveFromSettings(written))
    }

    @Test
    fun mirrorDeleteMergesPreservingOtherKeys() {
        val merged = NasSourceLifecycle.mergeSettings("{\"recursive\":true}", mirrorDelete = true)
        assertTrue(NasSourceLifecycle.mirrorDeleteFromSettings(merged))
        assertTrue(NasSourceLifecycle.recursiveFromSettings(merged))
        val off = NasSourceLifecycle.mergeSettings(merged, mirrorDelete = false)
        assertFalse(NasSourceLifecycle.mirrorDeleteFromSettings(off))
        assertFalse(NasSourceLifecycle.mirrorDeleteFromSettings(null))
        assertFalse(NasSourceLifecycle.mirrorDeleteFromSettings("{}"))
        assertEquals("nas.pending_delete:src-1", NasSourceLifecycle.pendingDeleteKey("src-1"))
    }
}
