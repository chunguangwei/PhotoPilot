package dev.photopilot.scaffold.data.imports

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class PickedFilesStager(private val context: Context) {

    data class Result(
        val entries: List<PickedFilesSourceAdapter.Entry>,
        val skippedUnsupported: Int,
    )

    fun stage(uris: List<Uri>, jobTag: String): Result {
        val dir = File(context.cacheDir, "picked/$jobTag")
        dir.mkdirs()
        var skipped = 0
        val entries = uris.mapIndexedNotNull { index, uri ->
            val displayName = displayNameOf(uri) ?: "picked-$index"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension !in LocalFilesSourceAdapter.MEDIA_EXTENSIONS) {
                skipped += 1
                return@mapIndexedNotNull null
            }
            val safeName = displayName.replace('/', '_').replace('\\', '_')
            val target = File(dir, "${index.toString().padStart(3, '0')}-$safeName")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                } ?: throw FileNotFoundException("unreadable uri")
            }.isSuccess
            if (!copied) {
                target.delete()
                return@mapIndexedNotNull null
            }
            PickedFilesSourceAdapter.Entry(
                remoteKey = PickedFilesSourceAdapter.remoteKeyOf(displayName, target.length()),
                file = target,
            )
        }
        return Result(entries = entries, skippedUnsupported = skipped)
    }

    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            ) ?: return@runCatching null
            try {
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } finally {
                cursor.close()
            }
        }.getOrNull()
}
