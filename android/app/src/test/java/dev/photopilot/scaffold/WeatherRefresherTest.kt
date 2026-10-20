package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.WeatherRefresher
import dev.photopilot.scaffold.data.db.WeatherSnapshotEntity
import dev.photopilot.scaffold.data.logic.WeatherLogic
import dev.photopilot.scaffold.data.net.ForecastResult
import dev.photopilot.scaffold.data.net.PlaceResult
import dev.photopilot.scaffold.data.net.WeatherException
import dev.photopilot.scaffold.data.net.WeatherProvider
import dev.photopilot.scaffold.data.repo.WeatherStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRefresherTest {

    private class FakeProvider(
        val calls: MutableList<Pair<Double, Double>> = mutableListOf(),
        var result: ForecastResult = ForecastResult(
            "clear",
            22_000L,
            1_699_999_000_000L,
            "Data by Open-Meteo.com",
            "https://open-meteo.com/",
        ),
        var error: WeatherException? = null,
        val gate: CompletableDeferred<Unit>? = null,
    ) : WeatherProvider {
        override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult {
            calls += latitude to longitude
            gate?.await()
            error?.let { throw it }
            return result
        }

        override suspend fun search(query: String, language: String): List<PlaceResult> = emptyList()
    }

    private class FakeStore(placeId: String) : WeatherStore {
        var snapshot: WeatherSnapshotEntity? = null
        val place = WeatherLogic.StoredPlace(placeId, "北京", "Beijing", 399040000, 1164070000)

        override suspend fun snapshot(placeId: String): WeatherSnapshotEntity? = snapshot

        override suspend fun saveSnapshot(snapshot: WeatherSnapshotEntity) {
            this.snapshot = snapshot
        }

        override suspend fun place(placeId: String): WeatherLogic.StoredPlace? = place
    }

    private var now = 1_700_000_000_000L

    private fun refresher(
        placeId: String,
        provider: FakeProvider,
        store: FakeStore = FakeStore(placeId),
        online: Boolean = true,
    ): Triple<WeatherRefresher, FakeStore, FakeProvider> {
        val refresher = WeatherRefresher(
            provider = provider,
            repository = store,
            online = { online },
            nowMs = { now },
        )
        return Triple(refresher, store, provider)
    }

    @Test
    fun concurrentAutoRefreshesShareOnePendingRequest() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val provider = FakeProvider(gate = gate)
        val (r, _, _) = refresher("om:1", provider)
        val first = async(start = CoroutineStart.UNDISPATCHED) { r.refreshIfDue("om:1") }
        val second = async(start = CoroutineStart.UNDISPATCHED) { r.refreshIfDue("om:1") }
        try {
            assertEquals(1, provider.calls.size)
        } finally {
            gate.complete(Unit)
        }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.updated })
        assertEquals(1, provider.calls.size)
        Unit
    }

    @Test
    fun offlineSkipsRequestEntirely() = runBlocking {
        val (r, _, provider) = refresher("om:1", FakeProvider(), online = false)
        val outcome = r.refreshIfDue("om:1")
        assertFalse(outcome.updated)
        assertEquals(WeatherException.ERR_STALE, outcome.errorCode)
        assertEquals(0, provider.calls.size)
        Unit
    }

    @Test
    fun manualThrottleBlocksRapidClicks() = runBlocking {
        val (r, _, provider) = refresher("om:1", FakeProvider())
        assertTrue(r.refreshIfDue("om:1", manual = true).updated)
        assertFalse(r.refreshIfDue("om:1", manual = true).updated)
        assertEquals(1, provider.calls.size)
        Unit
    }

    @Test
    fun freshSnapshotIsSharedWithoutNewRequest() = runBlocking {
        val store = FakeStore("om:1").apply {
            snapshot = WeatherSnapshotEntity(
                placeId = "om:1",
                conditionCode = "rain",
                temperatureMilli = 18_000L,
                observedAt = now,
                fetchedAt = now,
                expiresAt = now + 3_600_000L,
                attribution = null,
                createdAt = now,
                updatedAt = now,
            )
        }
        val (r, _, provider) = refresher("om:1", FakeProvider(), store = store)
        assertFalse(r.refreshIfDue("om:1").updated)
        assertEquals(0, provider.calls.size)
        Unit
    }

    @Test
    fun retryAfterIsRespected() = runBlocking {
        val provider = FakeProvider(
            error = WeatherException(WeatherException.ERR_UNAVAILABLE, retryAfterMs = 120_000L),
        )
        val (r, _, _) = refresher("om:1", provider)
        assertEquals(WeatherException.ERR_UNAVAILABLE, r.refreshIfDue("om:1", manual = true).errorCode)
        assertFalse(r.refreshIfDue("om:1", manual = true).updated)
        assertEquals(1, provider.calls.size)
        now += 121_000L
        provider.error = null
        assertTrue(r.refreshIfDue("om:1", manual = true).updated)
        Unit
    }

    @Test
    fun autoIntervalGatesAutoRefresh() = runBlocking {
        val (r, _, provider) = refresher("om:1", FakeProvider())
        assertTrue(r.refreshIfDue("om:1").updated)
        assertFalse(r.refreshIfDue("om:1").updated)
        now += WeatherLogic.AUTO_REFRESH_INTERVAL_MS + 1
        assertTrue(r.refreshIfDue("om:1").updated)
        assertEquals(2, provider.calls.size)
        Unit
    }
}
