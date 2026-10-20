package dev.photopilot.scaffold.data.logic

object WeatherLogic {

    const val FRESH_LIMIT_MS = 60L * 60L * 1000L
    const val STALE_LIMIT_MS = 180L * 60L * 1000L
    const val AUTO_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    const val MANUAL_REFRESH_MIN_INTERVAL_MS = 60_000L
    const val KEY_PLACES = "weather.places"
    const val KEY_ENABLED_DEFAULT = "weather.enabled_default"
    const val KEY_UNITS = "weather.units"
    const val KEY_SELECTED_PLACE = "weather.selected_place_id"
    const val KEY_FOLLOW_LOCATION = "weather.follow_location"
    const val PLACE_ID_LOCATION_PREFIX = "geo:"
    const val UNITS_METRIC = "metric"
    const val UNITS_IMPERIAL = "imperial"

    enum class TtlSegment { FRESH, STALE, EXPIRED }

    fun followsLocation(storedFlag: String?, selectedPlaceId: String?): Boolean =
        when (storedFlag) {
            "true" -> true
            "false" -> false
            else -> selectedPlaceId?.startsWith(PLACE_ID_LOCATION_PREFIX) == true
        }

    fun shouldLocateOnPlayback(weatherEnabled: Boolean, followsLocation: Boolean): Boolean =
        weatherEnabled && followsLocation

    fun preserveLocatedPlace(existing: StoredPlace?, candidate: StoredPlace): StoredPlace =
        if (existing != null && existing.placeId == candidate.placeId) existing.copy(
            displayZh = enrich(existing.displayZh, candidate.displayZh, existing.nameZh),
            displayEn = enrich(existing.displayEn, candidate.displayEn, existing.nameEn),
        ) else candidate

    fun segmentOf(observedAt: Long, nowMs: Long): TtlSegment {
        val age = nowMs - observedAt
        return when {
            age <= FRESH_LIMIT_MS -> TtlSegment.FRESH
            age <= STALE_LIMIT_MS -> TtlSegment.STALE
            else -> TtlSegment.EXPIRED
        }
    }

    fun displayDegrees(milliCelsius: Long, imperial: Boolean): Int =
        if (imperial) {
            Math.round(milliCelsius / 1000.0 * 9.0 / 5.0 + 32.0).toInt()
        } else {
            Math.round(milliCelsius / 1000.0).toInt()
        }

    fun coarsen(value: Double): Double = Math.round(value * 10.0) / 10.0

    fun searchFallbackQuery(query: String): String? {
        val trimmed = query.trim()
        val stripped = trimmed
            .replace(Regex("[区县市旗]$"), "")
            .replace(Regex("(?i)\\s+(district|county)$"), "")
        return stripped.takeIf { it.isNotEmpty() && it != trimmed }
    }

    fun wmoToConditionCode(wmoCode: Long): String = when (wmoCode) {
        0L -> "clear"
        1L, 2L -> "partly_cloudy"
        3L -> "cloudy"
        45L, 48L -> "fog"
        51L, 53L, 55L, 56L, 57L -> "drizzle"
        61L, 63L, 66L, 80L, 81L -> "rain"
        65L, 82L -> "heavy_rain"
        71L, 73L, 77L, 85L -> "snow"
        75L, 86L -> "heavy_snow"
        95L, 96L, 99L -> "thunderstorm"
        else -> "unknown"
    }

    fun conditionIcon(conditionCode: String): String = when (conditionCode) {
        "clear" -> "☀️"
        "partly_cloudy" -> "⛅"
        "cloudy" -> "☁️"
        "fog" -> "🌫️"
        "drizzle" -> "🌦️"
        "rain" -> "🌧️"
        "heavy_rain" -> "🌧️"
        "snow" -> "🌨️"
        "heavy_snow" -> "❄️"
        "thunderstorm" -> "⛈️"
        "hail" -> "🌨️"
        "windy" -> "💨"
        "haze" -> "🌫️"
        "sand" -> "🌫️"
        else -> "☁️"
    }

