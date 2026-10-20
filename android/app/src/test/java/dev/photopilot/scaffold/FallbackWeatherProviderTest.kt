package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.FallbackWeatherProvider
import dev.photopilot.scaffold.data.net.ForecastResult
import dev.photopilot.scaffold.data.net.PlaceResult
import dev.photopilot.scaffold.data.net.WeatherException
import dev.photopilot.scaffold.data.net.WeatherProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackWeatherProviderTest {

    private class FakeProvider(
        private val forecastBehavior: () -> ForecastResult = { throw WeatherException(WeatherException.ERR_UNAVAILABLE) },
        private val searchBehavior: () -> List<PlaceResult> = { throw WeatherException(WeatherException.ERR_UNAVAILABLE) },
    ) : WeatherProvider {
        override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult =
            forecastBehavior()

        override suspend fun search(query: String, language: String): List<PlaceResult> =
            searchBehavior()
    }

    private fun snapshot(code: String) = ForecastResult(
        conditionCode = code,
        temperatureMilli = 20_000L,
        observedAt = 1L,
        attributionText = "fake",
        attributionUrl = "https://example.com/",
    )

    private fun place(id: String) = PlaceResult(
        placeId = id,
        name = "Place",
        region = "Region",
        latE7 = 1,
        lonE7 = 2,
    )

    private fun fallback(
        forecast: List<WeatherProvider>,
        search: List<WeatherProvider>,
    ) = FallbackWeatherProvider(forecast, search, logger = {})

    @Test
    fun forecastFallsBackToSecondaryOnError() = runBlocking {
        val provider = fallback(
            forecast = listOf(
                FakeProvider(),
                FakeProvider(forecastBehavior = { snapshot("clear") }),
            ),
            search = emptyList(),
        )
        assertEquals("clear", provider.forecast(0.0, 0.0).conditionCode)
        Unit
    }

    @Test
    fun forecastThrowsLastErrorWhenAllFail() = runBlocking {
        val provider = fallback(
            forecast = listOf(
                FakeProvider(forecastBehavior = { throw WeatherException(WeatherException.ERR_UNAVAILABLE) }),
                FakeProvider(forecastBehavior = { throw WeatherException(WeatherException.ERR_STALE) }),
            ),
            search = emptyList(),
        )
        try {
            provider.forecast(0.0, 0.0)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_STALE, e.code)
        }
        Unit
    }

    @Test
    fun searchEmptyResultContinuesToNext() = runBlocking {
        val provider = fallback(
            forecast = emptyList(),
            search = listOf(
                FakeProvider(searchBehavior = { emptyList() }),
                FakeProvider(searchBehavior = { listOf(place("geo:1,2")) }),
            ),
        )
        val results = provider.search("x", "en")
        assertEquals(1, results.size)
        assertEquals("geo:1,2", results.first().placeId)
        Unit
    }

    @Test
    fun searchReturnsEmptyWhenAllEmptyWithoutError() = runBlocking {
        val provider = fallback(
            forecast = emptyList(),
            search = listOf(
                FakeProvider(searchBehavior = { emptyList() }),
                FakeProvider(searchBehavior = { emptyList() }),
            ),
        )
        assertTrue(provider.search("x", "en").isEmpty())
        Unit
    }

    @Test
    fun searchThrowsLastErrorWhenAllFail() = runBlocking {
        val provider = fallback(
            forecast = emptyList(),
            search = listOf(
                FakeProvider(searchBehavior = { throw WeatherException(WeatherException.ERR_UNAVAILABLE) }),
                FakeProvider(searchBehavior = { emptyList() }),
            ),
        )
        try {
            provider.search("x", "en")
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_UNAVAILABLE, e.code)
        }
        Unit
    }

    @Test
    fun forecastLogsErrorTypeWithoutLocationOrUrl() = runBlocking {
        val logs = mutableListOf<String>()
        val provider = FallbackWeatherProvider(
            forecast = listOf(
                FakeProvider(forecastBehavior = {
                    throw java.io.IOException("https://weather.example/?latitude=39.9&longitude=116.4")
                }),
                FakeProvider(forecastBehavior = { snapshot("clear") }),
            ),
            search = emptyList(),
            logger = logs::add,
        )
        provider.forecast(39.9, 116.4)
        assertEquals(listOf("forecast failed via FakeProvider: IOException"), logs)
    }

    @Test
    fun searchLogsErrorTypeWithoutQueryOrUrl() = runBlocking {
        val logs = mutableListOf<String>()
        val provider = FallbackWeatherProvider(
            forecast = emptyList(),
            search = listOf(
                FakeProvider(searchBehavior = {
                    throw java.io.IOException("https://weather.example/search?q=PrivateHomeAddress")
                }),
                FakeProvider(searchBehavior = { listOf(place("geo:1,2")) }),
            ),
            logger = logs::add,
        )
        provider.search("PrivateHomeAddress", "en")
        assertEquals(listOf("search failed via FakeProvider: IOException"), logs)
    }

}
