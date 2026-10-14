package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.LocalImportStore
import dev.photopilot.scaffold.data.imports.AssetTruncatedException
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImportStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStore(): Pair<LocalImportStore, Pair<File, File>> {
        val originals = folder.newFolder("originals")
        val tmp = folder.newFolder("tmp")
        return LocalImportStore(originals, tmp) to (originals to tmp)
    }

    @Test
    fun stageComputesStreamingHashes() {
        val (store, _) = newStore()
        val fixture = ContentHashTest.fixture("F01/baseline.jpg")
        val staged = store.stage("a.part", fixture.inputStream(), fixture.length())
        assertEquals("a.part", staged.tempRelPath)
        assertEquals(fixture.length(), staged.bytesWritten)
        assertEquals(
            dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(fixture.readBytes()),
            staged.contentHash,
        )
        assertTrue(staged.head64kHash != null)
    }

    @Test(expected = AssetTruncatedException::class)
    fun stageRejectsByteCountMismatch() {
        val (store, _) = newStore()
        val fixture = ContentHashTest.fixture("F01/baseline.jpg")
        store.stage("a.part", fixture.inputStream(), fixture.length() + 1)
    }

    @Test
    fun commitStagedMovesFileIntoOriginals() {
        val (store, dirs) = newStore()
        val payload = ByteArray(4096) { 7 }
        store.stage("b.part", payload.inputStream(), payload.size.toLong())
        store.commitStaged("b.part", "sub/dir/asset-1.png")
        assertTrue(File(dirs.first, "sub/dir/asset-1.png").isFile)
        assertFalse(File(dirs.second, "b.part").exists())
        assertTrue(store.existsOriginal("sub/dir/asset-1.png"))
        assertFalse(store.existsOriginal("missing.png"))
    }

    @Test
    fun discardStagedRemovesTempFile() {
        val (store, dirs) = newStore()
        store.stage("c.part", ByteArray(16).inputStream(), 16L)
        store.discardStaged("c.part")
        assertFalse(File(dirs.second, "c.part").exists())
        assertTrue(dirs.second.listFiles().isEmpty())
    }
}
