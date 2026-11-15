package dev.photopilot.scaffold.data.references

import org.junit.Assert.*
import org.junit.Test

class LocalReferenceRulesTest {
    @Test fun allSeventeenStaticExtensionsRemainSupported() {
        assertEquals(17, LocalReferenceRules.extensions.size)
        LocalReferenceRules.extensions.forEach { assertTrue(LocalReferenceRules.supported("fixture.$it", "application/octet-stream")) }
    }
    @Test fun videoAndUnsupportedImagesAreExcluded() {
        assertFalse(LocalReferenceRules.supported("fixture.jpg", "video/mp4"))
        assertFalse(LocalReferenceRules.supported("fixture.mov", ""))
        assertFalse(LocalReferenceRules.supported("fixture.svg", "image/svg+xml"))
    }
    @Test fun decodedBitmapFitsTargetAndThirtyTwoMiBBudget() {
        val size = LocalReferenceRules.size(100000, 50000, 100000, 100000)
        assertTrue(size.first.toLong() * size.second * 4 <= 32 * 1024 * 1024)
        assertEquals(1000 to 500, LocalReferenceRules.size(100000, 50000, 1000, 800))
        assertEquals(10 to 5, LocalReferenceRules.size(10, 5, 1000, 1000))
    }
}
