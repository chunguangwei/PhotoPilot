package dev.photopilot.scaffold
import dev.photopilot.scaffold.data.aiframes.SentencePieceTokenizer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.json.JSONObject

class AITokenizerTest {
    @Test fun realTokenizerMatchesUpstreamSentencePieceForChineseEnglishAndUnicode() {
        val root = File(System.getenv("AI_MODEL_TEST_DIR") ?: "/private/tmp/photopilot-ai-models")
        org.junit.Assume.assumeTrue("Set AI_MODEL_TEST_DIR to the downloaded pinned model fixtures", File(root, "tokenizer.model").isFile)
        val tokenizer = SentencePieceTokenizer(File(root, "tokenizer.model").readBytes())
        val cases = JSONObject(File(root, "tokenizer-reference.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val ids = case.getJSONArray("ids")
            assertArrayEquals(case.getString("text"), LongArray(ids.length()) { ids.getLong(it) }, tokenizer.raw(case.getString("text")))
        }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("猫".repeat(100), 64) }
        assertThrows(IllegalArgumentException::class.java) { tokenizer.encode("🙂".repeat(100), 64) }
        assertEquals(64, tokenizer.encode("海边的日落", 64).size)
        assertEquals(1, tokenizer.encode("海边的日落", 64)[4])
    }
}
