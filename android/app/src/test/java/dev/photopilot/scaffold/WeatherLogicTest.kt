package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.WeatherLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {

    @Test
    fun conditionTextResCoversAllKnownCodes() {
        val known = listOf(
            "clear", "partly_cloudy", "cloudy", "fog", "drizzle", "rain", "heavy_rain",
            "thunderstorm", "sleet", "snow", "heavy_snow", "hail", "windy", "haze", "sand",
        )
        known.forEach { code ->
            assertNotNull(code, WeatherLogic.conditionTextRes(code))
        }
        assertEquals(
            dev.photopilot.scaffold.R.string.weather_condition_clear,
            WeatherLogic.conditionTextRes("clear"),
        )
        assertNull(WeatherLogic.conditionTextRes("unknown"))
        assertNull(WeatherLogic.conditionTextRes("foggy"))
    }

    @Test
    fun attributionGateFollowsPresence() {
        val json = "{\"text\":\"Data by Open-Meteo.com\",\"url\":\"https://open-meteo.com/\"}"
        assertEquals("Data by Open-Meteo.com", WeatherLogic.attributionText(json))
        assertTrue(WeatherLogic.weatherDataAllowed(json))
        assertFalse(WeatherLogic.weatherDataAllowed(null))
        assertFalse(WeatherLogic.weatherDataAllowed("{}"))
        assertFalse(WeatherLogic.weatherDataAllowed("{\"text\":\"\"}"))
        assertFalse(WeatherLogic.weatherDataAllowed("bad-json"))
    }

    @Test
    fun ttlSegmentsByObservedAt() {
        val base = 1_700_000_000_000L
        assertEquals(
            WeatherLogic.TtlSegment.FRESH,
            WeatherLogic.segmentOf(base, base + WeatherLogic.FRESH_LIMIT_MS),
        )
        assertEquals(
            WeatherLogic.TtlSegment.STALE,
            WeatherLogic.segmentOf(base, base + WeatherLogic.FRESH_LIMIT_MS + 1),
        )
        assertEquals(
            WeatherLogic.TtlSegment.STALE,
            WeatherLogic.segmentOf(base, base + WeatherLogic.STALE_LIMIT_MS),
        )
        assertEquals(
            WeatherLogic.TtlSegment.EXPIRED,
            WeatherLogic.segmentOf(base, base + WeatherLogic.STALE_LIMIT_MS + 1),
        )
    }

    @Test
    fun unitConversionKeepsPrecision() {
        assertEquals(22, WeatherLogic.displayDegrees(22_000L, imperial = false))
        assertEquals(72, WeatherLogic.displayDegrees(22_000L, imperial = true))
        assertEquals(32, WeatherLogic.displayDegrees(0L, imperial = true))
        assertEquals(-40, WeatherLogic.displayDegrees(-40_000L, imperial = true))
        assertEquals(98, WeatherLogic.displayDegrees(36_700L, imperial = true))
    }

    @Test
    fun wmoMappingFollowsFrozenTable() {
        assertEquals("clear", WeatherLogic.wmoToConditionCode(0L))
        assertEquals("partly_cloudy", WeatherLogic.wmoToConditionCode(2L))
        assertEquals("cloudy", WeatherLogic.wmoToConditionCode(3L))
        assertEquals("fog", WeatherLogic.wmoToConditionCode(45L))
        assertEquals("drizzle", WeatherLogic.wmoToConditionCode(53L))
        assertEquals("rain", WeatherLogic.wmoToConditionCode(80L))
        assertEquals("heavy_rain", WeatherLogic.wmoToConditionCode(65L))
        assertEquals("snow", WeatherLogic.wmoToConditionCode(85L))
        assertEquals("heavy_snow", WeatherLogic.wmoToConditionCode(86L))
        assertEquals("thunderstorm", WeatherLogic.wmoToConditionCode(95L))
        assertEquals("unknown", WeatherLogic.wmoToConditionCode(4L))
        assertEquals("unknown", WeatherLogic.wmoToConditionCode(999L))
    }

    @Test
    fun coarsenRoundsToTenthDegree() {
        assertEquals(39.9, WeatherLogic.coarsen(39.904), 1e-9)
        assertEquals(116.4, WeatherLogic.coarsen(116.397), 1e-9)
        assertEquals(-33.9, WeatherLogic.coarsen(-33.86), 1e-9)
    }

    @Test
    fun placesRoundTripAndCorruptionSafety() {
        val places = mapOf(
            "om:2038349" to WeatherLogic.StoredPlace("om:2038349", "北京", "Beijing", 399040000, 1164070000),
        )
        val decoded = WeatherLogic.decodePlaces(WeatherLogic.encodePlaces(places))
        assertEquals(places, decoded)
        assertEquals(emptyMap<String, WeatherLogic.StoredPlace>(), WeatherLogic.decodePlaces(null))
        assertEquals(emptyMap<String, WeatherLogic.StoredPlace>(), WeatherLogic.decodePlaces("bad"))
        assertNull(WeatherLogic.decodePlaces("{\"a\":{\"nameZh\":\"x\"}}")["a"])
    }
}
