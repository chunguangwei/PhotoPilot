package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.StorageThreshold
import dev.photopilot.scaffold.data.logic.SystemFeatureGates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGatesTest {

    @Test
    fun appearanceWorksOnEverySupportedAndroidVersion() {
        assertTrue(SystemFeatureGates.canApplyAppearance(29))
        assertTrue(SystemFeatureGates.canApplyAppearance(30))
        assertTrue(SystemFeatureGates.canApplyAppearance(31))
        assertTrue(SystemFeatureGates.canApplyAppearance(36))
    }

    @Test
    fun languageWorksOnEverySupportedAndroidVersion() {
        assertTrue(SystemFeatureGates.canApplyLanguage(29))
        assertTrue(SystemFeatureGates.canApplyLanguage(32))
        assertTrue(SystemFeatureGates.canApplyLanguage(33))
    }

    @Test
    fun legacyPreferencesRecreateOnlyWhenTheirEffectiveSelectionChanges() {
        assertTrue(SystemFeatureGates.needsPreferenceRecreation(29, "system", "dark", "zh", "zh"))
        assertTrue(SystemFeatureGates.needsPreferenceRecreation(32, "dark", "dark", "zh", "en"))
        assertTrue(SystemFeatureGates.needsPreferenceRecreation(32, "dark", "dark", "en", "system"))
        assertFalse(SystemFeatureGates.needsPreferenceRecreation(29, "dark", "dark", "en", "en"))
        assertFalse(SystemFeatureGates.needsPreferenceRecreation(31, "system", "dark", "en", "en"))
        assertFalse(SystemFeatureGates.needsPreferenceRecreation(33, "system", "dark", "zh", "en"))
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