    fun conditionTextRes(conditionCode: String): Int? = when (conditionCode) {
        "clear" -> dev.photopilot.scaffold.R.string.weather_condition_clear
        "partly_cloudy" -> dev.photopilot.scaffold.R.string.weather_condition_partly_cloudy
        "cloudy" -> dev.photopilot.scaffold.R.string.weather_condition_cloudy
        "fog" -> dev.photopilot.scaffold.R.string.weather_condition_fog
        "drizzle" -> dev.photopilot.scaffold.R.string.weather_condition_drizzle
        "rain" -> dev.photopilot.scaffold.R.string.weather_condition_rain
        "heavy_rain" -> dev.photopilot.scaffold.R.string.weather_condition_heavy_rain
        "thunderstorm" -> dev.photopilot.scaffold.R.string.weather_condition_thunderstorm
        "sleet" -> dev.photopilot.scaffold.R.string.weather_condition_sleet
        "snow" -> dev.photopilot.scaffold.R.string.weather_condition_snow
        "heavy_snow" -> dev.photopilot.scaffold.R.string.weather_condition_heavy_snow
        "hail" -> dev.photopilot.scaffold.R.string.weather_condition_hail
        "windy" -> dev.photopilot.scaffold.R.string.weather_condition_windy
        "haze" -> dev.photopilot.scaffold.R.string.weather_condition_haze
        "sand" -> dev.photopilot.scaffold.R.string.weather_condition_sand
        else -> null
    }

    fun attributionText(attributionJson: String?): String? {
        val text = JsonMini.parseObject(attributionJson)["text"] as? String
        return text?.takeIf { it.isNotBlank() }
    }

    fun weatherDataAllowed(attributionJson: String?): Boolean =
        attributionText(attributionJson) != null

    data class DisplayFields(
        val locality: String? = null,
        val subLocality: String? = null,
        val subAdminArea: String? = null,
        val adminArea: String? = null,
        val country: String? = null,
    )

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun compact(fields: DisplayFields?, fallback: String, language: String): String {
        if (fields == null) return fallback.trim()
        val city = clean(fields.locality)
        val district = clean(fields.subLocality)?.takeUnless { it.equals(city, ignoreCase = true) }
        if (city != null) return if (language == "zh") {
            listOfNotNull(city, district).joinToString(" · ")
        } else {
            listOfNotNull(district, city).joinToString(", ")
        }
        return clean(fields.subLocality) ?: clean(fields.subAdminArea) ?: clean(fields.adminArea)
            ?: clean(fields.country) ?: fallback.trim()
    }

    fun context(fields: DisplayFields?, language: String): String {
        if (fields == null) return ""
        val shown = compact(fields, "", language)
        val values = listOfNotNull(clean(fields.subAdminArea), clean(fields.adminArea), clean(fields.country))
            .filterNot { it.equals(clean(fields.locality), true) || it.equals(clean(fields.subLocality), true) || it == shown }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        return values.joinToString(", ")
    }

    private fun displayFields(place: StoredPlace, language: String): DisplayFields? {
        val preferred = if (language == "zh") place.displayZh else place.displayEn
        val other = if (language == "zh") place.displayEn else place.displayZh
        return preferred?.takeIf { compact(it, "", language).isNotBlank() } ?: other
    }

    fun displayName(place: StoredPlace, language: String): String = compact(
        displayFields(place, language),
        if (language == "zh") place.nameZh.ifBlank { place.nameEn } else place.nameEn.ifBlank { place.nameZh },
        language,
    )

    fun detailedName(place: StoredPlace, language: String): String {
        val fields = displayFields(place, language)
        val other = if (language == "zh") place.displayEn else place.displayZh
        val region = context(fields, language).ifBlank { context(other, language) }
        return listOf(displayName(place, language), region).filter { it.isNotBlank() }
            .joinToString(if (language == "zh") " · " else ", ")
    }

