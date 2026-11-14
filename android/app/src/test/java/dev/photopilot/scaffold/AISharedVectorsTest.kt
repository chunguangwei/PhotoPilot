package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.*
import org.junit.Assert.*
import org.junit.Test

class AISharedVectorsTest {
    @Test fun sharedClassificationCasesMatchAcrossPlatforms() {
        val cases = Vectors.load("ai-frame-classify.json").getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val item = cases.getJSONObject(index)
            val includeScores = item.getJSONArray("includeScores").let { array -> (0 until array.length()).map { array.optDouble(it, Double.NaN).toFloat() } }
            val excludeScores = item.getJSONArray("excludeScores").let { array -> (0 until array.length()).map { array.optDouble(it, Double.NaN).toFloat() } }
            val actual = try {
                val rule = AIRule("测试 test", includeScores.map { "include" }, excludeScores.map { "exclude" }, item.getBoolean("matchAll"), item.getDouble("threshold").toFloat())
                rule.evaluate(item.optDouble("categoryScore", Double.NaN).toFloat(), includeScores, excludeScores)
            } catch (_: IllegalArgumentException) { AIMatch.REJECT }
            val expected = when (item.getString("expected")) { "matched" -> AIMatch.MATCH; "review" -> AIMatch.REVIEW; else -> AIMatch.REJECT }
            assertEquals(item.getString("id"), expected, actual)
        }
    }
    @Test fun versionedStateRoundTripsWithoutLosingOwnershipOrProgress() {
        val configuration = AIConfiguration("frame", 12, "model", "revision", listOf("folder"), true, false, AIRule("海边 beach"), setOf("ai-member"), mapOf("ai-member" to "content:model:1"), mapOf("review" to .18f), "paused", 7, 2)
        assertEquals(configuration, AIConfiguration.parse(configuration.json()))
        assertThrows(IllegalArgumentException::class.java) { AIConfiguration.parse(configuration.json().put("version", 9)) }
    }
}
