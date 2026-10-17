package dev.photopilot.scaffold.data.net

import android.content.Context
import android.location.Geocoder
import dev.photopilot.scaffold.data.logic.WeatherLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class NativeGeocoderProvider(
    private val context: Context,
) : WeatherProvider {

    override suspend fun forecast(latitude: Double, longitude: Double): ForecastResult {
        throw WeatherException(WeatherException.ERR_UNAVAILABLE)
    }

    @Suppress("DEPRECATION")
    override suspend fun search(query: String, language: String): List<PlaceResult> =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) throw WeatherException(WeatherException.ERR_UNAVAILABLE)
            val addresses = try {
                Geocoder(context).getFromLocationName(query, MAX_RESULTS)
            } catch (e: IOException) {
                throw WeatherException(WeatherException.ERR_STALE)
            } ?: emptyList()
            addresses.mapNotNull { address ->
                val lat = WeatherLogic.coarsen(address.latitude)
                val lon = WeatherLogic.coarsen(address.longitude)
                val latE7 = Math.round(lat * 10_000_000.0).toInt()
                val lonE7 = Math.round(lon * 10_000_000.0).toInt()
                val name = address.locality
                    ?: address.subAdminArea
                    ?: address.adminArea
                    ?: address.featureName
                if (name.isNullOrBlank()) return@mapNotNull null
                val region = listOfNotNull(address.adminArea, address.countryName)
                    .filter { it.isNotBlank() && it != name }
                    .distinct()
                    .joinToString(", ")
                PlaceResult(
                    placeId = "geo:$latE7,$lonE7",
                    name = name,
                    region = region,
                    latE7 = latE7,
                    lonE7 = lonE7,
                )
            }.distinctBy { it.placeId }
        }

    companion object {
        const val MAX_RESULTS = 8
    }
}
