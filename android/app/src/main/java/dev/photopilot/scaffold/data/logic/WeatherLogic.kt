package dev.photopilot.scaffold.data.logic

object WeatherLogic {

    const val FRESH_LIMIT_MS = 60L * 60L * 1000L
    const val STALE_LIMIT_MS = 180L * 60L * 1000L
    const val AUTO_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
    const val MANUAL_REFRESH_MIN_INTERVAL_MS = 60_000L
    const val KEY_PLACES = "weather.places"
    const val KEY_ENABLED_DEFAULT = "weather.enabled_default"
    const val KEY_UNITS = "weather.units"
    const val UNITS_METRIC = "metric"
    const val UNITS_IMPERIAL = "imperial"

    enum class TtlSegment { FRESH, STALE, EXPIRED }

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

    data class StoredPlace(        val placeId: String,
        val nameZh: String,
        val nameEn: String,
        val latE7: Int,
        val lonE7: Int,
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
                )
            },
        )

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
            )
        }.toMap()
    }
}
