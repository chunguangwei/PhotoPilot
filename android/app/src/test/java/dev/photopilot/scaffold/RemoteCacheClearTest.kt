package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.remote.RemoteFileCache
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteCacheClearTest {
    @Test fun clearKeepsCancelledWriterAccountedUntilItsFinallyCompletes() = runBlocking {
        val directory = Files.createTempDirectory("cache-clear").toFile()
        val cache = RemoteFileCache(directory, 10, 0, { 1000 })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = async(Dispatchers.IO) {
            cache.acquire("old", 7, { true }) {
                object : InputStream() {
                    override fun read(): Int { entered.countDown(); release.await(); return -1 }
                    override fun close() = Unit
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { entered.await() }
            cache.clear()
            assertThrows(IOException::class.java) { runBlocking { cache.acquire("new", 7, { true }) { ByteArrayInputStream(ByteArray(7)) }.close() } }
        } finally {
            release.countDown()
            writer.cancelAndJoin()
        }
        withTimeout(3000) {
            while (true) {
                try { cache.acquire("new", 7, { true }) { ByteArrayInputStream(ByteArray(7)) }.close(); break }
                catch (_: IOException) { delay(10) }
            }
        }
        assertTrue(cache.bytesUsed() <= 10)
        directory.deleteRecursively()
        Unit
    }
}
