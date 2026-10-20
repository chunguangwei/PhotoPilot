package dev.photopilot.scaffold.data.net

import android.util.Log

class FallbackWeatherProvider(
    private val forecast: List<WeatherProvider>,
    private val search: List<WeatherProvider>,
    private val logger: (String) -> Unit = { Log.w(TAG, it) },
) : WeatherProvider {

    override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult {
        var lastError: Exception? = null
        for (provider in forecast) {
            try {
                return provider.forecast(latitude, longitude)
            } catch (e: Exception) {
                logger("forecast failed via ${provider.javaClass.simpleName}: ${e.javaClass.simpleName}")
                lastError = e
            }
        }
        throw lastError ?: WeatherException(WeatherException.ERR_UNAVAILABLE)
    }

    override suspend fun search(query: String, language: String): List<PlaceResult> {
        var lastError: Exception? = null
        for (provider in search) {
            try {
                val results = provider.search(query, language)
                if (results.isNotEmpty()) return results
                logger("search empty via ${provider.javaClass.simpleName}")
            } catch (e: Exception) {
                logger("search failed via ${provider.javaClass.simpleName}: ${e.javaClass.simpleName}")
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return emptyList()
    }

    companion object {
        const val TAG = "PhotoPilotWeather"
    }
}
