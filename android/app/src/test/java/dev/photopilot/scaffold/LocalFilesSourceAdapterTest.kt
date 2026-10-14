package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.EnumeratedAsset
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.LocalFilesSourceAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalFilesSourceAdapterTest {

    private fun enumerateAll(pageSize: Int): List<EnumeratedAsset> = runBlocking {
        val adapter = LocalFilesSourceAdapter(Vectors.fixtureRoot(), "local_files:smoke-v1", pageSize = pageSize)
        val out = mutableListOf<EnumeratedAsset>()
        var cursor: String? = LocalFilesSourceAdapter.CURSOR_PREFIX + "0"
        while (cursor != null) {
            val page = adapter.enumerate("{}", cursor)
            out += page.items
            cursor = page.nextCursor
        }
        out
    }

    @Test
    fun enum01OnlyMediaFilesEnumerated() {
        val vector = Vectors.load("fixture-enumeration.json")
        val expectedCount = vector.getInt("expected_count")
        val excluded = vector.getJSONArray("excluded_examples")
        val case = vector.getJSONArray("cases").getJSONObject(0)
        assertEquals("ENUM-01", case.getString("id"))
        assertEquals(expectedCount, case.getInt("expected_count"))

        val items = enumerateAll(500)
        assertEquals(expectedCount, items.size)
        val keys = items.map { it.remoteKey }
        for (i in 0 until excluded.length()) {
            assertTrue(
                "excluded ${excluded.getString(i)} must not be enumerated",
                excluded.getString(i) !in keys,
            )
        }
        val mustNotContain = case.getJSONArray("must_not_contain")
        for (i in 0 until mustNotContain.length()) {
            assertTrue(mustNotContain.getString(i) !in keys)
        }
    }

    @Test
    fun enum02NestedSameNamesKeepDistinctRelativeKeys() {
        val items = enumerateAll(500)
        val keys = items.map { it.remoteKey }.toSet()
        assertTrue("F24/folder-0/duplicate.jpg" in keys)
        assertTrue("F24/folder-1/duplicate.jpg" in keys)
        assertEquals(keys.size, items.size)
        assertTrue(keys.all { !it.startsWith("/") && ".." !in it.split('/') })
        assertTrue("F23/山与海.png" in keys)
        assertTrue("F23/name with spaces.png" in keys)
    }

    @Test
    fun enum03PaginationNeverLosesOrDuplicates() {
        val vector = Vectors.load("fixture-enumeration.json")
        val case = vector.getJSONArray("cases").getJSONObject(2)
        assertEquals("ENUM-03", case.getString("id"))
        val full = enumerateAll(500).map { it.remoteKey }.toSet()
        val sizes = case.getJSONArray("page_sizes")
        for (i in 0 until sizes.length()) {
            val pageSize = sizes.getInt(i)
            val paged = enumerateAll(pageSize)
            assertEquals("page_size=$pageSize", full, paged.map { it.remoteKey }.toSet())
            assertEquals("page_size=$pageSize duplicates", paged.size, paged.map { it.remoteKey }.toSet().size)
            assertEquals("page_size=$pageSize", full.size, paged.size)
        }
    }

    @Test
    fun revisionEncodesMtimeAndSize() {
        val baseline = enumerateAll(500).first { it.remoteKey == "F01/baseline.jpg" }
        val file = Vectors.fixture("F01/baseline.jpg")
        assertEquals("${file.lastModified()}:${file.length()}", baseline.revision)
        assertEquals(file.length(), baseline.byteSize)
        assertNull(baseline.stableId)
        assertNull(baseline.takenAt)
    }

    @Test
    fun openAssetReadsExactBytes() {
        val adapter = LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp")
        val bytes = adapter.openAsset("F01/baseline.jpg").use { it.readBytes() }
        assertEquals(Vectors.fixture("F01/baseline.jpg").length(), bytes.size.toLong())
    }

    @Test(expected = IllegalArgumentException::class)
    fun openAssetRejectsTraversal() {
        LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp").openAsset("../contracts/error-codes.yaml")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCursorRejected() {
        runBlocking {
            LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp").enumerate("{}", "bogus")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unprefixedCursorRejected() {
        runBlocking {
            LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp").enumerate("{}", "50")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeOffsetCursorRejected() {
        runBlocking {
            LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp").enumerate("{}", "offset:-5")
        }
    }

    @Test
    fun staleOffsetCursorReturnsEmptyPage() {
        runBlocking {
            val adapter = LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp", pageSize = 2)
            val total = adapter.enumerate("{}", null).totalKnown
            val page = adapter.enumerate("{}", "offset:${total!! + 5}")
            assertTrue(page.items.isEmpty())
            assertNull(page.nextCursor)
            assertEquals(total, page.totalKnown)
        }
    }

    @Test
    fun doneSentinelCursorReturnsEmptyPage() {
        runBlocking {
            val page = LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp").enumerate("{}", ImportCoordinator.CURSOR_DONE)
            assertTrue(page.items.isEmpty())
            assertNull(page.nextCursor)
        }
    }

    @Test
    fun cursorLiteralsMatchSharedVector() {
        val meta = Vectors.load("fixture-enumeration.json").getJSONObject("meta")
        assertEquals(meta.getString("cursor_prefix"), LocalFilesSourceAdapter.CURSOR_PREFIX)
        assertEquals(meta.getString("done_sentinel"), ImportCoordinator.CURSOR_DONE)
    }

    @Test
    fun nullCursorStartsAtOffsetZero() {
        runBlocking {
            val page = LocalFilesSourceAdapter(Vectors.fixtureRoot(), "fp", pageSize = 2).enumerate("{}", null)
            assertEquals(2, page.items.size)
            assertEquals("offset:2", page.nextCursor)
        }
    }

    @Test
    fun mediaExtensionWhitelistMatchesVector() {
        val vector = Vectors.load("fixture-enumeration.json")
        val expected = (0 until vector.getJSONArray("media_extensions").length())
            .map { vector.getJSONArray("media_extensions").getString(it) }
            .toSet()
        assertEquals(expected, LocalFilesSourceAdapter.MEDIA_EXTENSIONS)
    }

    @Test
    fun uppercaseExtensionAccepted() {
        val root = File(Vectors.fixtureRoot(), "../smoke-v1-case-tmp")
        val dir = File(root.path).canonicalFile
        dir.mkdirs()
        val upper = File(dir, "UPPER.JPG")
        Vectors.fixture("F01/baseline.jpg").copyTo(upper, overwrite = true)
        try {
            val items = runBlocking {
                LocalFilesSourceAdapter(dir, "fp", pageSize = 10)
                    .enumerate("{}", LocalFilesSourceAdapter.CURSOR_PREFIX + "0").items
            }
            assertTrue(items.any { it.remoteKey == "UPPER.JPG" })
        } finally {
            upper.delete()
            dir.delete()
        }
    }
}
