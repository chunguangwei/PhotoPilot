package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.AddInclude
import dev.photopilot.scaffold.data.logic.RuleChange
import dev.photopilot.scaffold.data.logic.SaveIncludeRow
import dev.photopilot.scaffold.data.logic.SaveRuleRow
import dev.photopilot.scaffold.data.logic.SelectionSaveInput
import dev.photopilot.scaffold.data.logic.SelectionSavePlanner
import dev.photopilot.scaffold.data.logic.SelectionSaveRequest
import dev.photopilot.scaffold.data.logic.SelectionSaveResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSavePlannerTest {

    private fun includeRows(array: org.json.JSONArray): List<SaveIncludeRow> =
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            SaveIncludeRow(
                ref = entry.getString("ref"),
                manualOrder = if (entry.isNull("manual_order")) null else entry.getLong("manual_order"),
                addedAt = entry.optLong("added_at", 0L),
            )
        }

    private fun ruleRows(array: org.json.JSONArray): List<SaveRuleRow> =
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            SaveRuleRow(
                collectionId = entry.getString("collection"),
                recursive = entry.getBoolean("recursive"),
                includeFuture = entry.getBoolean("include_future"),
            )
        }

    private fun stringList(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun request(json: JSONObject): SelectionSaveRequest {
        val addIncludes = json.getJSONArray("add_includes").let { array ->
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                AddInclude(ref = entry.getString("ref"), addedAt = entry.getLong("added_at"))
            }
        }
        val ruleChanges = json.getJSONArray("rule_changes").let { array ->
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                RuleChange(
                    collectionId = entry.getString("collection"),
                    recursive = entry.getBoolean("recursive"),
                    includeFuture = entry.getBoolean("include_future"),
                    confirmed = entry.getBoolean("confirmed"),
                    members = stringList(entry.getJSONArray("members")),
                    addedAt = entry.getLong("added_at"),
                )
            }
        }
        return SelectionSaveRequest(
            addIncludes = addIncludes,
            removeRefs = stringList(json.getJSONArray("remove_refs")),
            addExcludes = stringList(json.getJSONArray("add_excludes")),
            ruleChanges = ruleChanges,
            removeRules = stringList(json.getJSONArray("remove_rules")),
        )
    }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("selection-save.json").getJSONArray("cases")
        assertTrue("expected at least 10 vectors", cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val result = SelectionSavePlanner.plan(
                SelectionSaveInput(
                    currentVersion = case.getLong("current_version"),
                    baseVersion = case.getLong("base_version"),
                    orderMode = case.getString("order_mode"),
                    existingIncludes = includeRows(case.getJSONArray("existing_includes")),
                    existingExcludes = stringList(case.getJSONArray("existing_excludes")),
                    existingRules = ruleRows(case.getJSONArray("existing_rules")),
                    request = request(case.getJSONObject("request")),
                ),
            )
            val expected = case.getJSONObject("expected")
            when (expected.getString("result")) {
                "conflict" -> {
                    assertTrue(id, result is SelectionSaveResult.Conflict)
                    assertEquals(
                        id,
                        expected.getString("error"),
                        (result as SelectionSaveResult.Conflict).error,
                    )
                }
                "rejected" -> assertTrue(id, result is SelectionSaveResult.Rejected)
                else -> {
                    assertTrue(id, result is SelectionSaveResult.Ok)
                    val ok = result as SelectionSaveResult.Ok
                    assertEquals(id, expected.getLong("new_version"), ok.newVersion)
                    assertEquals(
                        id,
                        includeRows(expected.getJSONArray("include_rows")).map { it.ref to it.manualOrder },
                        ok.includeRows.map { it.ref to it.manualOrder },
                    )
                    assertEquals(id, stringList(expected.getJSONArray("exclude_rows")), ok.excludeRows)
                    assertEquals(id, ruleRows(expected.getJSONArray("rule_rows")), ok.ruleRows)
                }
            }
        }
    }
}
