package cn.edu.buaa.scp

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.buaa.scp.net.UpdateInfo
import cn.edu.buaa.scp.ui.UpdateUi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 独立 testbed 包内验证更新 UI 生命周期；模拟响应不下载或安装 APK。 */
@RunWith(AndroidJUnit4::class)
class UpdateUiTest {
    private val info = UpdateInfo("99.0.0", "v99.0.0", "测试版本", "https://example.invalid/app.apk", 100, null, "https://example.invalid/")
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val prefs get() = instrumentation.targetContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    @Before fun resetUpdatePreferences() { prefs.edit().clear().commit() }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun hasText(text: String): Boolean {
        var found = false
        main { found = WindowInspector.getGlobalWindowViews().flatMap { views(it) }.filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains(text) } }
        return found
    }
    private fun awaitText(text: String) {
        val until = System.currentTimeMillis() + 5000
        while (!hasText(text) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("未出现：$text", hasText(text))
    }
    private fun clickText(text: String) {
        main { WindowInspector.getGlobalWindowViews().flatMap { views(it) }.filterIsInstance<TextView>().first { it.isShown && it.text.toString() == text }.performClick() }
        instrumentation.waitForIdleSync()
    }

    @Test fun destroyedCheckDoesNotBlockTheRecreatedActivity() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { UpdateUi.check(it, true) { started.countDown(); release.await(5, TimeUnit.SECONDS); info } }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                awaitText("正在检查更新")
                scenario.recreate()
                scenario.onActivity { UpdateUi.check(it, true) { info } }
                awaitText("发现新版本 v99.0.0")
            } finally { release.countDown() }
        }
    }

    @Test fun recreatedUpdateDialogCanBeShownAgain() {
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            scenario.onActivity { UpdateUi.check(it, true) { info } }
            awaitText("发现新版本 v99.0.0")
            scenario.recreate()
            scenario.onActivity { UpdateUi.check(it, true) { info } }
            awaitText("发现新版本 v99.0.0")
            clickText("稍后")
            scenario.onActivity { UpdateUi.check(it, true) { info } }
            awaitText("发现新版本 v99.0.0")
        }
    }

    @Test fun cancelledOldCheckCannotHideTheNextCheck() {
        val release = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { UpdateUi.check(it, true) { release.await(5, TimeUnit.SECONDS); info } }
                awaitText("正在检查更新")
                clickText("取消")
                scenario.onActivity { UpdateUi.check(it, true) { secondRelease.await(5, TimeUnit.SECONDS); info } }
                release.countDown()
                awaitText("正在检查更新")
                secondRelease.countDown()
                awaitText("发现新版本 v99.0.0")
            } finally { release.countDown(); secondRelease.countDown() }
        }
    }

    @Test fun manualClickJoinsAutoCheckAndMakesFailureVisible() {
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    UpdateUi.check(activity, false) { calls.incrementAndGet(); release.await(5, TimeUnit.SECONDS); throw IOException("测试网络断开") }
                    UpdateUi.check(activity, true) { calls.incrementAndGet(); info }
                }
                awaitText("正在检查更新")
                release.countDown()
                awaitText("检查更新失败")
                awaitText("测试网络断开")
                assertEquals(1, calls.get())
                assertFalse(prefs.contains("last_check"))
                clickText("关闭")
                scenario.onActivity { UpdateUi.check(it, true) { info } }
                awaitText("发现新版本 v99.0.0")
            } finally { release.countDown() }
        }
    }

    @Test fun manualCheckIgnoresSkippedVersionAndRecentCheck() {
        prefs.edit().putString("skipped_version", info.version).putString("checked_version", BuildConfig.VERSION_NAME)
            .putLong("last_check", System.currentTimeMillis()).commit()
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            scenario.onActivity { UpdateUi.check(it, true) { info } }
            awaitText("发现新版本 v99.0.0")
        }
    }
}
