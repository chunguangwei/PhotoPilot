package dev.photopilot.scaffold.update

import dev.photopilot.scaffold.data.update.DownloadIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadIdentityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sameReleaseKeepsPartialBytes() {
        val part = File(folder.root, "update.part")
        DownloadIdentity.prepare(part, "https://example.com/a.apk", 42L)
        part.writeText("partial")
        DownloadIdentity.prepare(part, "https://example.com/a.apk", 42L)
        assertEquals("partial", part.readText())
    }

    @Test fun differentReleaseDiscardsPartialBytesEvenWithSameSize() {
        val part = File(folder.root, "update.part")
        DownloadIdentity.prepare(part, "https://example.com/a.apk", 42L)
        part.writeText("old")
        DownloadIdentity.prepare(part, "https://example.com/b.apk", 42L)
        assertFalse(part.exists())
    }

    @Test fun legacyUnidentifiedPartialIsDiscarded() {
        val part = File(folder.root, "update.part").apply { writeText("old") }
        DownloadIdentity.prepare(part, "https://example.com/a.apk", 42L)
        assertFalse(part.exists())
    }
}
