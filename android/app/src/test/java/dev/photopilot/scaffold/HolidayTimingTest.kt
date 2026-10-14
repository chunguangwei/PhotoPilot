package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.HolidayTiming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidayTimingTest {

    private fun nullableInt(entry: JSONObject, key: String): Int? =
        if (entry.isNull(key)) null else entry.getInt(key)

    private fun rule(entry: JSONObject): HolidayTiming.Rule = HolidayTiming.Rule(
        id = entry.optString("id"),
        enabled = entry.optInt("enabled", 1) != 0,
        position = entry.optLong("position", 0L),
        startDate = entry.getString("start_date"),
        endDate = entry.getString("end_date"),
        startTime = nullableInt(entry, "start_time"),
        endTime = nullableInt(entry, "end_time"),
    )

    private fun rules(array: JSONArray): List<HolidayTiming.Rule> =
        (0 until array.length()).map { rule(array.getJSONObject(it)) }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("holiday-windows.json").getJSONArray("cases")
        assertEquals(30, cases.length())
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            when (case.getString("scenario")) {
                "active" -> assertEquals(
                    id,
                    case.getBoolean("expected"),
                    HolidayTiming.isActive(
                        rule(case.getJSONObject("rule")),
                        case.getString("date"),
                        case.getInt("minute"),
                    ),
                )
                "pick" -> {
                    val expected = if (case.isNull("expected")) null else case.getString("expected")
                    assertEquals(
                        id,
                        expected,
                        HolidayTiming.pick(
                            rules(case.getJSONArray("rules")),
                            case.getString("date"),
                            case.getInt("minute"),
                        ),
                    )
                }
                "overlap" -> assertEquals(
                    id,
                    case.getBoolean("expected"),
                    HolidayTiming.overlap(
                        rule(case.getJSONObject("a")),
                        rule(case.getJSONObject("b")),
                    ),
                )
                else -> assertTrue("unknown scenario in $id", false)
            }
        }
    }
}
