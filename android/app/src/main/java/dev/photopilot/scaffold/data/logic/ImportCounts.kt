package dev.photopilot.scaffold.data.logic

object ImportCounts {

    private val ENTRY = Regex("\"([a-z_]+)\":(\\d+)")

    fun parse(countsJson: String): Map<String, Int> =
        ENTRY.findAll(countsJson).associate { it.groupValues[1] to it.groupValues[2].toInt() }

    fun imported(counts: Map<String, Int>): Int = counts["imported"] ?: 0

    fun failed(counts: Map<String, Int>): Int = counts["failed"] ?: 0

    fun skipped(counts: Map<String, Int>): Int = counts["skipped"] ?: 0

    fun canceled(counts: Map<String, Int>): Int = counts["canceled"] ?: 0

    fun terminal(counts: Map<String, Int>): Int =
        imported(counts) + failed(counts) + skipped(counts) + canceled(counts)
}
