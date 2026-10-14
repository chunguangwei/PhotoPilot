package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {

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
