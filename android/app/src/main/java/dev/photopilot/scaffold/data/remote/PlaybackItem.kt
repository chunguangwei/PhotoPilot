package dev.photopilot.scaffold.data.remote

import dev.photopilot.scaffold.data.db.*

data class PlaybackItem(val local: AssetEntity? = null, val remote: SourceAssetEntity? = null, val sourceEpoch: Long? = null, val localIsRetained: Boolean = false, val referenceDisplayName: String? = null, val referenceCacheable: Boolean = true) {
    init { require(local != null || remote != null) }
    val id: String get() = remote?.let { "remote:${it.id}" } ?: requireNotNull(local).id
    val sourceAssetId: String? get() = remote?.id
    val mediaKind: MediaKind get() = local?.mediaKind ?: MediaKind.IMAGE
    val durationMs: Long? get() = local?.durationMs
    val originalRelPath: String get() = local?.originalRelPath.orEmpty()
    val exifOrientation: Long get() = local?.exifOrientation ?: 1
    val capturedAt: Long? get() = remote?.remoteTakenAt ?: local?.capturedAt
    val importedAt: Long get() = local?.importedAt ?: requireNotNull(remote).createdAt
    val filename: String get() = referenceDisplayName ?: remote?.remoteKey?.substringAfterLast('/') ?: local?.originalFileName.orEmpty()
}
