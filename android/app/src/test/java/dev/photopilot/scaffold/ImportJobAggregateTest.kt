package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.db.enumFromWire
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.logic.ImportJobAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportJobAggregateTest {

    private fun signalFromWire(wire: String): ImportJobAggregate.Signal =
        when (wire) {
            "none" -> ImportJobAggregate.Signal.NONE
            "user_cancel" -> ImportJobAggregate.Signal.USER_CANCEL
            "fatal_error" -> ImportJobAggregate.Signal.FATAL_ERROR
            "user_pause" -> ImportJobAggregate.Signal.USER_PAUSE
            "auto_pause" -> ImportJobAggregate.Signal.AUTO_PAUSE
            else -> error("unknown signal: $wire")
        }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("import-job-aggregate.json").getJSONArray("cases")
        assertTrue("expected at least 18 vectors", cases.length() >= 18)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val items = case.getJSONArray("items")
            val states = (0 until items.length()).map {
                enumFromWire<ImportItemState>(items.getString(it))
            }
            val actual = ImportJobAggregate.derive(
                states,
                signalFromWire(case.getString("signal")),
                enumerationComplete = case.optBoolean("enumeration_complete", true),
            )
            assertEquals(id, enumFromWire<ImportJobState>(case.getString("expected")), actual)
        }
    }

    @Test
    fun countByStateGroupsItems() {
        val counts = ImportJobAggregate.countByState(
            listOf(
                ImportItemState.IMPORTED,
                ImportItemState.IMPORTED,
                ImportItemState.FAILED,
            ),
        )
        assertEquals(2, counts[ImportItemState.IMPORTED])
        assertEquals(1, counts[ImportItemState.FAILED])
    }
}
