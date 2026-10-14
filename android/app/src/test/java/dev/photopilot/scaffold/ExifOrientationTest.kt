package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ExifOrientation
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {

    @Test
    fun outOfDomainValuesNormalizeToOne() {
        assertEquals(1, ExifOrientation.normalize(0))
        assertEquals(1, ExifOrientation.normalize(9))
        assertEquals(1, ExifOrientation.normalize(-1))
        assertEquals(1, ExifOrientation.normalize(Int.MAX_VALUE))
    }

    @Test
    fun validDomainPassThrough() {
        for (value in 1..8) {
            assertEquals(value, ExifOrientation.normalize(value))
        }
    }
}
