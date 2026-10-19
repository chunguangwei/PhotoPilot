package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.WeatherLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLocationFollowTest {

    @Test
    fun followContractVectors() {
        val cases = Vectors.load("weather-location-follow.json").getJSONArray("cases")
        assertTrue("expected at least 8 vectors", cases.length() >= 8)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val storedFlag = if (case.isNull("stored_follow_location")) {
                null
            } else {
                case.getString("stored_follow_location")
            }
            val selectedPlaceId = if (case.isNull("selected_place_id")) {
                null
            } else {
                case.getString("selected_place_id")
            }
            val follows = WeatherLogic.followsLocation(storedFlag, selectedPlaceId)
            assertEquals(id, case.getBoolean("follows_location"), follows)
            assertEquals(
                id,
                case.getBoolean("should_locate_on_playback"),
                WeatherLogic.shouldLocateOnPlayback(case.getBoolean("weather_enabled"), follows),
            )
        }
    }

    @Test
    fun preserveContractVectors() {
        val cases = Vectors.load("weather-location-preserve.json").getJSONArray("cases")
        assertTrue("expected at least 3 vectors", cases.length() >= 3)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val existing = if (case.isNull("existing")) {
                null
            } else {
                place(case.getJSONObject("existing"))
            }
            val candidate = place(case.getJSONObject("candidate"))
            val result = WeatherLogic.preserveLocatedPlace(existing, candidate)
            assertEquals(id, case.getString("result_place_id"), result.placeId)
            assertEquals(id, case.getString("result_name_zh"), result.nameZh)
            assertEquals(id, case.getString("result_name_en"), result.nameEn)
            assertEquals(id, case.getLong("result_lat_e7"), result.latE7.toLong())
            assertEquals(id, case.getLong("result_lon_e7"), result.lonE7.toLong())
        }
    }

    private fun place(obj: org.json.JSONObject) = WeatherLogic.StoredPlace(
        placeId = obj.getString("place_id"),
        nameZh = obj.getString("name_zh"),
        nameEn = obj.getString("name_en"),
        latE7 = obj.getInt("lat_e7"),
        lonE7 = obj.getInt("lon_e7"),
    )
}
