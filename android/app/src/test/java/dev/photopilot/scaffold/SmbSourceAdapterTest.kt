package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.SmbSourceAdapter
import dev.photopilot.scaffold.data.net.SmbEntry
import dev.photopilot.scaffold.data.net.SmbShare
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbSourceAdapterTest {

    private class FakeShare(
        private val dirs: Map<String, List<SmbEntry>>,
        private val files: Map<String, ByteArray>,
    ) : SmbShare {
        override fun security() = dev.photopilot.scaffold.data.net.SmbClient.Security("SMB_3_1_1", true, true)

        override fun list(path: String): List<SmbEntry> = dirs[path] ?: emptyList()

        override fun openRead(path: String): InputStream =
            files.getValue(path).inputStream()
    }

    private fun fixtureShare(): FakeShare {
        val a = ByteArray(8) { 1 }
        val b = ByteArray(6) { 2 }
        val c = ByteArray(4) { 3 }
        return FakeShare(
            dirs = mapOf(
                "" to listOf(
                    SmbEntry("sub", isCollection = true),
                    SmbEntry("a.jpg", size = a.size.toLong(), modifiedMs = 1000L),
                    SmbEntry("b.png", size = b.size.toLong(), modifiedMs = 2000L),
                    SmbEntry("d.txt", size = 1L, modifiedMs = 3000L),
                ),
                "sub" to listOf(
                    SmbEntry("c.jpg", size = c.size.toLong(), modifiedMs = 4000L),
                ),
            ),
            files = mapOf("a.jpg" to a, "b.png" to b, "sub/c.jpg" to c),
        )
    }

    @Test
    fun recursiveEnumerateWalksTree() = runBlocking {
        val adapter = SmbSourceAdapter(
            share = fixtureShare(),
            sourceFingerprint = "smb://nas.local:445/share",
            pageSize = 2,
            recursive = true,
        )
        val first = adapter.enumerate("{\"directories\":[\"\"]}", null)
        assertEquals(listOf("a.jpg", "b.png"), first.items.map { it.remoteKey })
        assertEquals("1000:8", first.items[0].revision)
        assertTrue(first.nextCursor != null)
        val second = adapter.enumerate("{\"directories\":[\"\"]}", first.nextCursor)
        assertEquals(listOf("sub/c.jpg"), second.items.map { it.remoteKey })
        assertNull(second.nextCursor)
        assertNull(first.totalKnown)
        Unit
    }

    @Test
    fun nonRecursiveSkipsSubdirectories() = runBlocking {
        val adapter = SmbSourceAdapter(
            share = fixtureShare(),
            sourceFingerprint = "smb://nas.local:445/share",
            pageSize = 10,
            recursive = false,
        )
        val page = adapter.enumerate("{\"directories\":[\"\"]}", null)
        assertEquals(listOf("a.jpg", "b.png"), page.items.map { it.remoteKey })
        assertNull(page.nextCursor)
        Unit
    }

    @Test
    fun nonMediaExtensionsFiltered() = runBlocking {
        val adapter = SmbSourceAdapter(
            share = fixtureShare(),
            sourceFingerprint = "smb://nas.local:445/share",
            recursive = true,
        )
        val page = adapter.enumerate("{\"directories\":[\"\"]}", null)
        assertTrue(page.items.none { it.remoteKey == "d.txt" })
        Unit
    }

    @Test
    fun openAssetStreamsBytes() = runBlocking {
        val adapter = SmbSourceAdapter(
            share = fixtureShare(),
            sourceFingerprint = "smb://nas.local:445/share",
        )
        assertEquals(4, adapter.openAsset("sub/c.jpg").readBytes().size)
        Unit
    }
}