    private fun enrich(old: DisplayFields?, fresh: DisplayFields?, rawName: String): DisplayFields? {
        if (fresh == null) return old
        if (old == null) return fresh.takeIf {
            clean(it.locality) != null || clean(it.subLocality) != null ||
                compact(it, "", "en").equals(rawName.trim(), ignoreCase = true)
        }
        val oldCity = clean(old.locality)
        val newCity = clean(fresh.locality)
        val oldDistrict = clean(old.subLocality)
        val newDistrict = clean(fresh.subLocality)
        if (oldCity != null && newCity != null && !oldCity.equals(newCity, ignoreCase = true)) return old
        if (oldDistrict != null && newDistrict != null && !oldDistrict.equals(newDistrict, ignoreCase = true)) return old
        if (oldCity == null && newCity != null && oldDistrict != null &&
            !oldDistrict.equals(newDistrict, ignoreCase = true)) return old
        if (oldDistrict == null && newDistrict != null && oldCity != null &&
            !oldCity.equals(newCity, ignoreCase = true)) return old
        return DisplayFields(
            oldCity ?: newCity,
            oldDistrict ?: newDistrict,
            clean(old.subAdminArea) ?: clean(fresh.subAdminArea),
            clean(old.adminArea) ?: clean(fresh.adminArea),
            clean(old.country) ?: clean(fresh.country),
        )
    }

    fun mayApplyAutoLocation(startId: String?, currentId: String?, follows: Boolean): Boolean =
        follows && startId == currentId

    data class StoredPlace(val placeId: String,
        val nameZh: String,
        val nameEn: String,
        val latE7: Int,
        val lonE7: Int,
        val displayZh: DisplayFields? = null,
        val displayEn: DisplayFields? = null,
    ) {
        val latitude: Double get() = latE7 / 10_000_000.0
        val longitude: Double get() = lonE7 / 10_000_000.0
    }

    fun encodePlaces(places: Map<String, StoredPlace>): String =
        JsonMini.toJson(
            places.mapValues { (_, p) ->
                mapOf(
                    "nameZh" to p.nameZh,
                    "nameEn" to p.nameEn,
                    "latE7" to p.latE7.toDouble(),
                    "lonE7" to p.lonE7.toDouble(),
                    "displayZh" to encodeDisplay(p.displayZh),
                    "displayEn" to encodeDisplay(p.displayEn),
                )
            },
        )

    private fun encodeDisplay(fields: DisplayFields?): Map<String, String?>? = fields?.let {
        mapOf("locality" to it.locality, "subLocality" to it.subLocality,
            "subAdminArea" to it.subAdminArea, "adminArea" to it.adminArea, "country" to it.country)
    }

    private fun decodeDisplay(value: Any?): DisplayFields? {
        val fields = value as? Map<*, *> ?: return null
        return DisplayFields(fields["locality"] as? String, fields["subLocality"] as? String,
            fields["subAdminArea"] as? String, fields["adminArea"] as? String, fields["country"] as? String)
    }

    fun decodePlaces(json: String?): Map<String, StoredPlace> {
        val root = JsonMini.parseObject(json)
        return root.mapNotNull { (key, value) ->
            val map = value as? Map<*, *> ?: return@mapNotNull null
            val nameZh = map["nameZh"] as? String ?: return@mapNotNull null
            val nameEn = map["nameEn"] as? String ?: return@mapNotNull null
            val latE7 = (map["latE7"] as? Double)?.toInt() ?: return@mapNotNull null
            val lonE7 = (map["lonE7"] as? Double)?.toInt() ?: return@mapNotNull null
            key to StoredPlace(
                placeId = key,
                nameZh = nameZh,
                nameEn = nameEn,
                latE7 = latE7,
                lonE7 = lonE7,
                displayZh = decodeDisplay(map["displayZh"]),
                displayEn = decodeDisplay(map["displayEn"]),
            )
        }.toMap()
    }
}
