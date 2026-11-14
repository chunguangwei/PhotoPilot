package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.remote.RemotePaths
import dev.photopilot.scaffold.data.remote.RemoteEligibility
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import org.junit.Assert.*
import org.junit.Test

class RemoteMediaTest {
    @Test fun pathsRespectBoundaries() {
        val cases = Vectors.load("frame-remote-paths.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("id"), c.getBoolean("expectedMember"), RemotePaths.contains(c.getString("folder"), c.getString("remoteKey"), c.getBoolean("recursive")))
        }
    }
    @Test fun remoteEligibilityVectors() {
        val cases = Vectors.load("frame-remote-eligibility.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("id"), c.getBoolean("expectedPlayable"), RemoteEligibility.playable(c.getString("mode"), c.getString("sourceStatus"), c.getString("accessState"), c.getBoolean("policyAllowed"), c.getBoolean("privacyExcluded"), c.getBoolean("deleted"), c.getBoolean("localReady") && c.getBoolean("localFileExists"), c.getString("mediaKind")))
        }
    }
    @Test fun sourceScopeIgnoresFrameScopedLocalSyncJobs() {
        val global = "{\"directories\":[\"global\"]}"
        val frame = "{\"frameId\":\"frame\",\"directories\":[]}"
        assertEquals(global, dev.photopilot.scaffold.data.logic.NasScope.latestSourceScope(listOf(frame, global)))
        assertNull(dev.photopilot.scaffold.data.logic.NasScope.latestSourceScope(listOf(frame)))
    }
    @Test fun catalogOnlyPreferenceSurvivesSourceEditAndExistingImportChoice() {
        val lifecycle = dev.photopilot.scaffold.data.logic.NasSourceLifecycle
        assertTrue(lifecycle.usesCatalogIndex(false, "{\"catalogOnly\":true}"))
        assertTrue(lifecycle.usesCatalogIndex(true, "{}"))
        assertFalse(lifecycle.usesCatalogIndex(false, "{}"))
    }
    @Test fun storageModeDefaultsAndRoundTrip() {
        assertEquals("local", FramePlaybackSettings.parse("{}").mediaStorageMode)
        assertEquals("remote", FramePlaybackSettings.parse("{\"mediaStorageMode\":\"remote\"}").mediaStorageMode)
    }
}
