package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.WeatherException
import dev.photopilot.scaffold.data.net.WttrProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WttrProviderFixtureTest {

    private lateinit var server: RawFixtureServer

    @Before
    fun start() {
        server = RawFixtureServer { request ->
            when {
                request.path.startsWith("/ok") ->
                    RawFixtureServer.RawResponse(
                        200,
                        "OK",
                        body = WTTR_JSON.toByteArray(Charsets.UTF_8),
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

    @Test
    fun parseSnapshotSuccess() {
        val result = WttrProvider.parseSnapshot(WTTR_JSON, 1_700_000_000_000L)
        assertEquals("partly_cloudy", result.conditionCode)
        assertEquals(22_500L, result.temperatureMilli)
        assertEquals(1_700_000_000_000L, result.observedAt)
        assertEquals("wttr.in", result.attributionText)
        assertEquals("https://wttr.in/", result.attributionUrl)
    }

    @Test
    fun parseSnapshotMalformedThrowsUnavailable() {
        try {
            WttrProvider.parseSnapshot("not json at all", 0L)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_UNAVAILABLE, e.code)
        }
    }

    @Test
    fun parseSnapshotMissingFieldsThrowsUnavailable() {
        try {
            WttrProvider.parseSnapshot("{\"current_condition\":[{}]}", 0L)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_UNAVAILABLE, e.code)
        }
    }

    @Test
    fun wwoMappingCoversGroups() {
        assertEquals("clear", WttrProvider.wwoToConditionCode(113))
        assertEquals("partly_cloudy", WttrProvider.wwoToConditionCode(116))
        assertEquals("cloudy", WttrProvider.wwoToConditionCode(122))
        assertEquals("fog", WttrProvider.wwoToConditionCode(248))
        assertEquals("drizzle", WttrProvider.wwoToConditionCode(176))
        assertEquals("rain", WttrProvider.wwoToConditionCode(353))
        assertEquals("heavy_rain", WttrProvider.wwoToConditionCode(359))
        assertEquals("snow", WttrProvider.wwoToConditionCode(227))
        assertEquals("heavy_snow", WttrProvider.wwoToConditionCode(371))
        assertEquals("thunderstorm", WttrProvider.wwoToConditionCode(389))
        assertEquals("unknown", WttrProvider.wwoToConditionCode(999))
    }

    @Test
    fun forecastViaHttpParses() = runBlocking {
        val provider = WttrProvider(baseUrl = "http://127.0.0.1:${server.port}/ok")
        val result = provider.forecast(39.94, 116.37)
        assertEquals("partly_cloudy", result.conditionCode)
        assertEquals(22_500L, result.temperatureMilli)
        Unit
    }

    @Test
    fun serverErrorMapsToStale() = runBlocking {
        val provider = WttrProvider(baseUrl = "http://127.0.0.1:${server.port}/broken")
        try {
            provider.forecast(0.0, 0.0)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_STALE, e.code)
        }
        Unit
    }

    @Test
    fun clientErrorMapsToUnavailable() = runBlocking {
        val provider = WttrProvider(baseUrl = "http://127.0.0.1:${server.port}/missing")
        try {
            provider.forecast(0.0, 0.0)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertEquals(WeatherException.ERR_UNAVAILABLE, e.code)
        }
        Unit
    }

    private companion object {
        const val WTTR_JSON =
            "{\"current_condition\":[{" +
                "\"temp_C\":\"22.5\",\"weatherCode\":\"116\"," +
                "\"weatherDesc\":[{\"value\":\"Partly cloudy\"}]}]}"
    }
}
