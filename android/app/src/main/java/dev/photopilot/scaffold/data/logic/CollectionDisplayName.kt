package dev.photopilot.scaffold.data.logic

import dev.photopilot.scaffold.data.db.CollectionType

object CollectionDisplayName {

    fun display(name: String, type: CollectionType, importBatchFormat: String): String =
        if (type == CollectionType.IMPORT_BATCH) {
            String.format(importBatchFormat, name)
        } else {
            name
        }
}
