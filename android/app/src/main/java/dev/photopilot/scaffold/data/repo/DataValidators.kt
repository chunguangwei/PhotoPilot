package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.OrientationClass
import dev.photopilot.scaffold.data.logic.ContentHash
import dev.photopilot.scaffold.data.logic.FrameSelectionLogic
import dev.photopilot.scaffold.data.logic.FrameSelectionValues

object DataValidators {

    private val HASH_REGEX = Regex("^sha256:[0-9a-f]{64}$")

    fun requireValidContentHash(contentHash: String) {
        require(HASH_REGEX.matches(contentHash)) { "invalid content_hash format" }
    }

    fun requireValidAsset(entity: AssetEntity) {
        requireValidContentHash(entity.contentHash)
        require(entity.byteSize > 0L) { "byte_size must be positive" }
        require(entity.pixelWidth > 0L && entity.pixelHeight > 0L) { "pixel dims must be positive" }
        require(entity.exifOrientation in 1L..8L) { "exif_orientation must be 1..8" }
        require(entity.orientationClass == derivedOrientationClass(entity.pixelWidth, entity.pixelHeight)) {
            "orientation_class does not match corrected pixel dims"
        }
        require(
            (entity.capturedAtSource == CapturedAtSource.EXIF) == (entity.capturedAt != null),
        ) { "captured_at/captured_at_source mismatch" }
        requireValidRelPath(entity.originalRelPath)
        require(entity.format in setOf(AssetFormat.JPEG, AssetFormat.PNG, AssetFormat.HEIC)) {
            "format not in whitelist"
        }
    }

    fun requireValidSelection(values: FrameSelectionValues) {
        FrameSelectionLogic.requireExactlyOneRef(values.assetId, values.sourceAssetId)
        require(values.refKey == FrameSelectionLogic.refKey(values.assetId, values.sourceAssetId)) {
            "ref_key must equal the non-null ref"
        }
        require(values.mode == "include" || values.mode == "exclude") { "unknown selection mode" }
    }

    fun requireValidTombstone(entity: LocalTombstoneEntity) {
        require(entity.reason.isNotBlank()) { "tombstone reason required" }
        val tier1 = entity.contentHash != null
        val tier2 = entity.sourceFingerprint != null && entity.remoteId != null
        val tier3 = entity.sourceFingerprint != null &&
            entity.byteSize != null &&
            entity.head64kHash != null
        require(tier1 || tier2 || tier3) { "tombstone lacks a complete tier key set" }
        if (entity.contentHash != null) requireValidContentHash(entity.contentHash)
    }

    fun requireValidRelPath(relPath: String) {
        require(relPath.isNotBlank()) { "rel path blank" }
        val normalized = relPath.replace('\\', '/')
        require(!normalized.startsWith("/")) { "rel path must be relative" }
        val segments = normalized.split('/')
        require(".." !in segments) { "path traversal rejected" }
        require(segments.none { it.isBlank() }) { "empty path segment" }
    }

    fun derivedOrientationClass(width: Long, height: Long): OrientationClass = when {
        width == height -> OrientationClass.SQUARE
        width > height -> OrientationClass.LANDSCAPE
        else -> OrientationClass.PORTRAIT
    }

    fun head64kHashOf(bytes: ByteArray): String? =
        if (bytes.isEmpty()) {
            null
        } else {
            ContentHash.sha256Hex(bytes.copyOfRange(0, minOf(bytes.size, ContentHash.HEAD64K_SIZE)))
        }

    fun isTerminalReadiness(readiness: AssetReadiness): Boolean =
        readiness == AssetReadiness.DELETED
}
