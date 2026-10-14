package dev.photopilot.scaffold.data.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class UpdateFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_APK

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            },
        )
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w') || mode.contains('t')) {
            throw FileNotFoundException("read only")
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no name")
        val dir = File(ctx.cacheDir, UpdateDownloadService.UPDATES_DIR)
        val file = File(dir, name)
        val rootPath = dir.canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(rootPath) || !file.isFile) {
            throw FileNotFoundException(name)
        }
        return file
    }

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
    }
}
