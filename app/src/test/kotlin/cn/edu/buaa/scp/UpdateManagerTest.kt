package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.UpdateManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateManager.isNewer("0.4.1", "0.4.0"))
        assertTrue(UpdateManager.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateManager.isNewer("0.4.0", "0.4.0"))
        assertFalse(UpdateManager.isNewer("0.3.9", "0.4.0"))
        assertFalse(UpdateManager.isNewer("bad", "0.4.0"))
    }

    @Test
    fun parsesReleaseWithApkAndChecksum() {
        val json = JSONObject()
            .put("tag_name", "v0.4.1")
            .put("name", "通财航 v0.4.1")
            .put("body", "修复若干问题")
            .put("draft", false)
            .put(
                "assets",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("name", "TongCaiHang-v0.4.1-release.apk")
                            .put("browser_download_url", "https://example.com/app.apk")
                            .put("size", 1234),
                    )
                    put(
                        JSONObject()
                            .put("name", "TongCaiHang-v0.4.1-release.apk.sha256")
                            .put("browser_download_url", "https://example.com/app.apk.sha256"),
                    )
                },
            )
        val info = UpdateManager.parse(json)
        assertNotNull(info)
        assertEquals("0.4.1", info!!.version)
        assertEquals("https://example.com/app.apk", info.apkUrl)
        assertEquals("https://example.com/app.apk.sha256", info.checksumUrl)
        assertEquals(1234L, info.apkSize)
    }

    @Test
    fun ignoresReleaseWithoutApk() {
        val json = JSONObject()
            .put("tag_name", "v0.4.1")
            .put("assets", JSONArray())
        assertNull(UpdateManager.parse(json))
    }
    @Test fun prefersOfficialAssetApiForApkAndChecksum() {
        val prefix = "https://api.github.com/repos/y38501148-max/Buaa-SCP/releases/assets/"
        val release = JSONObject().put("tag_name", "v0.8.2").put("assets", JSONArray()
            .put(JSONObject().put("name", "app.apk").put("url", prefix + "12").put("browser_download_url", "https://github.com/app.apk"))
            .put(JSONObject().put("name", "app.apk.sha256").put("url", prefix + "13").put("browser_download_url", "https://github.com/app.apk.sha256")))
        val info = UpdateManager.parse(release)!!
        assertEquals(prefix + "12", info.apkUrl)
        assertEquals(prefix + "13", info.checksumUrl)
    }

    @Test fun ignoresUnrelatedOrMalformedAssetApiUrls() {
        val fallback = "https://github.com/app.apk"
        for (url in listOf("https://evil.test/asset", "http://api.github.com/repos/y38501148-max/Buaa-SCP/releases/assets/12",
            "https://api.github.com/repos/other/repo/releases/assets/12", "https://api.github.com/repos/y38501148-max/Buaa-SCP/releases/assets/12?redirect=evil")) {
            assertEquals(fallback, UpdateManager.assetDownloadUrl(JSONObject().put("url", url).put("browser_download_url", fallback)))
        }
    }

}
