package dev.photopilot.scaffold.data.imports

import java.io.InputStream

interface SourceAdapter {

    val sourceFingerprint: String

    suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage

    fun openAsset(remoteKey: String): InputStream
}

data class EnumerationPage(
    val items: List<EnumeratedAsset>,
    val nextCursor: String?,
    val totalKnown: Long?,
)

data class EnumeratedAsset(
    val remoteKey: String,
    val stableId: String?,
    val revision: String,
    val byteSize: Long?,
    val takenAt: Long?,
)
