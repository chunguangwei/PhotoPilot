package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.net.SmbClient
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbSecurityReportingTest {

    private fun clientWith(dialect: String, encrypted: Boolean, signing: Boolean = true) =
        SmbClient("nas.local", 445, "share", "", "").apply {
            dialectProbe = { dialect }
            encryptionProbe = { encrypted }
            signingProbe = { signing }
        }

    @Test
    fun preSmb3ServerNeverReportsEncrypted() {
        listOf("SMB_2_0_2", "SMB_2_1", "SMB_2XX").forEach { dialect ->
            val security = clientWith(dialect, encrypted = false).security()
            assertEquals(dialect, security.dialect)
            assertEquals(false, security.encrypted)
        }
    }

    @Test
    fun smb3EncryptedSessionReportsEncrypted() {
        listOf("SMB_3_0", "SMB_3_0_2", "SMB_3_1_1").forEach { dialect ->
            val security = clientWith(dialect, encrypted = true).security()
            assertEquals(dialect, security.dialect)
            assertEquals(true, security.encrypted)
        }
    }

    @Test
    fun smb3UnencryptedFallbackReportsPlain() {
        val security = clientWith("SMB_3_1_1", encrypted = false).security()
        assertEquals(false, security.encrypted)
    }

    @Test
    fun signingFollowsNegotiatedFlag() {
        assertEquals(true, clientWith("SMB_3_1_1", true, signing = true).security().signing)
        assertEquals(false, clientWith("SMB_2_0_2", false, signing = false).security().signing)
    }
}
