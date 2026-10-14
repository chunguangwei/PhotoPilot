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
        val versionCode: Long,
        val apkUrl: String,
        val apkSize: Long,
    )

    enum class CheckResult { UPDATE_AVAILABLE, UP_TO_DATE, FAILED }

    private val assetNamePattern = Regex("^PhotoPilot-v(.+)-(\\d+)\\.apk$")

    fun parseAssetName(name: String): Pair<String, Long>? {
        val match = assetNamePattern.matchEntire(name) ?: return null
        val code = match.groupValues[2].toLongOrNull() ?: return null
        return match.groupValues[1] to code
    }

    fun compare(localVersionCode: Long, release: ReleaseInfo?): CheckResult {
        if (release == null) return CheckResult.FAILED
        return if (release.versionCode > localVersionCode) {
            CheckResult.UPDATE_AVAILABLE
        } else {
            CheckResult.UP_TO_DATE
        }
    }

    fun parseLatestRelease(json: String): ReleaseInfo? {
        val root = JsonMini.parseObject(json)
        val tagName = root["tag_name"] as? String ?: return null
        val assets = root["assets"] as? List<*> ?: return null
        for (item in assets) {
            val asset = item as? Map<*, *> ?: continue
            val name = asset["name"] as? String ?: continue
            val parsed = parseAssetName(name) ?: continue
            val url = asset["browser_download_url"] as? String
            if (url.isNullOrEmpty()) continue
            val size = (asset["size"] as? Number)?.toLong() ?: 0L
            return ReleaseInfo(tagName, parsed.first, parsed.second, url, size)
        }
        return null
    }

    fun fetchLatestReleaseJson(): String {
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
}
