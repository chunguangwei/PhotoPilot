package dev.photopilot.scaffold.data.logic

import java.net.InetAddress

object NasAddress {

    enum class HostKind(val wire: String) {
        IPV4("ipv4"),
        IPV6("ipv6"),
        HOSTNAME("hostname"),
    }

    data class ParsedHost(val host: String, val port: Int?)

    private val IPV6_PREScreen = Regex("[0-9a-fA-F:.%]+")
    private val HOSTNAME_PATTERN = Regex("[^\\s]+")

    fun parseHostInput(raw: String): ParsedHost? {
        val input = raw.trim()
        if (input.isEmpty()) return null
        if (input.startsWith("[")) {
            val close = input.indexOf(']')
            if (close < 0) return null
            val host = input.substring(1, close)
            if (!isIpv6Literal(host)) return null
            val rest = input.substring(close + 1)
            if (rest.isEmpty()) return ParsedHost(host.lowercase(), null)
            if (!rest.startsWith(":")) return null
            val port = parsePort(rest.substring(1)) ?: return null
            return ParsedHost(host.lowercase(), port)
        }
        val colons = input.count { it == ':' }
        if (colons > 1) {
            if (!isIpv6Literal(input)) return null
            return ParsedHost(input.lowercase(), null)
        }
        val host: String
        var port: Int? = null
        if (colons == 1) {
            val split = input.split(':', limit = 2)
            host = split[0]
            port = parsePort(split[1]) ?: return null
        } else {
            host = input
        }
        val normalized = host.lowercase()
        if (!HOSTNAME_PATTERN.matches(normalized)) return null
        if (normalized == "0.0.0.0") return null
        if (isIpv4Literal(normalized) || isIpv6Literal(normalized) || isPlausibleHostname(normalized)) {
            return ParsedHost(normalized, port)
        }
        return null
    }

    fun classifyHost(host: String): HostKind = when {
        isIpv4Literal(host) -> HostKind.IPV4
        isIpv6Literal(host) -> HostKind.IPV6
        else -> HostKind.HOSTNAME
    }

    fun isPrivateEligible(host: String): Boolean = when (classifyHost(host)) {
        HostKind.IPV4 -> isPrivateIpv4(host)
        HostKind.IPV6 -> isPrivateIpv6(host)
        HostKind.HOSTNAME -> false
    }

    fun defaultPort(protocol: String): Int = when (protocol.lowercase()) {
        "smb" -> 445
        "webdav_http" -> 80
        else -> 443
    }

    fun displayName(protocol: String, host: String, port: Int): String =
        if (port == defaultPort(protocol)) host else "$host:$port"

    fun sourceFingerprint(protocol: String, host: String, port: Int?, path: String): String {
        val proto = protocol.lowercase()
        val normalizedHost = host.lowercase()
        val effectivePort = port ?: defaultPort(proto)
        val normalizedPath = NasPath.normalize(path).orEmpty()
        return buildString {
            append(proto)
            append("://")
            append(normalizedHost)
            append(':')
            append(effectivePort)
            if (normalizedPath.isNotEmpty()) {
                append('/')
                append(normalizedPath)
            }
        }
    }

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || !text.all { it.isDigit() }) return null
        val value = text.toIntOrNull() ?: return null
        return if (value in 1..65535) value else null
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it.isDigit() } &&
                !(part.length > 1 && part.startsWith("0")) &&
                part.toInt() <= 255
        }
    }

    private fun isIpv6Literal(host: String): Boolean {
        if (!IPV6_PREScreen.matches(host) || !host.contains(':')) return false
        return runCatching {
            InetAddress.getByName(host) as? java.net.Inet6Address
        }.getOrNull() != null
    }

    private fun isPlausibleHostname(host: String): Boolean =
        host.any { it.isLetterOrDigit() } && host.none { it.isWhitespace() }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').map { it.toInt() }
        val first = parts[0]
        val second = parts[1]
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val bytes = runCatching {
            InetAddress.getByName(host).address
        }.getOrNull() ?: return false
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return when {
            first == 0xFE && (second and 0xC0) == 0x80 -> true
            (first and 0xFE) == 0xFC -> true
            else -> false
        }
    }
}
