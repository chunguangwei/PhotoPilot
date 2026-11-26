package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.remote.RemoteFileCache
import java.nio.file.Files
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class RemoteCacheTest {
    @Test fun leaseProtectsAndReleaseAllowsEviction() = runBlocking {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 10, 0, { 1000 })
        val a = cache.acquire("a", 7, { true }) { ByteArrayInputStream(ByteArray(7)) }
        assertThrows(java.io.IOException::class.java) { runBlocking { cache.acquire("b", 7, { true }) { ByteArrayInputStream(ByteArray(7)) } } }
        a.close()
        cache.acquire("b", 7, { true }) { ByteArrayInputStream(ByteArray(7)) }.close()
        assertTrue(cache.bytesUsed() <= 10)
        dir.deleteRecursively()
        Unit
    }
    @Test fun understatedAndUnknownSizesRemainBounded() = runBlocking {
        for (reported in listOf(null, 1L)) {
            val dir = Files.createTempDirectory("remote-cache").toFile()
            val cache = RemoteFileCache(dir, 10, 0, { 1000 })
            assertThrows(java.io.IOException::class.java) { runBlocking {
                cache.acquire("a", reported, { true }) { ByteArrayInputStream(ByteArray(11)) }
            } }
            assertEquals(0L, cache.bytesUsed())
            dir.deleteRecursively()
        }
    }
    @Test fun cancellingOneConsumerKeepsSharedDownloadAlive() = runBlocking {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 10, 0, { 1000 })
        val opened = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val open = {
            reads.incrementAndGet()
            object : java.io.InputStream() {
                var delivered = false
                override fun read(): Int { opened.countDown(); release.await(); if (delivered) return -1; delivered = true; return 1 }
            }
        }
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { cache.acquire("a", 1, { true }, open) }
        opened.await()
        val second = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { cache.acquire("a", 1, { true }, open) }
        kotlinx.coroutines.delay(50)
        first.cancel()
        release.countDown()
        second.await().close()
        assertEquals(1, reads.get())
        assertEquals(1L, cache.bytesUsed())
        dir.deleteRecursively()
        Unit
    }
    @Test fun systemRemovalOfCacheDirectoryIsRecoverable() = runBlocking {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 10, 0, { 1000 })
        dir.deleteRecursively()
        cache.acquire("a", 7, { true }) { ByteArrayInputStream(ByteArray(7)) }.use { lease -> assertEquals(7L, lease.file.length()) }
        dir.deleteRecursively()
        Unit
    }
    @Test fun revokedDownloadCannotInstall() = runBlocking {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 10, 0, { 1000 })
        var allowed = true
        assertThrows(java.io.IOException::class.java) { runBlocking {
            cache.acquire("a", 7, { allowed }) { allowed = false; ByteArrayInputStream(ByteArray(7)) }
        } }
        assertEquals(0, cache.bytesUsed())
        dir.deleteRecursively()
        Unit
    }
    @Test fun storedThumbnailNeverEvictsExistingCache() {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 10, 0, { 1000 })
        assertTrue(cache.storeBytes("thumb:one", ByteArray(6)))
        assertEquals(6L, cache.readIfPresent("thumb:one")?.length() ?: -1L)
        assertFalse(cache.storeBytes("thumb:two", ByteArray(6)))
        assertEquals(6L, cache.readIfPresent("thumb:one")?.length() ?: -1L)
        assertNull(cache.readIfPresent("thumb:two"))
        assertTrue(cache.bytesUsed() <= 10)
        dir.deleteRecursively()
    }
    @Test fun dedicatedCoverCacheEvictsOnlyReleasedThumbnails() {
        val dir = Files.createTempDirectory("frame-covers").toFile()
        try {
            val cache = RemoteFileCache(dir, 10, 0, { 1_000 })
            assertTrue(cache.storeBytes("old", ByteArray(6), evictThumbnails = true))
            requireNotNull(cache.openThumb("old")).use {
                assertFalse(cache.storeBytes("new", ByteArray(6), evictThumbnails = true))
            }
            assertTrue(cache.storeBytes("new", ByteArray(6), evictThumbnails = true))
            assertNull(cache.readIfPresent("old"))
            assertEquals(6L, cache.readIfPresent("new")?.length())
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test fun storedThumbnailIsOneFilePerDistinctKey() {
        val dir = Files.createTempDirectory("remote-cache").toFile()
        val cache = RemoteFileCache(dir, 100, 0, { 1_000_000 })
        assertTrue(cache.storeBytes("a", ByteArray(3)))
        assertTrue(cache.storeBytes("b", ByteArray(4)))
        assertEquals(3L, cache.readIfPresent("a")?.length() ?: -1L)
        assertEquals(4L, cache.readIfPresent("b")?.length() ?: -1L)
        assertEquals(7L, cache.bytesUsed())
        dir.deleteRecursively()
    }
}
