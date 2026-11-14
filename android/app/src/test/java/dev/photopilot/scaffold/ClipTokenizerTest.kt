package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.WordPieceTokenizer
import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ClipTokenizerTest {
    @Test fun casedWordPieceMatchesOfficialTokenizerCases() {
        val folder = File(System.getenv("AI_CLIP_TEST_DIR") ?: "/private/tmp/photopilot-ai-models/multilingual-clip")
        org.junit.Assume.assumeTrue("Provide the pinned multilingual CLIP fixtures", File(folder, "vocab.txt").isFile)
        val tokenizer = WordPieceTokenizer(File(folder, "vocab.txt").readLines())
        val references = Vectors.load("ai-wordpiece.json").getJSONArray("cases")
        assertEquals(20, references.length())
        for (index in 0 until references.length()) {
            val reference = references.getJSONObject(index)
            val ids = reference.getJSONArray("ids")
            assertArrayEquals(reference.getString("text"), LongArray(ids.length()) { ids.getLong(it) }, tokenizer.encode(reference.getString("text")))
        }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("猫".repeat(127)) }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("a ".repeat(127)) }
        assertEquals(128, tokenizer.encode("猫".repeat(126)).size)
    }
}
