package dev.photopilot.scaffold.data.imports

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.InputStream

class MediaStoreSourceAdapter(
    private val context: Context,
    override val sourceFingerprint: String = FINGERPRINT,
    private val pageSize: Int = 100,
) : SourceAdapter {

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    fun readAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(
                "android.permission.READ_MEDIA_IMAGES",
            ) == PackageManager.PERMISSION_GRANTED
        }
        return context.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") ==
            PackageManager.PERMISSION_GRANTED
    }

    override suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage {
        val lastId = MediaStoreCursor.parseLastId(cursor)
        val items = mutableListOf<EnumeratedAsset>()
        val pageCursor = queryPage(lastId) ?: return EnumerationPage(
            items = emptyList(),
            nextCursor = null,
            totalKnown = if (lastId == MediaStoreCursor.START_LAST_ID) queryTotalCount() else null,
        )
        pageCursor.use { result ->
            val idColumn = result.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val modifiedColumn = result.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = result.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenColumn = result.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (items.size < pageSize && result.moveToNext()) {
                val takenAt = if (result.isNull(takenColumn)) null else result.getLong(takenColumn)
                items.add(
                    EnumeratedAsset(
                        remoteKey = result.getLong(idColumn).toString(),
                        stableId = null,
                        revision = result.getLong(modifiedColumn).toString(),
                        byteSize = if (result.isNull(sizeColumn)) null else result.getLong(sizeColumn),
                        takenAt = takenAt?.takeIf { it > 0L },
                    ),
                )
            }
        }
        val nextCursor = if (items.size == pageSize) {
            MediaStoreCursor.format(items.last().remoteKey.toLong())
        } else {
            null
        }
        return EnumerationPage(
            items = items,
            nextCursor = nextCursor,
            totalKnown = if (lastId == MediaStoreCursor.START_LAST_ID) queryTotalCount() else null,
        )
    }

    override fun openAsset(remoteKey: String): InputStream {
        val id = remoteKey.toLongOrNull()
            ?: throw IllegalArgumentException("invalid remoteKey: $remoteKey")
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        return context.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException("mediastore asset unavailable: $remoteKey")
    }

    private fun queryPage(lastId: Long): android.database.Cursor? {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Images.Media._ID} > ?",
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(lastId.toString()),
                )
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.Images.Media._ID} ASC",
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, pageSize)
            }
            context.contentResolver.query(uri, projection, args, null)
        } else {
            context.contentResolver.query(
                uri,
                projection,
                "${MediaStore.Images.Media._ID} > ?",
                arrayOf(lastId.toString()),
                "${MediaStore.Images.Media._ID} ASC",
            )
        }
    }

    private fun queryTotalCount(): Long {
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            null,
        ) ?: return 0L
        try {
            return cursor.count.toLong()
        } finally {
            cursor.close()
        }
    }

    companion object {
        const val FINGERPRINT = "android.mediastore.images:v1"
        const val CURSOR_PREFIX = "offset:"
    }
}
