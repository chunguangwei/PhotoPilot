package dev.photopilot.scaffold.data.imports

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class PickedFilesSourceAdapter(
    private val entries: List<Entry>,
    override val sourceFingerprint: String = FINGERPRINT,
) : SourceAdapter {

    data class Entry(val remoteKey: String, val file: File)

    override suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage {
        val offset = when (cursor) {
            null -> 0
            else -> cursor.removePrefix(CURSOR_PREFIX).toIntOrNull()
                ?: throw IllegalArgumentException("invalid cursor")
        }
        if (offset != 0) {
            return EnumerationPage(emptyList(), null, entries.size.toLong())
        }
        return EnumerationPage(
            items = entries.map { entry ->
                EnumeratedAsset(
                    remoteKey = entry.remoteKey,
                    stableId = null,
                    revision = "${entry.file.lastModified()}:${entry.file.length()}",
                    byteSize = entry.file.length(),
                    takenAt = null,
                )
            },
            nextCursor = null,
            totalKnown = entries.size.toLong(),
        )
    }

    override fun openAsset(remoteKey: String): InputStream {
        val entry = entries.firstOrNull { it.remoteKey == remoteKey }
            ?: throw FileNotFoundException("picked asset unavailable")
        return FileInputStream(entry.file)
    }

    companion object {
        const val FINGERPRINT = "android.saf.picked:v1"
        const val CURSOR_PREFIX = LocalFilesSourceAdapter.CURSOR_PREFIX

        fun remoteKeyOf(displayName: String, byteSize: Long): String = "$displayName:$byteSize"

        fun fromDirectory(dir: File): PickedFilesSourceAdapter? {
            if (!dir.isDirectory) return null
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            if (files.isNullOrEmpty()) return null
            return PickedFilesSourceAdapter(
                files.map { file ->
                    Entry(
                        remoteKey = remoteKeyOf(file.name.substringAfter('-', file.name), file.length()),
                        file = file,
                    )
                },
            )
        }
    }
}
