package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.data.logic.CollectionListing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionListingTest {

    @Test
    fun emptyImportBatchHiddenFromFolderList() {
        assertFalse(CollectionListing.visibleInFolderList(CollectionType.IMPORT_BATCH, 0L))
    }

    @Test
    fun nonEmptyImportBatchVisible() {
        assertTrue(CollectionListing.visibleInFolderList(CollectionType.IMPORT_BATCH, 3L))
    }

    @Test
    fun emptySourceFolderStillVisible() {
        assertTrue(CollectionListing.visibleInFolderList(CollectionType.SOURCE_FOLDER, 0L))
    }
}
