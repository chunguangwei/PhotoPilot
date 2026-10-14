package dev.photopilot.scaffold.data.logic

object ClockFormat {

    fun pattern(hour24: Boolean?, system24Hour: Boolean): String =
        if (hour24 ?: system24Hour) "HH:mm" else "h:mm a"
}
