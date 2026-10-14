package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NasPathVectorTest {

    @Test
    fun allPathCasesMatch() {
        val cases = Vectors.load("nas-path.json").getJSONArray("cases")
        assertEquals(17, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val expected = c.getJSONObject("expected")
            val actual = NasPath.normalize(c.getString("input"))
            if (expected.getBoolean("ok")) {
                assertEquals(id, expected.getString("path"), actual)
            } else {
                assertNull(id, actual)
            }
        }
    }
}
