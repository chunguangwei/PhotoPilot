package dev.photopilot.scaffold.data.logic

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

object ClockFormat {

    fun dateWithWeekday(dateText: String, date: Date, locale: Locale, timeZone: TimeZone): String {
        val weekday = SimpleDateFormat("EEEE", locale).apply {
            this.timeZone = timeZone
        }.format(date)
        return "$dateText $weekday"
    }

    fun pattern(hour24: Boolean?, system24Hour: Boolean): String =
        if (hour24 ?: system24Hour) "HH:mm" else "h:mm a"
}
