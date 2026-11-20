package dev.photopilot.scaffold.data.update

import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.SimpleHttpsGet
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    const val RELEASES_URL = "https://api.github.com/repos/chunguangwei/PhotoPilot/releases/latest"
    const val USER_AGENT = "PhotoPilot-Android"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val versionCode: Long?,
        val semVer: Triple<Int, Int, Int>?,
        val apkUrl: String,
        val apkSize: Long,
        val sha256Url: String?,
    ) {
        val versionKey: Long =
            versionCode ?: semVer?.let { (it.first.toLong() shl 32) or (it.second.toLong() shl 16) or it.third.toLong() } ?: 0L
    }

    enum class CheckResult { UPDATE_AVAILABLE, UP_TO_DATE, FAILED }

    private val assetNamePattern = Regex("^PhotoPilot-(?:v)?([0-9]+\\.[0-9]+\\.[0-9]+)(?:-(\\d+))?\\.apk$")

    fun parseSemVer(value: String): Triple<Int, Int, Int>? {
        val parts = value.trim().split('.').map { it.toIntOrNull() }
        if (parts.size != 3 || parts.any { it == null }) return null
        return Triple(parts[0]!!, parts[1]!!, parts[2]!!)
    }

    fun parseAssetName(name: String): Pair<Triple<Int, Int, Int>, Long?>? {
        val match = assetNamePattern.matchEntire(name) ?: return null
        val sem = parseSemVer(match.groupValues[1]) ?: return null
        val codeText = match.groupValues[2]
        if (codeText.isEmpty()) return sem to null
        val code = codeText.toLongOrNull() ?: return null
        return sem to code
    }

    fun compare(localVersionCode: Long, localVersionName: String, release: ReleaseInfo?): CheckResult {
        if (release == null) return CheckResult.FAILED
        val newer = if (release.versionCode != null && localVersionCode > 0L) {
            release.versionCode > localVersionCode
        } else {
            val localSem = parseSemVer(localVersionName)
            val releasedSem = release.semVer
            if (localSem != null && releasedSem != null) {
                compareSemVer(releasedSem, localSem) > 0
            } else {
                release.versionCode != null && release.versionCode > localVersionCode
            }
        }
        return if (newer) CheckResult.UPDATE_AVAILABLE else CheckResult.UP_TO_DATE
    }

    internal fun compareSemVer(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val first = a.first.compareTo(b.first)
        if (first != 0) return first
        val second = a.second.compareTo(b.second)
        if (second != 0) return second
        return a.third.compareTo(b.third)
    }

    fun parseLatestRelease(json: String): ReleaseInfo? {
        val root = JsonMini.parseObject(json)
        val tagName = root["tag_name"] as? String ?: return null
        val assets = root["assets"] as? List<*> ?: return null
        var sha256Url: String? = null
        val candidates = assets.mapNotNull { item ->
            val asset = item as? Map<*, *> ?: return@mapNotNull null
            val name = asset["name"] as? String ?: return@mapNotNull null
            val url = asset["browser_download_url"] as? String
            if (url.isNullOrEmpty()) return@mapNotNull null
            if (name == "SHA256SUMS.txt") {
                sha256Url = url
                return@mapNotNull null
            }
            if (!name.endsWith(".apk")) return@mapNotNull null
            val parsed = parseAssetName(name) ?: return@mapNotNull null
            val sem = parsed.first
            val size = (asset["size"] as? Number)?.toLong() ?: 0L
            ReleaseInfo(tagName, "${sem.first}.${sem.second}.${sem.third}", parsed.second, sem, url, size, null)
        }
        return candidates.maxByOrNull { it.versionKey }?.let { info ->
            ReleaseInfo(info.tagName, info.versionName, info.versionCode, info.semVer, info.apkUrl, info.apkSize, sha256Url)
        }
    }

    fun fetchLatestReleaseJson(): String {
        var lastError: IOException? = null
        repeat(2) {
            try {
                return fetchOnce()
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("fetch failed")
    }

    private fun fetchOnce(): String {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SimpleHttpsGet.CONNECT_TIMEOUT_MS
            connection.readTimeout = SimpleHttpsGet.READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("unexpected status $status")
            return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchSha256(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = SimpleHttpsGet.CONNECT_TIMEOUT_MS
            connection.readTimeout = SimpleHttpsGet.READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            return body.lineSequence().firstOrNull { it.contains("PhotoPilot-") }?.trim()
        } finally {
            connection.disconnect()
        }
    }
}
