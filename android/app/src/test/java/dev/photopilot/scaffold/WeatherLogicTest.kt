package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.WeatherLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLogicTest {

    @Test
    fun conflictingDistrictCannotLeakIncomingAdministrativeContext() {
        val old = WeatherLogic.StoredPlace("geo:1", "Haidian", "Haidian", 1, 2,
            displayEn = WeatherLogic.DisplayFields(subLocality = "Haidian"))
        val foreign = old.copy(displayEn = WeatherLogic.DisplayFields(locality = "Shanghai", subLocality = "Pudong",
            adminArea = "Shanghai", country = "China"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, foreign))
    }

    @Test
    fun unconfirmedDistrictCannotLeakIncomingAdministrativeContext() {
        val old = WeatherLogic.StoredPlace("geo:1", "Beijing", "Beijing", 1, 2,
            displayEn = WeatherLogic.DisplayFields(locality = "Beijing"))
        val foreign = old.copy(displayEn = WeatherLogic.DisplayFields(subLocality = "Pudong",
            adminArea = "Shanghai", country = "China"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, foreign))
    }

    @Test
    fun districtOnlyRecordNeedsMatchingDistrictBeforeAddingCity() {
        val old = WeatherLogic.StoredPlace("geo:1", "District A", "District A", 1, 2,
            displayEn = WeatherLogic.DisplayFields(subLocality = "District A", country = "Old Country"))
        val candidate = old.copy(displayEn = WeatherLogic.DisplayFields(locality = "City B", country = "New Country"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, candidate))
        val confirmed = candidate.copy(displayEn = candidate.displayEn!!.copy(subLocality = "District A"))
        assertEquals(old.displayEn!!.copy(locality = "City B"),
            WeatherLogic.preserveLocatedPlace(old, confirmed).displayEn)
    }

    @Test
    fun cityOnlyRecordNeedsMatchingCityBeforeAddingDistrict() {
        val old = WeatherLogic.StoredPlace("geo:1", "City A", "City A", 1, 2,
            displayEn = WeatherLogic.DisplayFields(locality = "City A", country = "Old Country"))
        val candidate = old.copy(displayEn = WeatherLogic.DisplayFields(subLocality = "District B", country = "New Country"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, candidate))
        val confirmed = candidate.copy(displayEn = candidate.displayEn!!.copy(locality = "City A"))
        assertEquals(old.displayEn!!.copy(subLocality = "District B"),
            WeatherLogic.preserveLocatedPlace(old, confirmed).displayEn)
    }

    @Test
    fun nativeSearchIdentityDistinguishesSettlementsWithinSameWeatherTile() {
        val union = WeatherLogic.DisplayFields(locality = "Union City", adminArea = "New Jersey", country = "United States")
        val west = union.copy(locality = "West New York")
        val unionId = dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId("en", union, 408000000, -740000000)
        val westId = dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId("en", west, 408000000, -740000000)
        assertFalse(unionId == westId)
        assertTrue(unionId.startsWith("native:"))
        assertEquals(unionId, dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId(
            "en", union.copy(locality = " Union City "), 408000000, -740000000))
        assertFalse(unionId == dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId(
            "zh", union, 408000000, -740000000))
        val separatorA = union.copy(locality = "A\u001fB", subLocality = "C")
        val separatorB = union.copy(locality = "A", subLocality = "B\u001fC")
        assertFalse(dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId("en", separatorA, 1, 2) ==
            dev.photopilot.scaffold.data.net.NativeGeocoderProvider.searchPlaceId("en", separatorB, 1, 2))
        val first = WeatherLogic.StoredPlace(unionId, "Union City", "Union City", 408000000, -740000000,
            displayEn = union)
        val second = first.copy(placeId = westId, nameZh = "West New York", nameEn = "West New York", displayEn = west)
        assertEquals(second, WeatherLogic.preserveLocatedPlace(first, second))
        val all = WeatherLogic.decodePlaces(WeatherLogic.encodePlaces(mapOf(first.placeId to first, second.placeId to second)))
        assertEquals(second, all[westId])
        assertEquals(first, all[unionId])
    }

    @Test
    fun bilingualSearchKeepsEnglishOnlyAndDistinctNamesakes() {
        val english = dev.photopilot.scaffold.data.net.PlaceResult("om:1", "Springfield", "Illinois", 1, 2,
            WeatherLogic.DisplayFields(locality = "Springfield", adminArea = "Illinois"))
        val merged = dev.photopilot.scaffold.data.net.PlaceResult.mergeLocalized(emptyList(), listOf(english), "zh")
        assertEquals("en", merged.single().language)
        val stored = merged.single().stored("zh", null)
        assertEquals(english.display, stored.displayEn)
        assertNull(stored.displayZh)
        val preferred = english.copy(name = "斯普林菲尔德")
        val namesake = english.copy(placeId = "om:2", region = "Missouri")
        val both = dev.photopilot.scaffold.data.net.PlaceResult.mergeLocalized(listOf(preferred), listOf(english, namesake), "zh")
        assertEquals(listOf("om:1", "om:2"), both.map { it.placeId })
        assertEquals("zh", both.first().language)
        assertEquals("en", both.last().language)
    }

    @Test
    fun nativeCoarseTileCannotBindForwardSearchTranslations() {
        val primary = dev.photopilot.scaffold.data.net.PlaceResult("geo:1", "Village A", "Region", 1, 2,
            WeatherLogic.DisplayFields(locality = "Village A"), "en")
        val unrelated = primary.copy(name = "另一村", display = WeatherLogic.DisplayFields(locality = "另一村"), language = "zh")
        val place = primary.stored("en", unrelated)
        assertEquals("Village A", place.nameZh)
        assertNull(place.displayZh)
        assertEquals(primary.display, place.displayEn)
        assertEquals(2, dev.photopilot.scaffold.data.net.PlaceResult.mergeLocalized(listOf(primary), listOf(unrelated), "en").size)
    }

    @Test
    fun emptyPreferredMetadataFallsBackToOtherLanguage() {
        val place = WeatherLogic.StoredPlace("om:1", "Raw", "Raw", 1, 2,
            WeatherLogic.DisplayFields(), WeatherLogic.DisplayFields(locality = "Reykjavík", country = "Ísland"))
        assertEquals("Reykjavík", WeatherLogic.displayName(place, "zh"))
        assertEquals("Reykjavík · Ísland", WeatherLogic.detailedName(place, "zh"))
    }

    @Test
    fun partialReverseLookupCannotReplaceLegacyCityWithCountry() {
        val old = WeatherLogic.StoredPlace("geo:1", "北京", "Beijing", 1, 2)
        val partial = old.copy(displayZh = WeatherLogic.DisplayFields(country = "中国"),
            displayEn = WeatherLogic.DisplayFields(adminArea = "Beijing Region", country = "China"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, partial))
        val matching = old.copy(displayZh = WeatherLogic.DisplayFields(subAdminArea = "北京", country = "中国"))
        assertEquals(matching, WeatherLogic.preserveLocatedPlace(old, matching))
    }

    @Test
    fun sameTileDifferentLocalityCannotAttachForeignDistrict() {
        val old = WeatherLogic.StoredPlace("geo:1", "Old", "Old", 1, 2,
            displayEn = WeatherLogic.DisplayFields(locality = "Old Town"))
        val fresh = old.copy(displayEn = WeatherLogic.DisplayFields(locality = "New Town", subLocality = "New District"))
        assertEquals(old, WeatherLogic.preserveLocatedPlace(old, fresh))
    }

    @Test
    fun translationPairingUsesStableIdAndKeepsOriginalCoordinates() {
        val fields = WeatherLogic.DisplayFields(locality = "Springfield", adminArea = "Illinois", country = "US")
        val selected = dev.photopilot.scaffold.data.net.PlaceResult("om:1", "Springfield", "Illinois, US", 1, 2, fields)
        val namesake = selected.copy(placeId = "om:2", name = "另一城市", latE7 = 3)
        val untranslated = selected.stored("en", namesake)
        assertEquals("Springfield", untranslated.nameZh)
        assertEquals(1, untranslated.latE7)
        assertNull(untranslated.displayZh)
        assertEquals(fields, untranslated.displayEn)
        val translated = selected.stored("en", selected.copy(name = "斯普林菲尔德",
            display = fields.copy(locality = "斯普林菲尔德", adminArea = "伊利诺伊州"), latE7 = 3))
        assertEquals("伊利诺伊州", translated.displayZh?.adminArea)
        assertEquals(1, translated.latE7)
        assertEquals("om:1", translated.placeId)
    }

    @Test
    fun globalDisplayVectors() {
        val cases = Vectors.load("weather-place-display.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val f = c.getJSONObject("fields")
            fun value(key: String) = if (f.isNull(key)) null else f.getString(key)
            val fields = WeatherLogic.DisplayFields(value("locality"), value("subLocality"),
                value("subAdminArea"), value("adminArea"), value("country"))
            assertEquals(c.getString("id"), c.getString("zh"), WeatherLogic.compact(fields, "", "zh"))
            assertEquals(c.getString("id"), c.getString("en"), WeatherLogic.compact(fields, "", "en"))
            assertEquals(c.getString("id"), c.getString("context"), WeatherLogic.context(fields, "en"))
        }
    }

    @Test
    fun enrichmentRetainsRawIdentityAndNeverLosesDetail() {
        val old = WeatherLogic.StoredPlace("geo:1", "原名", "Raw", 1, 2,
            displayEn = WeatherLogic.DisplayFields(locality = "New York", subLocality = "Manhattan"))
        val fresh = WeatherLogic.StoredPlace("geo:1", "新名", "New", 3, 4,
            displayZh = WeatherLogic.DisplayFields(locality = "纽约", subLocality = "曼哈顿"),
            displayEn = WeatherLogic.DisplayFields(locality = "New York", country = "United States"))
        val enriched = WeatherLogic.preserveLocatedPlace(old, fresh)
        assertEquals(old.copy(displayZh = fresh.displayZh,
            displayEn = old.displayEn!!.copy(country = "United States")), enriched)
        assertEquals(enriched, WeatherLogic.preserveLocatedPlace(enriched,
            fresh.copy(displayZh = null, displayEn = null)))
        assertEquals(old, WeatherLogic.decodePlaces(WeatherLogic.encodePlaces(mapOf(old.placeId to old)))[old.placeId])
    }

    @Test
    fun delayedAutoLocationRequiresUnchangedSelectionAndFollowMode() {
        assertFalse(WeatherLogic.mayApplyAutoLocation("geo:1", "om:2", true))
        assertFalse(WeatherLogic.mayApplyAutoLocation("geo:1", "geo:1", false))
        assertTrue(WeatherLogic.mayApplyAutoLocation("geo:1", "geo:1", true))
    }

    @Test
    fun structuredDisplaySurvivesPersistence() {
        val json = """{"geo:1":{"nameZh":"北京","nameEn":"Beijing","latE7":1,"lonE7":2,"displayZh":{"locality":"北京","subLocality":"海淀区"},"displayEn":{"locality":"Beijing","subLocality":"Haidian"}}}"""
        val encoded = WeatherLogic.encodePlaces(WeatherLogic.decodePlaces(json))
        assertTrue(encoded.contains("海淀区"))
        assertTrue(encoded.contains("Haidian"))
    }

    @Test
    fun conditionTextResCoversAllKnownCodes() {
        val known = listOf(
            "clear", "partly_cloudy", "cloudy", "fog", "drizzle", "rain", "heavy_rain",
            "thunderstorm", "sleet", "snow", "heavy_snow", "hail", "windy", "haze", "sand",
        )
        known.forEach { code ->
            assertNotNull(code, WeatherLogic.conditionTextRes(code))
        }
        assertEquals(
            dev.photopilot.scaffold.R.string.weather_condition_clear,
            WeatherLogic.conditionTextRes("clear"),
        )
        assertNull(WeatherLogic.conditionTextRes("unknown"))
        assertNull(WeatherLogic.conditionTextRes("foggy"))
    }

    @Test
    fun attributionGateFollowsPresence() {
        val json = "{\"text\":\"Data by Open-Meteo.com\",\"url\":\"https://open-meteo.com/\"}"
        assertEquals("Data by Open-Meteo.com", WeatherLogic.attributionText(json))
        assertTrue(WeatherLogic.weatherDataAllowed(json))
        assertFalse(WeatherLogic.weatherDataAllowed(null))
        assertFalse(WeatherLogic.weatherDataAllowed("{}"))
        assertFalse(WeatherLogic.weatherDataAllowed("{\"text\":\"\"}"))
        assertFalse(WeatherLogic.weatherDataAllowed("bad-json"))
    }

    @Test
    fun ttlSegmentsByObservedAt() {
        val base = 1_700_000_000_000L
        assertEquals(
            WeatherLogic.TtlSegment.FRESH,
            WeatherLogic.segmentOf(base, base + WeatherLogic.FRESH_LIMIT_MS),
        )
        assertEquals(
            WeatherLogic.TtlSegment.STALE,
            WeatherLogic.segmentOf(base, base + WeatherLogic.FRESH_LIMIT_MS + 1),
        )
        assertEquals(
            WeatherLogic.TtlSegment.STALE,
            WeatherLogic.segmentOf(base, base + WeatherLogic.STALE_LIMIT_MS),
        )
        assertEquals(
            WeatherLogic.TtlSegment.EXPIRED,
            WeatherLogic.segmentOf(base, base + WeatherLogic.STALE_LIMIT_MS + 1),
        )
    }

    @Test
    fun unitConversionKeepsPrecision() {
        assertEquals(22, WeatherLogic.displayDegrees(22_000L, imperial = false))
        assertEquals(72, WeatherLogic.displayDegrees(22_000L, imperial = true))
        assertEquals(32, WeatherLogic.displayDegrees(0L, imperial = true))
        assertEquals(-40, WeatherLogic.displayDegrees(-40_000L, imperial = true))
        assertEquals(98, WeatherLogic.displayDegrees(36_700L, imperial = true))
    }

    @Test
    fun wmoMappingFollowsFrozenTable() {
        assertEquals("clear", WeatherLogic.wmoToConditionCode(0L))
        assertEquals("partly_cloudy", WeatherLogic.wmoToConditionCode(2L))
        assertEquals("cloudy", WeatherLogic.wmoToConditionCode(3L))
        assertEquals("fog", WeatherLogic.wmoToConditionCode(45L))
        assertEquals("drizzle", WeatherLogic.wmoToConditionCode(53L))
        assertEquals("rain", WeatherLogic.wmoToConditionCode(80L))
        assertEquals("heavy_rain", WeatherLogic.wmoToConditionCode(65L))
        assertEquals("snow", WeatherLogic.wmoToConditionCode(85L))
        assertEquals("heavy_snow", WeatherLogic.wmoToConditionCode(86L))
        assertEquals("thunderstorm", WeatherLogic.wmoToConditionCode(95L))
        assertEquals("unknown", WeatherLogic.wmoToConditionCode(4L))
        assertEquals("unknown", WeatherLogic.wmoToConditionCode(999L))
    }

    @Test
    fun coarsenRoundsToTenthDegree() {
        assertEquals(39.9, WeatherLogic.coarsen(39.904), 1e-9)
        assertEquals(116.4, WeatherLogic.coarsen(116.397), 1e-9)
        assertEquals(-33.9, WeatherLogic.coarsen(-33.86), 1e-9)
    }

    @Test
    fun placesRoundTripAndCorruptionSafety() {
        val places = mapOf(
            "om:2038349" to WeatherLogic.StoredPlace("om:2038349", "北京", "Beijing", 399040000, 1164070000),
        )
        val decoded = WeatherLogic.decodePlaces(WeatherLogic.encodePlaces(places))
        assertEquals(places, decoded)
        assertEquals(emptyMap<String, WeatherLogic.StoredPlace>(), WeatherLogic.decodePlaces(null))
        assertEquals(emptyMap<String, WeatherLogic.StoredPlace>(), WeatherLogic.decodePlaces("bad"))
        assertNull(WeatherLogic.decodePlaces("{\"a\":{\"nameZh\":\"x\"}}")["a"])
    }

    @Test
    fun searchFallbackStripsAdminSuffixes() {
        assertEquals("朝阳", WeatherLogic.searchFallbackQuery("朝阳区"))
        assertEquals("海淀", WeatherLogic.searchFallbackQuery("海淀区"))
        assertEquals("Fulton", WeatherLogic.searchFallbackQuery("Fulton County"))
        assertEquals("朝阳", WeatherLogic.searchFallbackQuery(" 朝阳区"))
        assertNull(WeatherLogic.searchFallbackQuery("海淀"))
        assertNull(WeatherLogic.searchFallbackQuery("海淀 "))
        assertNull(WeatherLogic.searchFallbackQuery("区"))
        assertNull(WeatherLogic.searchFallbackQuery("北京"))
    }
}
