package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasSyncDiff
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NasSyncDiffVectorTest {

    @Test
    fun allDiffCasesMatch() {
        val cases = Vectors.load("nas-sync-diff.json").getJSONArray("cases")
        assertEquals(8, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val old = revisionMap(c.getJSONObject("old"))
            val new = revisionMap(c.getJSONObject("new"))
            val result = NasSyncDiff.diff(old, new, c.getBoolean("mirror_delete"))
            val expected = c.getJSONObject("expected")
            val keys = expected.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                assertEquals(
                    "$id:$key",
                    expected.getString(key),
                    result[key]?.wire,
                )
            }
            assertEquals(id, expected.length(), result.size)
        }
    }

    private fun revisionMap(obj: JSONObject): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = if (obj.isNull(key)) null else obj.getString(key)
        }
        return out
    }
}
