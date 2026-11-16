package cn.edu.buaa.scp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.buaa.scp.net.UpdateManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 显式开启 liveUpdate=1：只检查公开 Release、下载和校验，绝不安装。 */
@RunWith(AndroidJUnit4::class)
class LiveUpdateTest {
    @Test fun androidCanFetchDownloadAndVerifyThePublishedUpdate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdate") == "1")
        val info = requireNotNull(UpdateManager.fetchLatest())
        assertTrue(UpdateManager.isNewer(info.version, "0.8.0"))
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "updates/live-probe.apk")
        try {
            val result = UpdateManager.download(info, target, { false }) { _, _, _ -> }
            assertEquals(info.apkSize, result.file.length())
            assertNotNull(info.checksumUrl)
            assertTrue(UpdateManager.verifyChecksum(info, result.file))
        } finally {
            target.delete()
            File(target.parentFile, target.name + ".part").delete()
        }
    }
}
