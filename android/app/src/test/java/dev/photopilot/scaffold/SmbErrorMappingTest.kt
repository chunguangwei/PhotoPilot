package dev.photopilot.scaffold

import com.hierynomus.mserref.NtStatus
import dev.photopilot.scaffold.data.net.SmbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbErrorMappingTest {

    @Test
    fun statusToCodeMapping() {
        assertEquals(
            "SRC_AUTH_FAILED",
            SmbClient.mapStatusCode(NtStatus.STATUS_LOGON_FAILURE.value),
        )
        assertEquals(
            "SRC_PERMISSION_DENIED",
            SmbClient.mapStatusCode(NtStatus.STATUS_ACCESS_DENIED.value),
        )
        assertEquals(
            "SRC_PATH_NOT_FOUND",
            SmbClient.mapStatusCode(NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value),
        )
        assertEquals(
            "SRC_PATH_NOT_FOUND",
            SmbClient.mapStatusCode(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value),
        )
        assertEquals(
            "SRC_PATH_NOT_FOUND",
            SmbClient.mapStatusCode(NtStatus.STATUS_BAD_NETWORK_NAME.value),
        )
        assertNull(SmbClient.mapStatusCode(null))
        assertNull(SmbClient.mapStatusCode(NtStatus.STATUS_SUCCESS.value))
        assertEquals(
            "SRC_UNAVAILABLE",
            SmbClient.mapError(java.net.SocketTimeoutException("timeout")).code,
        )
        assertEquals(
            "SMB_PROTOCOL_UNSUPPORTED",
            SmbClient.mapError(java.io.IOException("SMB dialect negotiation failed")).code,
        )
        assertEquals(
            "SMB_PROTOCOL_UNSUPPORTED",
            SmbClient.mapError(
                java.io.IOException("wrap").initCause(IllegalStateException("smb1 only server")),
            ).code,
        )
    }

    @Test
    fun stagedMapping() {
        val treeBadName = com.hierynomus.mssmb2.SMBApiException(
            NtStatus.STATUS_BAD_NETWORK_NAME.value,
            com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_TREE_CONNECT,
            null,
        )
        assertEquals("SRC_SHARE_NOT_FOUND", SmbClient.mapShareError(treeBadName).code)
        assertEquals(
            "SRC_SHARE_NOT_FOUND",
            SmbClient.mapShareError(java.io.IOException("wrapped").initCause(treeBadName)).code,
        )
        assertEquals(
            "SRC_PERMISSION_DENIED",
            SmbClient.mapShareError(
                com.hierynomus.mssmb2.SMBApiException(
                    NtStatus.STATUS_ACCESS_DENIED.value,
                    com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_TREE_CONNECT,
                    null,
                ),
            ).code,
        )
        assertEquals(
            "SRC_PATH_NOT_FOUND",
            SmbClient.mapShareError(
                com.hierynomus.mssmb2.SMBApiException(
                    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
                    com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_CREATE,
                    null,
                ),
            ).code,
        )
        assertEquals(
            "SRC_AUTH_FAILED",
            SmbClient.mapAuthError(
                com.hierynomus.mssmb2.SMBApiException(
                    NtStatus.STATUS_LOGON_FAILURE.value,
                    com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_SESSION_SETUP,
                    null,
                ),
            ).code,
        )
        assertEquals(
            "SRC_AUTH_FAILED",
            SmbClient.mapAuthError(
                com.hierynomus.mssmb2.SMBApiException(
                    NtStatus.STATUS_ACCESS_DENIED.value,
                    com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_SESSION_SETUP,
                    null,
                ),
            ).code,
        )
        assertEquals(
            "SRC_UNREACHABLE",
            SmbClient.mapConnectError(java.net.ConnectException("refused")).code,
        )
        assertEquals(
            "SRC_HOST_UNKNOWN",
            SmbClient.mapConnectError(java.net.UnknownHostException("nope")).code,
        )
        assertEquals(
            "NET_TIMEOUT",
            SmbClient.mapConnectError(java.net.SocketTimeoutException("slow")).code,
        )
    }
}
