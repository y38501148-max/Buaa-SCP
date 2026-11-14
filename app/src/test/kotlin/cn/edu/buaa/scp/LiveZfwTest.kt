package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.net.ZfwApi
import cn.edu.buaa.scp.store.ZfwSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.TrustManager

/**
 * 校园网流量真实链路探针（默认跳过）。
 *
 * 需要浏览器或 WebView 里已登录的 zfw Cookie：
 * ```
 * SCP_ZFW_COOKIE='PHPSESSID_8800=...; _csrf-8800=...' SCP_LIVE_TEST=1 \
 *   ./gradlew :app:testDebugUnitTest -Plive=1 --tests 'cn.edu.buaa.scp.LiveZfwTest'
 * ```
 */
class LiveZfwTest {

    @Before
    fun requireLiveFlag() {
        assumeTrue(
            "需要 SCP_LIVE_TEST=1 且提供 SCP_ZFW_COOKIE 才运行",
            "1" == System.getenv("SCP_LIVE_TEST") && !System.getenv("SCP_ZFW_COOKIE").isNullOrBlank(),
        )
        val trustAll = arrayOf<TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        })
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, trustAll, java.security.SecureRandom())
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    @Test
    fun homeTrafficFetchedFromZfw() = runBlocking {
        val jar = CookieJar()
        ZfwSession.parseCookieHeader(System.getenv("SCP_ZFW_COOKIE")).forEach { (name, value) ->
            jar.put(ZfwApi.HOST, name, value)
        }
        val api = ZfwApi(HttpEngine(jar))
        val home = api.fetchHome()
        println("校园网：用户 ${home.username} / 已用 ${home.usedTrafficText} / 免费剩余 ${home.freeRemainText} / 计费剩余 ${home.billedRemainText}")
        assertTrue("未解析到用户名", home.username.isNotBlank())
        assertTrue("未解析到计费流量剩余", home.billedRemain.isNotBlank())
    }
}
