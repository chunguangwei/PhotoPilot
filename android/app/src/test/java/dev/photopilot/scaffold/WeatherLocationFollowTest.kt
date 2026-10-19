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
}
