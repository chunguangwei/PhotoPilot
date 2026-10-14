package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.OpenMeteoProvider
import dev.photopilot.scaffold.data.net.WeatherException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenMeteoProviderFixtureTest {

    private lateinit var server: RawFixtureServer

    @Before
    fun start() {
        server = RawFixtureServer { request ->
            val chunked = request.path.startsWith("/v1/forecast") || request.path.startsWith("/v1/search")
            when {
                request.path.startsWith("/v1/forecast") ->
                    RawFixtureServer.RawResponse(
                        200,
                        "OK",
                        body = FORECAST_JSON.toByteArray(Charsets.UTF_8),
                        chunked = chunked,
                    )
                request.path.startsWith("/v1/search") ->
                    RawFixtureServer.RawResponse(
                        200,
                        "OK",
                        body = PLACES_JSON.toByteArray(Charsets.UTF_8),
                        chunked = chunked,
                    )
                request.path.startsWith("/limited") ->
                    RawFixtureServer.RawResponse(
                        429,
                        "Too Many Requests",
                        headers = mapOf("Retry-After" to "90"),
                        body = "{}".toByteArray(),
                    )
                request.path.startsWith("/broken") ->
                    RawFixtureServer.RawResponse(503, "Unavailable", body = "{}".toByteArray())
                else -> RawFixtureServer.RawResponse(404, "Not Found", body = "{}".toByteArray())
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun provider() = OpenMeteoProvider(
        forecastBaseUrl = "http://127.0.0.1:${server.port}",
        geocodingBaseUrl = "http://127.0.0.1:${server.port}",
    )

    @Test
    fun forecastParsesOpenMeteoShape() = runBlocking {
        val result = provider().forecast(39.9, 116.4)
        assertEquals("rain", result.conditionCode)
        assertEquals(22_500L, result.temperatureMilli)
        assertEquals(1_700_020_800_000L, result.observedAt)
        assertEquals("Open-Meteo.com", result.attributionText)
        assertEquals("https://open-meteo.com/", result.attributionUrl)
        Unit
    }

    @Test
    fun geocodingParsesPlaces() = runBlocking {
        val places = provider().search("Beijing", "en")
        assertEquals(2, places.size)
        val beijing = places.first()
        assertEquals("om:2038349", beijing.placeId)
        assertEquals("Beijing", beijing.name)
        assertEquals("Beijing, China", beijing.region)
        assertEquals(399040000, beijing.latE7)
        assertEquals(1164070000, beijing.lonE7)
        Unit
    }

    @Test
    fun rateLimitedCarriesRetryAfter() = runBlocking {
        val provider = OpenMeteoProvider(
            forecastBaseUrl = "http://127.0.0.1:${server.port}/limited",
            geocodingBaseUrl = "http://127.0.0.1:${server.port}",
        )
        try {
            provider.forecast(0.0, 0.0)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_UNAVAILABLE, e.code)
            assertEquals(90_000L, e.retryAfterMs)
        }
        Unit
    }

    @Test
    fun serverErrorMapsToStale() = runBlocking {
        val provider = OpenMeteoProvider(
            forecastBaseUrl = "http://127.0.0.1:${server.port}/broken",
            geocodingBaseUrl = "http://127.0.0.1:${server.port}",
        )
        try {
            provider.forecast(0.0, 0.0)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_STALE, e.code)
            assertNull(e.retryAfterMs)
        }
        Unit
    }

    private companion object {
        const val FORECAST_JSON =
            "{\"latitude\":39.9,\"longitude\":116.4,\"utc_offset_seconds\":28800," +
                "\"current\":{\"time\":\"2023-11-15T12:00\",\"temperature_2m\":22.5,\"weather_code\":61}}"
        const val PLACES_JSON =
            "{\"results\":[" +
                "{\"id\":2038349,\"name\":\"Beijing\",\"latitude\":39.904,\"longitude\":116.407," +
                "\"admin1\":\"Beijing\",\"country\":\"China\"}," +
                "{\"id\":1853909,\"name\":\"Osaka\",\"latitude\":34.69,\"longitude\":135.5," +
                "\"admin1\":\"Osaka\",\"country\":\"Japan\"}" +
                "]}"
    }
}
