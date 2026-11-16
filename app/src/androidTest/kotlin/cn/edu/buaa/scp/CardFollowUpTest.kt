package cn.edu.buaa.scp

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.buaa.scp.net.ScanOutcome
import cn.edu.buaa.scp.ui.CardFollowUpClient
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 所有 WebView 请求由内存页面响应，绝不调用校园卡服务。 */
@RunWith(AndroidJUnit4::class)
class CardFollowUpTest {
    private val inputUrl = ScanOutcome.pageUrl("/pages/common/inputPass/inputPass", org.json.JSONObject().put("qrcode", "TEST-QR"), "test-id")
    private val html = """
        <html><body><button onclick="window.payload={openid:window.cardApi.globalData.openid,qrcode:'TEST-QR',password:'123456'}">Pay</button>
        <script>setTimeout(function(){
            window.cardApp={globalData:{}};
            window.cardApi={baseUrl:location.origin+'/card',globalData:{openid:''},post:function(){}};
            var runtime={c:{randomBuildId:{exports:window.cardApi}}};
            window.webpackJsonp={push:function(chunk){Object.keys(chunk[1]).forEach(function(key){chunk[1][key]({}, {}, runtime)})}};
            window.getApp=function(){return window.cardApp};
            window.uni={setStorageSync:function(k,v){localStorage.setItem(k,v)},getStorageSync:function(k){return localStorage.getItem(k)}};
        },400);</script></body></html>
    """.trimIndent()

    private fun runPage(url: String = inputUrl, id: String = "test-id", content: String = html, check: (WebView, CardFollowUpClient, CountDownLatch, MutableList<String>) -> Unit) {
        ActivityScenario.launch(FollowUpTestActivity::class.java).use { scenario ->
            lateinit var web: WebView
            lateinit var client: CardFollowUpClient
            val ready = CountDownLatch(1)
            val results = java.util.Collections.synchronizedList(mutableListOf<String>())
            scenario.onActivity { activity ->
                web = WebView(activity)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                client = object : CardFollowUpClient(id, { ready.countDown() }, { results.add(it) }) {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/html", "UTF-8", content.byteInputStream())
                }
                web.webViewClient = client
                activity.frame.addView(web)
                web.loadUrl(url)
            }
            try { check(web, client, ready, results) } finally {
                scenario.onActivity { activity -> client.close(); activity.frame.removeAllViews(); web.stopLoading(); web.destroy() }
            }
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun eval(web: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        main { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    @Test fun delayedRuntimeGetsIdentityBeforeFormIsVisibleAndPaymentReadsIt() {
        val id = "test-'\"\\\n中文\u2028\u2029"
        runPage(id = id) { web, _, ready, results ->
            assertTrue("H5 初始化超时", ready.await(10, TimeUnit.SECONDS))
            main { assertEquals(View.VISIBLE, web.visibility) }
            val values = JSONArray(eval(web, "[getApp().globalData.openid,uni.getStorageSync('openid'),window.cardApi.globalData.openid]"))
            assertEquals(id, values.getString(0))
            assertEquals(id, values.getString(1))
            assertEquals(id, values.getString(2))
            val payload = org.json.JSONObject(eval(web, "(function(){document.querySelector('button').click();return window.payload})()"))
            assertEquals(id, payload.getString("openid"))
            assertEquals("TEST-QR", payload.getString("qrcode"))
            assertTrue(results.isEmpty())
        }
    }

    @Test fun successAndFailureHashRoutesFinishExactlyOnce() {
        for ((route, message) in listOf("success" to "操作成功", "fail" to "操作未完成", "error" to "操作未完成")) {
            runPage { web, client, ready, results ->
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                val url = "https://m.icw.buaa.edu.cn/card/#/pages/common/$route/$route"
                eval(web, "location.hash=" + org.json.JSONObject.quote(url.substringAfter('#')))
                val until = System.currentTimeMillis() + 5000
                while (results.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
                // 同一结果可能同时触发多次历史通知；只交付一次终态。
                main { client.doUpdateVisitedHistory(web, url, false) }
                assertEquals(listOf(message), results.toList())
                main { assertEquals(View.INVISIBLE, web.visibility) }
            }
        }
    }

    @Test fun initialTerminalPageDoesNotExposeForm() {
        runPage(url = "https://m.icw.buaa.edu.cn/card/#/pages/common/success/success") { web, _, ready, results ->
            val until = System.currentTimeMillis() + 5000
            while (results.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(listOf("操作成功"), results.toList())
            assertEquals(1L, ready.count)
            main { assertEquals(View.INVISIBLE, web.visibility) }
        }
    }

    @Test fun changedOfficialRuntimeKeepsFormHiddenAndReportsFailure() {
        runPage(content = html.replace("baseUrl:location.origin+'/card'", "baseUrl:'unexpected'")) { web, _, ready, results ->
            val until = System.currentTimeMillis() + 15000
            while (results.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertEquals(listOf("页面身份初始化失败，请返回查看账单"), results.toList())
            assertEquals(1L, ready.count)
            main { assertEquals(View.INVISIBLE, web.visibility) }
        }
    }

    @Test fun closingWhileRuntimeLoadsPreventsLateCallbacks() {
        runPage { _, client, ready, results ->
            main { client.close() }
            assertFalse(ready.await(1, TimeUnit.SECONDS))
            assertTrue(results.isEmpty())
        }
    }

    @Test fun rejectsExternalNavigationAndExternalSuccessSpoof() {
        runPage { web, client, ready, results ->
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            main {
                @Suppress("DEPRECATION")
                assertTrue(client.shouldOverrideUrlLoading(web, "https://evil.test/card/#/pages/common/success/success"))
                client.doUpdateVisitedHistory(web, "https://evil.test/card/#/pages/common/success/success", false)
            }
            assertTrue(results.isEmpty())
        }
    }
}
