package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.WeatherInlineLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherInlineLayoutTest {
    @Test
    fun wideRowKeepsBothNaturalWidths() {
        val row = WeatherInlineLayout.allocate(800, 320, 180, 80, 8)
        assertEquals(320, row.place)
        assertEquals(180, row.weather)
        assertEquals(8, row.gap)
    }

    @Test
    fun narrowLargeTextKeepsTemperatureAndVisibleLocation() {
        val row = WeatherInlineLayout.allocate(288, 760, 480, 132, 8)
        assertTrue(row.weather >= 132)
        assertTrue(row.place >= 80)
        assertTrue(row.place + row.weather + row.gap <= 288)
    }

    @Test
    fun shortPlaceLetsWeatherUseRemainingSpace() {
        val row = WeatherInlineLayout.allocate(288, 52, 480, 132, 8)
        assertEquals(52, row.place)
        assertEquals(228, row.weather)
    }

    @Test
    fun largeTemperatureTakesPriorityWithoutZeroLocation() {
        val row = WeatherInlineLayout.allocate(288, 760, 480, 220, 8)
        assertEquals(220, row.weather)
        assertEquals(60, row.place)
    }

    @Test
    fun hiddenPlaceDoesNotReserveWhitespace() {
        val row = WeatherInlineLayout.allocate(288, 0, 180, 100, 8)
        assertEquals(0, row.place)
        assertEquals(0, row.gap)
        assertEquals(180, row.weather)
    }
}
