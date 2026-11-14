package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.net.ZfwAuth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.TrustManager

/**
 * 校园网原生登录页真实探针（默认跳过）：
 * 验证未登录状态下能取到登录页 CSRF / RSA 公钥，并加载图形验证码图片。
 *
 * ```
 * SCP_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest -Plive=1 \
 *   --tests 'cn.edu.buaa.scp.LiveZfwAuthTest'
 * ```
 */
class LiveZfwAuthTest {

    @Before
    fun requireLiveFlag() {
        assumeTrue(
            "需要 SCP_LIVE_TEST=1 才运行",
            "1" == System.getenv("SCP_LIVE_TEST") || java.lang.Boolean.getBoolean("live"),
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
    fun loginPageExposesCsrfAndRsaKey() = runBlocking {
        val auth = ZfwAuth(HttpEngine(CookieJar()))
        val page = auth.loadLoginPage()
        assertNotNull("未解析到登录页（可能当前网络环境已登录）", page)
        assertTrue("CSRF 为空", page!!.csrfToken.isNotBlank())
        assertTrue("RSA 公钥异常", page.publicKeyPem.contains("BEGIN PUBLIC KEY"))
        val encrypted = ZfwAuth.encryptPassword(page.publicKeyPem, "test-only")
        assertTrue("RSA 加密结果异常", encrypted.length > 100)
        val captcha = auth.captchaImage()
        assertTrue("验证码图片为空", captcha.size > 500)
    }
}
