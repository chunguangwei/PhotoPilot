package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.imports.NasEnumCursor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NasEnumCursorTest {

    private fun dirsOf(json: JSONObject): List<String> =
        (0 until json.getJSONArray("dirs").length()).map { json.getJSONArray("dirs").getString(it) }

    @Test
    fun enumCursorContractVectors() {
        val cases = Vectors.load("nas-enum-cursor.json").getJSONArray("cases")
        assertTrue("expected at least 6 vectors", cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val stateJson = case.getJSONObject("state")
            val expectedDirs = dirsOf(stateJson)
            val expectedSkip = stateJson.getInt("skip")
            if (case.has("golden")) {
                val parsed = NasEnumCursor.parse(case.getString("golden"), emptyList())
                assertEquals(id, expectedDirs, parsed.dirs)
                assertEquals(id, expectedSkip, parsed.skip)
            }
            if (case.has("legacy")) {
                val parsed = NasEnumCursor.parse(case.getString("legacy"), emptyList())
                assertEquals(id, expectedDirs, parsed.dirs)
                assertEquals(id, expectedSkip, parsed.skip)
            }
            if (case.optBoolean("roundtrip")) {
                val state = NasEnumCursor.State(expectedDirs, expectedSkip)
                val parsed = NasEnumCursor.parse(NasEnumCursor.format(state), emptyList())
                assertEquals(id, state, parsed)
            }
        }
    }

    @Test
    fun iosGoldenFormatParsesAndSerializes() {
        val parsed = NasEnumCursor.parse("queue:{\"dirs\":[\"家庭 照片/2025 春节\"],\"skip\":2}", emptyList())
        assertEquals(listOf("家庭 照片/2025 春节"), parsed.dirs)
        assertEquals(2, parsed.skip)
        val encoded = NasEnumCursor.format(NasEnumCursor.State(listOf("a/b", "c"), 3))
        assertEquals("queue:{\"dirs\":[\"a/b\",\"c\"],\"skip\":3}", encoded)
        val root = JSONObject(encoded.removePrefix("queue:"))
        assertEquals(listOf("a/b", "c"), dirsOf(root))
        assertEquals(3, root.getInt("skip"))
    }
}
