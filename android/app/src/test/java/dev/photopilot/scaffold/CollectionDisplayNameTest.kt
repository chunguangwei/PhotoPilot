package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.data.logic.CollectionDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionDisplayNameTest {

    @Test
    fun importBatchComposesPrefixWithTimestampName() {
        assertEquals(
            "导入 · 2026-10-15 21:27",
            CollectionDisplayName.display("2026-10-15 21:27", CollectionType.IMPORT_BATCH, "导入 · %1\$s"),
        )
    }

    @Test
    fun sourceFolderKeepsRawName() {
        assertEquals(
            "DCIM/Vacation",
            CollectionDisplayName.display("DCIM/Vacation", CollectionType.SOURCE_FOLDER, "导入 · %1\$s"),
        )
    }
}
