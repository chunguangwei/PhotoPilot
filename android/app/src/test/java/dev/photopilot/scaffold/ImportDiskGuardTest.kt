package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.LocalImportStore
import dev.photopilot.scaffold.data.remote.DiskReservations
import dev.photopilot.scaffold.data.remote.RemoteFileCache
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImportDiskGuardTest {
    @Test fun cacheAndOrdinaryImportCannotSpendTheSamePhysicalSpace() = runBlocking {
        val root = Files.createTempDirectory("shared-space").toFile()
        val originals = root.resolve("originals").apply { mkdirs() }
        val staging = root.resolve("staging").apply { mkdirs() }
        val cacheDirectory = root.resolve("cache").apply { mkdirs() }
        val available = { 200L - root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val disk = DiskReservations()
        val cache = RemoteFileCache(cacheDirectory, 200, 64, available, disk)
        val store = LocalImportStore(originals, staging, disk, available, 64)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pending = async(Dispatchers.IO) {
            cache.acquire("cache", 100, { true }) {
                object : ByteArrayInputStream(ByteArray(100)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { entered.countDown(); release.await(); return super.read(buffer, offset, length) }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { entered.await() }
            var closed = false
            val input = object : ByteArrayInputStream(ByteArray(100)) { override fun close() { closed = true; super.close() } }
            assertThrows(IOException::class.java) { store.stage("source.part", input, 100) }
            assertTrue(closed)
        } finally { release.countDown() }
        pending.await().close()
        assertTrue(available() >= 64)
        cache.clear()
        assertEquals(100L, store.stage("source.part", ByteArrayInputStream(ByteArray(100)), 100).bytesWritten)
        assertTrue(available() >= 64)
        root.deleteRecursively()
        Unit
    }
    @Test fun ordinaryImportChecksUnknownUnderstatedAndFailedStreams() {
        val root = Files.createTempDirectory("import-space").toFile()
        val originals = root.resolve("originals").apply { mkdirs() }
        val staging = root.resolve("staging").apply { mkdirs() }
        val available = { 200L - root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        val store = LocalImportStore(originals, staging, DiskReservations(), available, 64)
        for (reported in listOf(null, 1L)) {
            assertThrows(IOException::class.java) { store.stage("source.part", ByteArrayInputStream(ByteArray(137)), reported) }
            assertEquals(200L, available())
        }
        val broken = object : InputStream() {
            var first = true
            override fun read(): Int = throw IOException("fixture")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw IOException("fixture")
                first = false
                return 8
            }
        }
        assertThrows(IOException::class.java) { store.stage("broken.part", broken, 100) }
        assertEquals(200L, available())
        assertEquals(100L, store.stage("source.part", ByteArrayInputStream(ByteArray(100)), 100).bytesWritten)
        root.deleteRecursively()
    }
}
