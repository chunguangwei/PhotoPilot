package dev.photopilot.scaffold.update

import dev.photopilot.scaffold.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun release(code: Long) = UpdateChecker.ReleaseInfo(
        tagName = "v1.0.0",
        versionName = "1.0.0",
        versionCode = code,
        apkUrl = "https://example.com/app.apk",
        apkSize = 1024L,
    )

    @Test
    fun newerRemoteVersionCodeMeansUpdate() {
        assertEquals(
            UpdateChecker.CheckResult.UPDATE_AVAILABLE,
            UpdateChecker.compare(1L, release(2L)),
        )
    }

    @Test
    fun equalOrOlderRemoteVersionCodeMeansUpToDate() {
        assertEquals(UpdateChecker.CheckResult.UP_TO_DATE, UpdateChecker.compare(2L, release(2L)))
        assertEquals(UpdateChecker.CheckResult.UP_TO_DATE, UpdateChecker.compare(3L, release(2L)))
    }

    @Test
    fun unparsableReleaseMeansFailed() {
        assertEquals(UpdateChecker.CheckResult.FAILED, UpdateChecker.compare(1L, null))
    }

    @Test
    fun assetNameParsesVersionNameAndCode() {
        assertEquals("1.0.0" to 2L, UpdateChecker.parseAssetName("PhotoPilot-v1.0.0-2.apk"))
        assertEquals("0.1.0" to 1L, UpdateChecker.parseAssetName("PhotoPilot-v0.1.0-1.apk"))
    }

    @Test
    fun assetNameRejectsNonConformingNames() {
        assertNull(UpdateChecker.parseAssetName("PhotoPilot.apk"))
        assertNull(UpdateChecker.parseAssetName("PhotoPilot-v1.0.0.apk"))
        assertNull(UpdateChecker.parseAssetName("photopilot-v1.0.0-2.apk"))
        assertNull(UpdateChecker.parseAssetName("PhotoPilot-v1.0.0-beta.apk"))
        assertNull(UpdateChecker.parseAssetName(""))
        assertNull(UpdateChecker.parseAssetName("PhotoPilot-v1.0.0-2.zip"))
    }

    @Test
    fun parseLatestReleaseReadsFirstMatchingAsset() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/sums.txt", "size": 128},
                {"name": "PhotoPilot-v1.0.0-2.apk", "browser_download_url": "https://example.com/PhotoPilot-v1.0.0-2.apk", "size": 52428800},
                {"name": "PhotoPilot-v9.9.9-99.apk", "browser_download_url": "https://example.com/other.apk", "size": 1}
              ]
            }
        """.trimIndent()
        val info = UpdateChecker.parseLatestRelease(json)
        assertEquals("v1.0.0", info?.tagName)
        assertEquals("1.0.0", info?.versionName)
        assertEquals(2L, info?.versionCode)
        assertEquals("https://example.com/PhotoPilot-v1.0.0-2.apk", info?.apkUrl)
        assertEquals(52_428_800L, info?.apkSize)
    }

    @Test
    fun parseLatestReleaseSkipsAssetWithEmptyUrl() {
        val json = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {"name": "PhotoPilot-v1.0.0-2.apk", "browser_download_url": "", "size": 10},
                {"name": "PhotoPilot-v1.0.0-3.apk", "browser_download_url": "https://example.com/ok.apk", "size": 20}
              ]
            }
        """.trimIndent()
        assertEquals(3L, UpdateChecker.parseLatestRelease(json)?.versionCode)
    }

    @Test
    fun parseLatestReleaseRejectsBadPayloads() {
        assertNull(UpdateChecker.parseLatestRelease(""))
        assertNull(UpdateChecker.parseLatestRelease("not json"))
        assertNull(UpdateChecker.parseLatestRelease("""{"tag_name":"v1.0.0"}"""))
        assertNull(UpdateChecker.parseLatestRelease("""{"assets":[]}"""))
        assertNull(
            UpdateChecker.parseLatestRelease(
                """{"tag_name":"v1.0.0","assets":[{"name":"readme.md","browser_download_url":"https://example.com/r.md","size":1}]}""",
            ),
        )
    }
}
