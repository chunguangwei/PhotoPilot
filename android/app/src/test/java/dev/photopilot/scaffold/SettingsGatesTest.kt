package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.StorageThreshold
import dev.photopilot.scaffold.data.logic.SystemFeatureGates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGatesTest {

    @Test
    fun appearanceGateRequiresApi31() {
        assertFalse(SystemFeatureGates.canApplyAppearance(29))
        assertFalse(SystemFeatureGates.canApplyAppearance(30))
        assertTrue(SystemFeatureGates.canApplyAppearance(31))
        assertTrue(SystemFeatureGates.canApplyAppearance(36))
    }

    @Test
    fun languageGateRequiresApi33() {
        assertFalse(SystemFeatureGates.canApplyLanguage(32))
        assertTrue(SystemFeatureGates.canApplyLanguage(33))
    }

    @Test
    fun thresholdIsOneGbOrFivePercentWhicheverLarger() {
        val gb = 1_073_741_824L
        assertEquals(gb, StorageThreshold.thresholdBytes(8L * gb))
        assertEquals(gb, StorageThreshold.thresholdBytes(20L * gb))
        assertEquals(2L * gb, StorageThreshold.thresholdBytes(40L * gb))
    }

    @Test
    fun lowStorageBoundary() {
        val gb = 1_073_741_824L
        assertTrue(StorageThreshold.isLow(gb - 1, 8L * gb))
        assertFalse(StorageThreshold.isLow(gb, 8L * gb))
        assertFalse(StorageThreshold.isLow(0, 0))
    }
}
