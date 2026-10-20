package dev.photopilot.scaffold.data.net

import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.WeatherLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

interface WeatherProvider {
    suspend fun forecast(latitude: Double, longitude: Double): ForecastResult
    suspend fun search(query: String, language: String): List<PlaceResult>
}

data class ForecastResult(
    val conditionCode: String,
    val temperatureMilli: Long,
    val observedAt: Long,
    val attributionText: String,
    val attributionUrl: String,
)

data class PlaceResult(
    val placeId: String,
    val name: String,
    val region: String,
    val latE7: Int,
    val lonE7: Int,
    val display: WeatherLogic.DisplayFields? = null,
    val language: String? = null,
) {
    fun stored(language: String, translation: PlaceResult?): WeatherLogic.StoredPlace {
        val selectedLanguage = this.language ?: language
        val other = translation?.takeIf { placeId.startsWith("om:") && it.placeId == placeId }
        return WeatherLogic.StoredPlace(placeId,
            if (selectedLanguage == "zh") name else other?.name ?: name,
            if (selectedLanguage == "zh") other?.name ?: name else name,
            latE7, lonE7,
            if (selectedLanguage == "zh") display else other?.display,
            if (selectedLanguage == "zh") other?.display else display)
    }

    companion object {
        fun mergeLocalized(preferred: List<PlaceResult>, other: List<PlaceResult>, language: String): List<PlaceResult> {
            val otherLanguage = if (language == "zh") "en" else "zh"
            val preferredIds = preferred.map { it.placeId }.toSet()
            return preferred.map { it.copy(language = language) } + other
                .filterNot { it.placeId.startsWith("om:") && it.placeId in preferredIds }
                .map { it.copy(language = otherLanguage) }
        }
    }
}

class WeatherException(val code: String, val retryAfterMs: Long? = null) : IOException("weather: $code") {

    companion object {
        const val ERR_UNAVAILABLE = "WEATHER_UNAVAILABLE"
        const val ERR_STALE = "WEATHER_STALE"
    }
}

class OpenMeteoProvider(
    private val forecastBaseUrl: String = FORECAST_URL,
    private val geocodingBaseUrl: String = GEOCODING_URL,
) : WeatherProvider {

    override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult =
        withContext(Dispatchers.IO) {
            val lat = WeatherLogic.coarsen(latitude)
            val lon = WeatherLogic.coarsen(longitude)
            val url = "$forecastBaseUrl/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&timezone=auto"
            val response = SimpleHttpsGet.get(url)
            when (response.status) {
                200 -> parseForecast(String(response.body, Charsets.UTF_8))
                429 -> throw WeatherException(
                    WeatherException.ERR_UNAVAILABLE,
                    retryAfterMs = retryAfterMs(response.headers),
                )
                in 500..599 -> throw WeatherException(WeatherException.ERR_STALE)
                else -> throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            }
        }

    override suspend fun search(query: String, language: String): List<PlaceResult> =
        withContext(Dispatchers.IO) {
            val results = searchOnce(query, language)
            if (results.isNotEmpty()) {
                results
            } else {
                val fallback = WeatherLogic.searchFallbackQuery(query)
                if (fallback == null) results else searchOnce(fallback, language)
            }
        }

    private fun searchOnce(query: String, language: String): List<PlaceResult> {
        val encoded = query.trim().toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                c.toString()
            } else {
                "%%%02X".format(byte.toInt() and 0xFF)
            }
        }
        val url = "$geocodingBaseUrl/v1/search?name=$encoded&count=$SEARCH_COUNT&language=$language"
        val response = SimpleHttpsGet.get(url)
        return when (response.status) {
            200 -> parsePlaces(String(response.body, Charsets.UTF_8))
            429 -> throw WeatherException(
                WeatherException.ERR_UNAVAILABLE,
                retryAfterMs = retryAfterMs(response.headers),
            )
            in 500..599 -> throw WeatherException(WeatherException.ERR_STALE)
            else -> throw WeatherException(WeatherException.ERR_UNAVAILABLE)
        }
    }

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.let { it * 1000L }

    private fun parseForecast(body: String): ForecastResult {
        val root = JsonMini.parseObject(body)
        val current = root["current"] as? Map<*, *>
            ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
        val temperature = (current["temperature_2m"] as? Double)
            ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
        val weatherCode = (current["weather_code"] as? Double)?.toLong()
            ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
        val timeText = current["time"] as? String
            ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
        val offsetSeconds = (root["utc_offset_seconds"] as? Double)?.toLong() ?: 0L
        val observedAt = parseIsoLocal(timeText) - offsetSeconds * 1000L
        return ForecastResult(
            conditionCode = WeatherLogic.wmoToConditionCode(weatherCode),
            temperatureMilli = Math.round(temperature * 1000.0),
            observedAt = observedAt,
            attributionText = ATTRIBUTION_TEXT,
            attributionUrl = ATTRIBUTION_URL,
        )
    }

    private fun parsePlaces(body: String): List<PlaceResult> {
        val root = JsonMini.parseObject(body)
        val results = root["results"] as? List<*> ?: return emptyList()
        return results.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? Double)?.toLong() ?: return@mapNotNull null
            val name = map["name"] as? String ?: return@mapNotNull null
            val lat = (map["latitude"] as? Double) ?: return@mapNotNull null
            val lon = (map["longitude"] as? Double) ?: return@mapNotNull null
            val admin1 = map["admin1"] as? String
            val admin2 = (map["admin2"] as? String)?.takeIf { it != admin1 }
            val region = listOfNotNull(
                admin2,
                admin1,
                map["country"] as? String,
            ).joinToString(", ")
            PlaceResult(
                display = WeatherLogic.DisplayFields(locality = name, subAdminArea = admin2,
                    adminArea = admin1, country = map["country"] as? String),
                placeId = "om:$id",
                name = name,
                region = region,
                latE7 = Math.round(lat * 10_000_000.0).toInt(),
                lonE7 = Math.round(lon * 10_000_000.0).toInt(),
            )
        }
    }

    private fun parseIsoLocal(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(value)
            ?.time
            ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)

    companion object {
        const val FORECAST_URL = "https://api.open-meteo.com"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com"
        const val SEARCH_COUNT = 8
        const val ATTRIBUTION_TEXT = "Open-Meteo.com"
        const val ATTRIBUTION_URL = "https://open-meteo.com/"
    }
}
