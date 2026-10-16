package dev.photopilot.scaffold.data.logic

import dev.photopilot.scaffold.data.db.CollectionType

object CollectionListing {

    fun visibleInFolderList(type: CollectionType, memberCount: Long): Boolean =
        type != CollectionType.IMPORT_BATCH || memberCount > 0L
}
