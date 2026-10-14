package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.AccessState
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.db.OriginState
import dev.photopilot.scaffold.data.db.OrientationClass
import dev.photopilot.scaffold.data.db.PolicyState
import dev.photopilot.scaffold.data.db.RetentionPolicy
import dev.photopilot.scaffold.data.db.SourceStatus
import dev.photopilot.scaffold.data.db.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class EnumWireValuesTest {

    @Test
    fun importJobStatesUseSingleLCanceledSpelling() {
        assertEquals("canceled", ImportJobState.CANCELED.wire)
        assertEquals("partial_failed", ImportJobState.PARTIAL_FAILED.wire)
        assertEquals("paused_auto", ImportJobState.PAUSED_AUTO.wire)
    }

    @Test
    fun importItemStatesMatchContract() {
        assertEquals(
            listOf(
                "queued", "enumerating", "pending_download", "downloading",
                "verifying", "committing", "imported", "paused",
                "waiting_network", "waiting_space", "needs_auth",
                "failed", "canceled", "skipped",
            ),
            ImportItemState.entries.map { it.wire },
        )
    }

    @Test
    fun remainingEnumsMatchContract() {
        assertEquals(
            listOf("local_photos", "local_files", "icloud_photos", "google_picker", "smb", "webdav"),
            SourceType.entries.map { it.wire },
        )
        assertEquals(listOf("user_managed", "policy_gated"), RetentionPolicy.entries.map { it.wire })
        assertEquals(
            listOf("active", "needs_auth", "unavailable", "disconnected"),
            SourceStatus.entries.map { it.wire },
        )
        assertEquals(
            listOf("accessible", "unauthorized", "missing", "removed"),
            AccessState.entries.map { it.wire },
        )
        assertEquals(listOf("jpeg", "png", "heic", "mp4", "mov"), AssetFormat.entries.map { it.wire })
        assertEquals(
            listOf("landscape", "portrait", "square"),
            OrientationClass.entries.map { it.wire },
        )
        assertEquals(listOf("exif", "container", "unknown"), CapturedAtSource.entries.map { it.wire })
        assertEquals(
            listOf("ready", "pending", "failed", "deleted"),
            AssetReadiness.entries.map { it.wire },
        )
        assertEquals(
            listOf("source_folder", "import_batch", "album_map", "user_folder"),
            CollectionType.entries.map { it.wire },
        )
        assertEquals(
            listOf("captured_asc", "random", "manual"),
            FrameOrderMode.entries.map { it.wire },
        )
        assertEquals(listOf("include", "exclude"), FrameSelectionMode.entries.map { it.wire })
        assertEquals(listOf("allowed", "gated", "revoked"), PolicyState.entries.map { it.wire })
        assertEquals(listOf("active", "detached"), OriginState.entries.map { it.wire })
    }
}
