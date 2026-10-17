package dev.photopilot.scaffold.data.net

import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.WeatherLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class WttrProvider(
    private val baseUrl: String = BASE_URL,
) : WeatherProvider {

    override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult =
        withContext(Dispatchers.IO) {
            val lat = WeatherLogic.coarsen(latitude)
            val lon = WeatherLogic.coarsen(longitude)
            val url = "$baseUrl/$lat,$lon?format=j1"
            val response = try {
                SimpleHttpsGet.get(url)
            } catch (e: IOException) {
                throw WeatherException(WeatherException.ERR_STALE)
            }
            when (response.status) {
                200 -> parseSnapshot(String(response.body, Charsets.UTF_8), System.currentTimeMillis())
                in 500..599 -> throw WeatherException(WeatherException.ERR_STALE)
                else -> throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            }
        }

    override suspend fun search(query: String, language: String): List<PlaceResult> {
        throw WeatherException(WeatherException.ERR_UNAVAILABLE)
    }

    companion object {
        const val BASE_URL = "https://wttr.in"
        const val ATTRIBUTION_TEXT = "wttr.in"
        const val ATTRIBUTION_URL = "https://wttr.in/"

        internal fun parseSnapshot(body: String, nowMillis: Long): ForecastResult {
            val root = JsonMini.parseObject(body)
            val current = (root["current_condition"] as? List<*>)?.firstOrNull() as? Map<*, *>
                ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            val temperature = when (val raw = current["temp_C"]) {
                is String -> raw.toDoubleOrNull()
                is Double -> raw
                else -> null
            } ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            val weatherCode = when (val raw = current["weatherCode"]) {
                is String -> raw.toLongOrNull()
                is Double -> raw.toLong()
                else -> null
            } ?: throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            return ForecastResult(
                conditionCode = wwoToConditionCode(weatherCode),
                temperatureMilli = Math.round(temperature * 1000.0),
                observedAt = nowMillis,
                attributionText = ATTRIBUTION_TEXT,
                attributionUrl = ATTRIBUTION_URL,
            )
        }

        fun wwoToConditionCode(wwoCode: Long): String = when (wwoCode) {
            113L -> "clear"
            116L -> "partly_cloudy"
            119L, 122L -> "cloudy"
            143L, 248L, 260L -> "fog"
            176L, 263L, 266L, 281L, 283L, 284L, 293L -> "drizzle"
            185L, 296L, 299L, 302L, 311L, 353L -> "rain"
            305L, 308L, 314L, 356L, 359L -> "heavy_rain"
            179L, 182L, 227L, 317L, 320L, 323L, 326L, 329L, 332L, 350L, 362L, 368L, 374L -> "snow"
            230L, 335L, 338L, 365L, 371L, 377L -> "heavy_snow"
            200L, 386L, 389L, 392L, 395L -> "thunderstorm"
            else -> "unknown"
        }
    }
}
