package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.net.PortalApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.TrustManager

/**
 * 门户网费余额真实链路探针（默认跳过）。
 *
 * 需要一个有效的统一身份认证 CASTGC：
 * ```
 * SCP_CASTGC=<TGT-...> SCP_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest -Plive=1 \
 *   --tests 'cn.edu.buaa.scp.LivePortalTest'
 * ```
 */
class LivePortalTest {

    @Before
    fun requireLiveFlag() {
        assumeTrue(
            "需要 SCP_LIVE_TEST=1 且提供 SCP_CASTGC 才运行",
            "1" == System.getenv("SCP_LIVE_TEST") && !System.getenv("SCP_CASTGC").isNullOrBlank(),
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
    fun netFeeBalanceFetchedFromPortal() = runBlocking {
        val jar = CookieJar().apply { put(CasAuth.SSO_HOST, "CASTGC", System.getenv("SCP_CASTGC")) }
        val api = PortalApi(HttpEngine(jar))
        val balance = api.netFeeBalance()
        println("门户网费余额：$balance")
        assertNotNull("未取到网费余额", balance)
        assertTrue("网费余额格式异常：$balance", Regex("""^\d+\.\d{2}$""").matches(balance!!))
    }
}
