package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.ImportBatchNaming
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportBatchNamingTest {

    @Test
    fun nameUsesLocalTimeZoneFormat() {
        val millis = 1_700_000_000_000L
        val utc = ImportBatchNaming.nameOf(millis, TimeZone.getTimeZone("UTC"))
        val shanghai = ImportBatchNaming.nameOf(millis, TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals("2023-11-14 22:13", utc)
        assertEquals("2023-11-15 06:13", shanghai)
    }

    @Test
    fun sortKeyIsZeroPaddedMillis() {
        assertEquals("00000001700000000000", ImportBatchNaming.sortKeyOf(1_700_000_000_000L))
        assertEquals("00000000000000000042", ImportBatchNaming.sortKeyOf(42L))
    }
}
