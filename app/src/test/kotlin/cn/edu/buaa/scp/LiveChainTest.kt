package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CardApi
import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.Html
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.net.SelfCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 真实链路探针：默认跳过，需要显式开启：
 *
 * ```
 * SCP_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest -Plive=1 --tests 'cn.edu.buaa.scp.LiveChainTest'
 * ```
 *
 * 验证的是「设备侧网络栈 + 协议实现」到学校服务的连通性，不需要账号密码，
 * 因此可以放进交付前检查。
 */
class LiveChainTest {

    @Before
    fun requireLiveFlag() {
        assumeTrue(
            "需要 SCP_LIVE_TEST=1（或 -Plive=1）才运行真实网络测试",
            "1" == System.getenv("SCP_LIVE_TEST") || java.lang.Boolean.getBoolean("live"),
        )
        relaxJvmTrustForProbe()
    }

    @Test
    fun selfCheckAllItemsPass() = runBlocking {
        val items = SelfCheck.run()
        items.forEach { println(it.line()) }
        items.forEach { item ->
            assertTrue("自检失败：${item.name} → ${item.detail}", item.ok)
        }
    }

    @Test
    fun casLoginPageExposesExecution() {
        val http = HttpEngine(CookieJar())
        val response = http.get(
            "https://${CasAuth.SSO_HOST}/login?service=${Html.urlEncode(CasAuth.SERVICE)}&noAutoRedirect=1",
        )
        assertEquals(200, response.status)
        val execution = Html.hiddenInputValue(response.body, "execution")
        assertNotNull("CAS 登录页未解析出 execution（页面可能改版）", execution)
        assertTrue("execution 长度异常：${execution?.length}", (execution?.length ?: 0) > 500)
    }

    @Test
    fun entryUrlRedirectsIntoCardApp() {
        val http = HttpEngine(CookieJar())
        val response = http.get(SelfCheck.ENTRY)
        assertEquals(302, response.status)
        assertNotNull(response.location)
        assertTrue("跳转目标异常：${response.location}", response.location!!.contains("/card"))
    }

    @Test
    fun businessApiRejectsAnonymousRequest() {
        val http = HttpEngine(CookieJar())
        val headers = mapOf(
            "x-requested-with" to "XMLHttpRequest",
            "session-type" to "uniapp",
            "isWechatApp" to "true",
            "orgid" to "2",
            "Content-Type" to "application/json;charset=UTF-8",
        )
        val response = http.post(CardApi.BASE + "/cardpay/openCardPay", headers, "{}")
        assertEquals(403, response.status)
    }

    /**
     * 桌面 JDK 的 cacerts 与 Android 系统信任库并不相同：m.icw.buaa.edu.cn 使用
     * CFCA 证书链，桌面 JDK 默认不信任，而 Android 端（模拟器/真机实测）可以正常校验通过。
     *
     * 因此本探针只验证「协议与连通性」，在 JVM 侧临时放宽证书校验；
     * 证书链验证以 Android 端「网络自检」结果为准。
     */
    private fun relaxJvmTrustForProbe() {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAll, SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    }
}
