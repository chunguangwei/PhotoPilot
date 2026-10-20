package dev.photopilot.scaffold.data.logic

object WeatherInlineLayout {
    data class Widths(val place: Int, val weather: Int, val gap: Int)

    fun allocate(available: Int, placeDesired: Int, weatherDesired: Int, weatherMinimum: Int, gap: Int): Widths {
        val width = available.coerceAtLeast(0)
        val place = placeDesired.coerceAtLeast(0)
        val weather = weatherDesired.coerceAtLeast(0)
        if (place == 0) return Widths(0, weather.coerceAtMost(width), 0)
        if (weather == 0) return Widths(place.coerceAtMost(width), 0, 0)
        val spacing = gap.coerceIn(0, (width - 2).coerceAtLeast(0))
        val content = width - spacing
        if (place.toLong() + weather <= content) return Widths(place, weather, spacing)
        val minimum = weatherMinimum.coerceIn(0, (content - 1).coerceAtLeast(0))
        var weatherWidth = weather.coerceAtMost(maxOf(minimum, content / 2))
        val placeWidth = place.coerceAtMost((content - weatherWidth).coerceAtLeast(0))
        weatherWidth = weather.coerceAtMost(content - placeWidth)
        return Widths(placeWidth, weatherWidth, spacing)
    }
}
