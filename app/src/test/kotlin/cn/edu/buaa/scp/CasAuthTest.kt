package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.Html
import cn.edu.buaa.scp.ui.RechargePage
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAS 链路参数构造测试（不发起网络请求）。 */
class CasAuthTest {

    @Test
    fun serviceUrlUsesSentinelOpenid() {
        assertEquals(
            "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/6/auth",
            CasAuth.SERVICE,
        )
        assertTrue(CasAuth.SERVICE.contains(CasAuth.SENTINEL_OPENID))
    }

    @Test
    fun authUrlContainsEncodedServiceAndFlag() {
        val url = CasAuth.authUrl(true)
        assertEquals(
            "https://sso.buaa.edu.cn/login?service=" +
                    Html.urlEncode(CasAuth.SERVICE) + "&noAutoRedirect=1",
            url,
        )
        assertFalse(url.contains("openid=1"))
        assertTrue(url.contains("112233"))
        assertTrue(url.contains("noAutoRedirect=1"))
    }

    @Test
    fun authUrlCanOmitNoAutoRedirect() {
        assertFalse(CasAuth.authUrl(false).contains("noAutoRedirect"))
    }

    @Test
    fun loginPageCarriesCaptchaFlag() {
        val plain = CasAuth.LoginPage("exec-1")
        assertFalse(plain.hasCaptcha)
        val withCaptcha = CasAuth.LoginPage("exec-2", Html.Captcha("image", "cap-1"))
        assertTrue(withCaptcha.hasCaptcha)
    }

    @Test
    fun rechargePageKeepsOpenidInQuery() {
        val url = RechargePage.url("1F76+D504/中文")
        assertTrue(url.startsWith("https://m.icw.buaa.edu.cn/card/#/pages_other/recharge/cardrecharge/cardrecharge?"))
        assertTrue(url.contains("openid=1F76%2BD504%2F%E4%B8%AD%E6%96%87"))
        assertTrue(url.contains("usertype=6"))
        assertNull(null)
    }

    @Test
    fun maskKeepsEdges() {
        assertEquals("1F76…1234", CasAuth.mask("1F76D5049ABCDEF01234"))
        assertEquals("1***X", CasAuth.mask("1ABX"))
        assertEquals("", CasAuth.mask(null))
    }
}
