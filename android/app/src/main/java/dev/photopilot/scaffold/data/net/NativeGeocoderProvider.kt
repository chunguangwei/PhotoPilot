package dev.photopilot.scaffold.data.net

import android.content.Context
import android.location.Geocoder
import android.location.Address
import java.util.Locale
import java.security.MessageDigest
import java.text.Normalizer
import dev.photopilot.scaffold.data.logic.WeatherLogic
import dev.photopilot.scaffold.data.logic.JsonMini
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
                Geocoder(context, Locale.forLanguageTag(language)).getFromLocationName(query, MAX_RESULTS)
            } catch (e: IOException) {
                throw WeatherException(WeatherException.ERR_STALE)
            } ?: emptyList()
            addresses.mapNotNull { address ->
                val lat = WeatherLogic.coarsen(address.latitude)
                val lon = WeatherLogic.coarsen(address.longitude)
                val latE7 = Math.round(lat * 10_000_000.0).toInt()
                val lonE7 = Math.round(lon * 10_000_000.0).toInt()
                val fields = displayFields(address)
                val name = fields.locality?.takeIf { it.isNotBlank() }
                    ?: fields.subLocality?.takeIf { it.isNotBlank() }
                    ?: fields.subAdminArea?.takeIf { it.isNotBlank() }
                    ?: fields.adminArea?.takeIf { it.isNotBlank() }
                    ?: fields.country?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val region = WeatherLogic.context(fields, language)
                PlaceResult(
                    display = fields,
                    placeId = searchPlaceId(language, fields, latE7, lonE7),
                    name = name,
                    region = region,
                    latE7 = latE7,
                    lonE7 = lonE7,
                )
            }.distinctBy { it.placeId }
        }

    companion object {
        fun searchPlaceId(language: String, fields: WeatherLogic.DisplayFields, latE7: Int, lonE7: Int): String {
            val identity = listOf(Locale.forLanguageTag(language).toLanguageTag(), fields.locality,
                fields.subLocality, fields.subAdminArea, fields.adminArea, fields.country,
                latE7.toString(), lonE7.toString()).map {
                Normalizer.normalize(it.orEmpty().trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
            }.let { JsonMini.toJson(it) }
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "native:$digest"
        }


        fun displayFields(address: Address) = WeatherLogic.DisplayFields(
            address.locality, address.subLocality, address.subAdminArea, address.adminArea, address.countryName,
        )

        const val MAX_RESULTS = 8
    }
}
