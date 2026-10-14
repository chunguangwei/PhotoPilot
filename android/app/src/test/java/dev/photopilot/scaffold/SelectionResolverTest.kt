package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.DirectoryRuleRef
import dev.photopilot.scaffold.data.logic.ResolvableAsset
import dev.photopilot.scaffold.data.logic.SelectionRef
import dev.photopilot.scaffold.data.logic.SelectionResolveInput
import dev.photopilot.scaffold.data.logic.SelectionResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionResolverTest {

    private fun stringList(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun stringMap(obj: JSONObject): Map<String, List<String>> {
        val result = mutableMapOf<String, List<String>>()
        for (key in obj.keys()) {
            result[key] = stringList(obj.getJSONArray(key))
        }
        return result
    }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("selection-resolve.json").getJSONArray("cases")
        assertTrue("expected at least 12 vectors", cases.length() >= 12)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")

            val selections = case.getJSONArray("selections").let { array ->
                (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    SelectionRef(
                        assetId = if (entry.has("asset")) entry.getString("asset") else null,
                        sourceAssetId = if (entry.has("source")) entry.getString("source") else null,
                        mode = entry.getString("mode"),
                    )
                }
            }
            val rules = case.getJSONArray("rules").let { array ->
                (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    DirectoryRuleRef(
                        collectionId = entry.getString("collection"),
                        recursive = entry.getBoolean("recursive"),
                    )
                }
            }
            val assetsJson = case.getJSONObject("assets")
            val assets = mutableMapOf<String, ResolvableAsset>()
            for (key in assetsJson.keys()) {
                val entry = assetsJson.getJSONObject(key)
                assets[key] = ResolvableAsset(
                    id = key,
                    readiness = entry.getString("readiness"),
                    privacyExcluded = entry.getBoolean("privacy_excluded"),
                    deleted = entry.getBoolean("deleted"),
                    fileExists = entry.getBoolean("file_exists"),
                    policyAllowed = entry.getBoolean("policy_allowed"),
                )
            }

            val resolution = SelectionResolver.resolve(
                SelectionResolveInput(
                    selections = selections,
                    rules = rules,
                    collectionChildren = stringMap(case.getJSONObject("collection_children")),
                    collectionAssets = stringMap(case.getJSONObject("collection_assets")),
                    assets = assets,
                ),
            )

            val expected = case.getJSONObject("expected")
            assertEquals(id, stringList(expected.getJSONArray("candidates")), resolution.candidates)
            assertEquals(id, stringList(expected.getJSONArray("pending")), resolution.pending)
            assertEquals(id, stringList(expected.getJSONArray("allowed")), resolution.allowed)
            assertEquals(id, stringList(expected.getJSONArray("playable")), resolution.playable)
        }
    }
}
