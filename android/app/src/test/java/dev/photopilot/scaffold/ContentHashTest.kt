package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ContentHash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContentHashTest {

    @Test
    fun sha256OfAbcMatchesKnownVector() {
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun streamsFixtureFileWithoutSecondRead() {
        val file = fixture("F01/baseline.jpg")
        val streamed = ContentHash.stream(file.inputStream())
        assertEquals(
            ContentHash.sha256Hex(file.readBytes()),
            streamed.contentHash,
        )
        assertEquals(file.length(), streamed.bytesWritten)
        assertNotNull(streamed.head64kHash)
    }

    @Test
    fun head64kHashCoversFirst64KiBOnly() {
        val payload = ByteArray(128 * 1024) { it.toByte() }
        val streamed = ContentHash.stream(payload.inputStream())
        val head = payload.copyOfRange(0, 64 * 1024)
        assertEquals(ContentHash.sha256Hex(head), streamed.head64kHash)
        assertEquals(ContentHash.sha256Hex(payload), streamed.contentHash)
    }

    @Test
    fun emptyStreamHasNoHeadHash() {
        val streamed = ContentHash.stream(ByteArray(0).inputStream())
        assertNull(streamed.head64kHash)
        assertEquals(ContentHash.sha256Hex(ByteArray(0)), streamed.contentHash)
    }

    companion object {
        fun fixture(rel: String): File {
            val candidates = listOf(
                File("../../tests/fixtures/smoke-v1", rel),
                File("../../../tests/fixtures/smoke-v1", rel),
            )
            return candidates.firstOrNull { it.isFile }
                ?: error("fixture not found: $rel (tried $candidates)")
        }
    }
}
