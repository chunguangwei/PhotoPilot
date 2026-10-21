package dev.photopilot.scaffold.data.net

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream

interface SmbShare {
    fun security(): SmbClient.Security
    fun list(path: String): List<SmbEntry>
    fun openRead(path: String): InputStream
}

data class SmbEntry(
    val name: String,
    val isCollection: Boolean = false,
    val size: Long? = null,
    val modifiedMs: Long? = null,
)

class SmbClient(
    private val host: String,
    private val port: Int,
    private val shareName: String,
    private val username: String,
    private val password: String,
    internal var encryptionProbe: (() -> Boolean)? = null,
    internal var dialectProbe: (() -> String)? = null,
    private val tlsPolicy: NasTlsPolicy? = null,
) : SmbShare {

    data class Security(
        val dialect: String,
        val encrypted: Boolean,
        val signing: Boolean,
    )

    class SmbException(val code: String, cause: Throwable? = null) : IOException("smb: $code", cause)

    private var client: SMBClient? = null
    private var connection: com.hierynomus.smbj.connection.Connection? = null
    private var session: com.hierynomus.smbj.session.Session? = null
    private var share: DiskShare? = null
    internal var signingProbe: (() -> Boolean)? = null

    override fun security(): Security {
        val encryption = encryptionProbe
        val dialect = dialectProbe
        val signing = signingProbe
        if (encryption != null && dialect != null && signing != null) {
            val encrypted = encryption.invoke()
            requireAllowedSession(encrypted)
            return Security(
                dialect = dialect.invoke(),
                encrypted = encrypted,
                signing = signing.invoke(),
            )
        }
        ensureConnected()
        val dialectName = runCatching {
            connection
                ?.connectionContext
                ?.negotiatedProtocol
                ?.dialect
                ?.name
        }.getOrNull() ?: "SMB2"
        return Security(
            dialect = dialectName,
            encrypted = isSessionEncrypted(),
            signing = isSessionSigning(),
        )
    }

    override fun list(path: String): List<SmbEntry> {
        ensureConnected()
        val target = share ?: throw SmbException(ERR_SRC_UNAVAILABLE)
        val entries = try {
            target.list(path)
        } catch (e: Throwable) {
            throw mapError(e)
        }
        return entries.mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            SmbEntry(
                name = name,
                isCollection = (info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L,
                size = info.endOfFile,
                modifiedMs = info.lastWriteTime?.toEpochMillis(),
            )
        }
    }

    override fun openRead(path: String): InputStream {
        ensureConnected()
        val target = share ?: throw SmbException(ERR_SRC_UNAVAILABLE)
        val file = try {
            target.openFile(
                path,
                setOf(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (e: Throwable) {
            throw mapError(e)
        }
        return file.inputStream
    }

    fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        connection = null
        client = null
    }

    private fun ensureConnected() {
        if (share != null) {
            requireAllowedSession(isSessionEncrypted())
            return
        }
        openWith(SmbConfig.builder().withEncryptData(true).build())
    }

    private fun requireAllowedSession(encrypted: Boolean) {
        if (encrypted) return
        val policy = tlsPolicy ?: throw SmbException(NasTlsPolicy.ERR_CLEARTEXT_BLOCKED)
        policy.cleartextError(host, port)?.let { throw SmbException(it) }
    }

    private fun isSessionEncrypted(): Boolean =
        encryptionProbe?.invoke()
            ?: runCatching { session?.shouldEncryptData() == true }.getOrDefault(false)

    private fun isSessionSigning(): Boolean =
        signingProbe?.invoke()
            ?: runCatching { session?.isSigningRequired() == true }.getOrDefault(false)

    private fun openWith(config: SmbConfig) {
        val candidate = SMBClient(config)
        try {
            val conn = try {
                candidate.connect(host, port)
            } catch (e: Throwable) {
                throw mapConnectError(e)
            }
            val auth = if (username.isEmpty() && password.isEmpty()) {
                AuthenticationContext.guest()
            } else {
                AuthenticationContext(username, password.toCharArray(), null)
            }
            val session = try {
                conn.authenticate(auth)
            } catch (e: Throwable) {
                throw mapAuthError(e)
            }
            requireAllowedSession(session.shouldEncryptData())
            val diskShare = try {
                session.connectShare(shareName) as DiskShare
            } catch (e: Throwable) {
                throw mapShareError(e)
            }
            client = candidate
            connection = conn
            this.session = session
            share = diskShare
        } catch (e: Throwable) {
            runCatching { candidate.close() }
            if (e is SmbException) throw e
            throw mapError(e)
        }
    }

    companion object {
        const val ERR_SRC_AUTH_FAILED = "SRC_AUTH_FAILED"
        const val ERR_SRC_PATH_NOT_FOUND = "SRC_PATH_NOT_FOUND"
        const val ERR_SRC_PERMISSION_DENIED = "SRC_PERMISSION_DENIED"
        const val ERR_SRC_UNAVAILABLE = "SRC_UNAVAILABLE"
        const val ERR_SRC_SHARE_NOT_FOUND = "SRC_SHARE_NOT_FOUND"
        const val ERR_SMB_PROTOCOL_UNSUPPORTED = "SMB_PROTOCOL_UNSUPPORTED"

        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

        fun mapError(error: Throwable): SmbException {
            val status = generateSequence(error) { it.cause }
                .filterIsInstance<SMBApiException>()
                .firstOrNull()
                ?.statusCode
            val code = mapStatusCode(status)
                ?: if (isDialectFailure(error)) {
                    ERR_SMB_PROTOCOL_UNSUPPORTED
                } else {
                    ERR_SRC_UNAVAILABLE
                }
            return SmbException(code, error)
        }

        fun mapConnectError(error: Throwable): SmbException {
            val causes = generateSequence(error) { it.cause }
            val code = when {
                causes.filterIsInstance<java.net.UnknownHostException>().any() ->
                    WebDavClient.ERR_SRC_HOST_UNKNOWN
                causes.filterIsInstance<java.net.ConnectException>().any() ->
                    WebDavClient.ERR_SRC_UNREACHABLE
                causes.filterIsInstance<java.net.SocketTimeoutException>().any() ->
                    WebDavClient.ERR_NET_TIMEOUT
                else -> null
            }
            return if (code != null) SmbException(code, error) else mapError(error)
        }

        fun mapAuthError(error: Throwable): SmbException {
            val status = generateSequence(error) { it.cause }
                .filterIsInstance<SMBApiException>()
                .firstOrNull()
                ?.statusCode
            if (status == NtStatus.STATUS_LOGON_FAILURE.value ||
                status == NtStatus.STATUS_ACCESS_DENIED.value
            ) {
                return SmbException(ERR_SRC_AUTH_FAILED, error)
            }
            return mapConnectError(error)
        }

        fun mapShareError(error: Throwable): SmbException {
            val status = generateSequence(error) { it.cause }
                .filterIsInstance<SMBApiException>()
                .firstOrNull()
                ?.statusCode
            val code = when (status) {
                NtStatus.STATUS_BAD_NETWORK_NAME.value,
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value,
                -> ERR_SRC_SHARE_NOT_FOUND
                NtStatus.STATUS_ACCESS_DENIED.value -> ERR_SRC_PERMISSION_DENIED
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value -> ERR_SRC_PATH_NOT_FOUND
                else -> null
            }
            return if (code != null) SmbException(code, error) else mapError(error)
        }

        fun mapStatusCode(status: Long?): String? = when (status) {
            NtStatus.STATUS_LOGON_FAILURE.value -> ERR_SRC_AUTH_FAILED
            NtStatus.STATUS_ACCESS_DENIED.value -> ERR_SRC_PERMISSION_DENIED
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value,
            NtStatus.STATUS_BAD_NETWORK_NAME.value,
            -> ERR_SRC_PATH_NOT_FOUND
            else -> null
        }

        private fun isDialectFailure(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.any { cause ->
                val message = cause.message?.lowercase().orEmpty()
                "dialect" in message || "smb1" in message || "negotiate" in message
            }
    }
}
