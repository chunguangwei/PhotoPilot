package dev.photopilot.scaffold

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteFrameCountTest {
    @Test fun remotePhotoCountMakesNoOfflineAvailabilityClaim() {
        assertEquals("1 photo", dev.photopilot.scaffold.ui.frames.remotePhotoCount(1, false))
        assertEquals("2 photos", dev.photopilot.scaffold.ui.frames.remotePhotoCount(2, false))
        assertEquals("2 张照片", dev.photopilot.scaffold.ui.frames.remotePhotoCount(2, true))
    }
}
