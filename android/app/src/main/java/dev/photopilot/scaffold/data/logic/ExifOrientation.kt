package dev.photopilot.scaffold.data.logic

object ExifOrientation {

    fun normalize(value: Int): Int = if (value in 1..8) value else 1
}
