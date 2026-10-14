package dev.photopilot.scaffold.data

import dev.photopilot.scaffold.data.db.WeatherSnapshotEntity
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.WeatherLogic
import dev.photopilot.scaffold.data.net.WeatherException
import dev.photopilot.scaffold.data.net.WeatherProvider
import dev.photopilot.scaffold.data.repo.WeatherRepository
import dev.photopilot.scaffold.data.repo.WeatherStore

class WeatherRefresher(
    private val provider: WeatherProvider,
    private val repository: WeatherStore,
    private val online: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class RefreshOutcome(
        val updated: Boolean,
        val errorCode: String? = null,
    )

    private val lastManualMs = mutableMapOf<String, Long>()
    private val lastAutoMs = mutableMapOf<String, Long>()
    private val nextAllowedMs = mutableMapOf<String, Long>()

    suspend fun refreshIfDue(
        placeId: String,
        manual: Boolean = false,
    ): RefreshOutcome {
        if (!online()) {
            return RefreshOutcome(updated = false, errorCode = WeatherException.ERR_STALE)
        }
        val now = nowMs()
        if (manual) {
            val last = lastManualMs[placeId] ?: 0L
            if (now - last < WeatherLogic.MANUAL_REFRESH_MIN_INTERVAL_MS) {
                return RefreshOutcome(updated = false)
            }
            lastManualMs[placeId] = now
        }
        val allowed = nextAllowedMs[placeId] ?: 0L
        if (now < allowed) {
            return RefreshOutcome(updated = false)
        }
        if (!manual) {
            val last = lastAutoMs[placeId] ?: 0L
            if (now - last < WeatherLogic.AUTO_REFRESH_INTERVAL_MS) {
                return RefreshOutcome(updated = false)
            }
            val existing = repository.snapshot(placeId)
            if (existing != null && now - existing.fetchedAt < WeatherLogic.AUTO_REFRESH_INTERVAL_MS) {
                lastAutoMs[placeId] = now
                return RefreshOutcome(updated = false)
            }
        }
        val place = repository.place(placeId)
            ?: return RefreshOutcome(updated = false, errorCode = WeatherException.ERR_UNAVAILABLE)
        return try {
            val result = provider.forecast(place.latitude, place.longitude)
            val fetched = nowMs()
            repository.saveSnapshot(
                WeatherSnapshotEntity(
                    placeId = placeId,
                    conditionCode = result.conditionCode,
                    temperatureMilli = result.temperatureMilli,
                    observedAt = result.observedAt,
                    fetchedAt = fetched,
                    expiresAt = fetched + WeatherLogic.FRESH_LIMIT_MS,
                    attribution = JsonMini.toJson(
                        mapOf(
                            "text" to result.attributionText,
                            "url" to result.attributionUrl,
                        ),
                    ),
                    createdAt = fetched,
                    updatedAt = fetched,
                ),
            )
            lastAutoMs[placeId] = nowMs()
            RefreshOutcome(updated = true)
        } catch (e: WeatherException) {
            e.retryAfterMs?.let { nextAllowedMs[placeId] = nowMs() + it }
            RefreshOutcome(updated = false, errorCode = e.code)
        }
    }
}
