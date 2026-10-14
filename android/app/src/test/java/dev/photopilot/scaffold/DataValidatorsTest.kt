package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.OrientationClass
import dev.photopilot.scaffold.data.logic.FrameSelectionLogic
import dev.photopilot.scaffold.data.repo.DataValidators
import org.junit.Assert.assertEquals
import org.junit.Test

class DataValidatorsTest {

    private fun asset(
        contentHash: String = "sha256:" + "a".repeat(64),
        width: Long = 800L,
        height: Long = 600L,
        orientation: OrientationClass = OrientationClass.LANDSCAPE,
        capturedAt: Long? = 1_700_000_000_000L,
        capturedAtSource: CapturedAtSource = CapturedAtSource.EXIF,
        relPath: String = "ab/cd.png",
    ) = AssetEntity(
        id = "asset-1",
        contentHash = contentHash,
        byteSize = 1234L,
        format = AssetFormat.PNG,
        pixelWidth = width,
        pixelHeight = height,
        exifOrientation = 1L,
        orientationClass = orientation,
        capturedAt = capturedAt,
        capturedAtSource = capturedAtSource,
        importedAt = 1L,
        originalRelPath = relPath,
        originalFileName = "cd.png",
        readiness = AssetReadiness.READY,
        failureCode = null,
        privacyExcluded = false,
        edgeColor = null,
        deletedAt = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun validAssetPasses() {
        DataValidators.requireValidAsset(asset())
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedContentHashRejected() {
        DataValidators.requireValidAsset(asset(contentHash = "md5:xyz"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun orientationClassMismatchRejected() {
        DataValidators.requireValidAsset(
            asset(width = 800, height = 600, orientation = OrientationClass.PORTRAIT),
        )
    }

    @Test
    fun orientationClassDerivedFromDims() {
        assertEquals(OrientationClass.LANDSCAPE, DataValidators.derivedOrientationClass(800, 600))
        assertEquals(OrientationClass.PORTRAIT, DataValidators.derivedOrientationClass(600, 800))
        assertEquals(OrientationClass.SQUARE, DataValidators.derivedOrientationClass(640, 640))
    }

    @Test(expected = IllegalArgumentException::class)
    fun exifSourceRequiresCapturedAt() {
        DataValidators.requireValidAsset(asset(capturedAt = null, capturedAtSource = CapturedAtSource.EXIF))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSourceRequiresNullCapturedAt() {
        DataValidators.requireValidAsset(
            asset(capturedAt = 1L, capturedAtSource = CapturedAtSource.UNKNOWN),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun absolutePathRejected() {
        DataValidators.requireValidAsset(asset(relPath = "/etc/passwd"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalRejected() {
        DataValidators.requireValidAsset(asset(relPath = "a/../../b.png"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankPathSegmentRejected() {
        DataValidators.requireValidRelPath("a//b.png")
    }

    @Test
    fun backslashNormalizedBeforeTraversalCheck() {
        DataValidators.requireValidRelPath("a\\b\\c.png")
    }

    @Test
    fun selectionExactlyOneEnforced() {
        val values = FrameSelectionLogic.newSelection(
            frameId = "f1",
            assetId = null,
            sourceAssetId = "sa-1",
            mode = FrameSelectionMode.INCLUDE.wire,
            manualOrder = null,
        )
        DataValidators.requireValidSelection(values)
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectionWithBothRefsRejected() {
        DataValidators.requireValidSelection(
            FrameSelectionLogic.newSelection("f1", "a1", "sa-1", "include", null),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedRefKeyRejected() {
        val values = FrameSelectionLogic.newSelection("f1", "a1", null, "include", null)
        DataValidators.requireValidSelection(values.copy(refKey = "forged"))
    }

    private fun tombstone(
        contentHash: String? = "sha256:" + "b".repeat(64),
        fingerprint: String? = null,
        remoteId: String? = null,
        byteSize: Long? = null,
        head64k: String? = null,
    ) = LocalTombstoneEntity(
        id = "t-1",
        contentHash = contentHash,
        sourceFingerprint = fingerprint,
        remoteId = remoteId,
        byteSize = byteSize,
        head64kHash = head64k,
        reason = "user_delete",
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun fullTombstonePasses() {
        DataValidators.requireValidTombstone(tombstone(fingerprint = "fp", remoteId = "r", byteSize = 1L, head64k = "sha256:" + "c".repeat(64)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyTombstoneRejected() {
        DataValidators.requireValidTombstone(tombstone(contentHash = null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tier2MissingRemoteIdRejected() {
        DataValidators.requireValidTombstone(tombstone(contentHash = null, fingerprint = "fp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tier3MissingHeadRejected() {
        DataValidators.requireValidTombstone(tombstone(contentHash = null, fingerprint = "fp", byteSize = 1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTombstoneReasonRejected() {
        DataValidators.requireValidTombstone(tombstone().copy(reason = " "))
    }
}
