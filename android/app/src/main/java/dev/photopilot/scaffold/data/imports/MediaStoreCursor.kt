package dev.photopilot.scaffold.data.imports

object MediaStoreCursor {

    const val KEYSET_PREFIX = "keyset:"

    const val START_LAST_ID = -1L

    fun parseLastId(cursor: String?): Long {
        if (cursor == null) return START_LAST_ID
        cursor.removePrefix(KEYSET_PREFIX).toLongOrNull()?.let { return it }
        cursor.removePrefix(LocalFilesSourceAdapter.CURSOR_PREFIX).toLongOrNull()?.let {
            return if (it <= 0L) START_LAST_ID else it
        }
        throw IllegalArgumentException("invalid cursor")
    }

    fun format(lastId: Long): String = "$KEYSET_PREFIX$lastId"
}
