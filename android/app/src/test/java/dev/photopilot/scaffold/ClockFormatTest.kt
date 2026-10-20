package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ClockFormatTest {

    @Test
    fun chineseDateIncludesFullWeekday() {
        assertEquals(
            "2026/10/20 星期二",
            ClockFormat.dateWithWeekday(
                "2026/10/20", Date.from(Instant.parse("2026-10-20T12:00:00Z")),
                Locale.SIMPLIFIED_CHINESE, TimeZone.getTimeZone("Asia/Shanghai"),
            ),
        )
    }

    @Test
    fun englishDateIncludesFullWeekday() {
        assertEquals(
            "10/20/26 Tuesday",
            ClockFormat.dateWithWeekday(
                "10/20/26", Date.from(Instant.parse("2026-10-20T12:00:00Z")),
                Locale.US, TimeZone.getTimeZone("UTC"),
            ),
        )
    }

    @Test
    fun weekdayUsesDeviceTimeZoneAcrossMidnight() {
        assertEquals(
            "2026/10/21 星期三",
            ClockFormat.dateWithWeekday(
                "2026/10/21", Date.from(Instant.parse("2026-10-20T23:30:00Z")),
                Locale.SIMPLIFIED_CHINESE, TimeZone.getTimeZone("Asia/Shanghai"),
            ),
        )
    }

    @Test
    fun explicitSettingOverridesSystem() {
        assertEquals("HH:mm", ClockFormat.pattern(hour24 = true, system24Hour = false))
        assertEquals("h:mm a", ClockFormat.pattern(hour24 = false, system24Hour = true))
    }

    @Test
    fun nullFollowsSystem() {
        assertEquals("HH:mm", ClockFormat.pattern(hour24 = null, system24Hour = true))
        assertEquals("h:mm a", ClockFormat.pattern(hour24 = null, system24Hour = false))
    }
}
