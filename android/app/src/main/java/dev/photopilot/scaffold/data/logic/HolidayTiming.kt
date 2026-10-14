package dev.photopilot.scaffold.data.logic

object HolidayTiming {

    const val MINUTES_PER_DAY = 1440

    data class Rule(
        val id: String,
        val enabled: Boolean,
        val position: Long,
        val startDate: String,
        val endDate: String,
        val startTime: Int?,
        val endTime: Int?,
        val frameId: String = "",
        val name: String = "",
    )

    fun isActive(rule: Rule, date: String, minute: Int): Boolean {
        if (endDateInvalid(rule)) return false
        if (date < rule.startDate || date > rule.endDate) return false
        val start = rule.startTime
        val end = rule.endTime
        if (start == null && end == null) return true
        if (start == null || end == null) return false
        return timeWindowActive(start, end, minute)
    }

    fun pick(rules: List<Rule>, date: String, minute: Int): String? =
        rules.asSequence()
            .filter { it.enabled }
            .sortedBy { it.position }
            .firstOrNull { isActive(it, date, minute) }
            ?.id

    fun overlap(
        aStart: String,
        aEnd: String,
        aStartTime: Int?,
        aEndTime: Int?,
        bStart: String,
        bEnd: String,
        bStartTime: Int?,
        bEndTime: Int?,
    ): Boolean {
        if (aEnd < aStart || bEnd < bStart) return false
        if (aEnd < bStart || bEnd < aStart) return false
        return windowsOverlap(aStartTime, aEndTime, bStartTime, bEndTime)
    }

    fun overlap(a: Rule, b: Rule): Boolean =
        overlap(
            a.startDate, a.endDate, a.startTime, a.endTime,
            b.startDate, b.endDate, b.startTime, b.endTime,
        )

    private fun endDateInvalid(rule: Rule): Boolean = rule.endDate < rule.startDate

    private fun timeWindowActive(start: Int, end: Int, minute: Int): Boolean {
        if (start == end) return false
        return if (start > end) {
            minute >= start || minute < end
        } else {
            minute >= start && minute < end
        }
    }

    private fun windowsOverlap(aStart: Int?, aEnd: Int?, bStart: Int?, bEnd: Int?): Boolean {
        if (invalidTimeWindow(aStart, aEnd) || invalidTimeWindow(bStart, bEnd)) return false
        if (isAllDay(aStart, aEnd) || isAllDay(bStart, bEnd)) return true
        return minuteSet(aStart!!, aEnd!!).any { it in minuteSet(bStart!!, bEnd!!) }
    }

    private fun isAllDay(start: Int?, end: Int?): Boolean = start == null && end == null

    private fun invalidTimeWindow(start: Int?, end: Int?): Boolean =
        start != null && start == end ||
            (start == null) != (end == null)

    private fun minuteSet(start: Int, end: Int): Set<Int> {
        if (start == end) return emptySet()
        return if (start < end) {
            (start until end).toSet()
        } else {
            (start until MINUTES_PER_DAY).toSet() + (0 until end).toSet()
        }
    }
}
