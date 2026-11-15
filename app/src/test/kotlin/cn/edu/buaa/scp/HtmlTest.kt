package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.Html
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML/URL 解析测试，含一份脱敏后的真实 CAS 登录页夹具（`src/test/resources/cas_login.html`）。
 */
class HtmlTest {

    @Test
    fun hiddenInputValue_fromRealCasPage() {
        val html = TestFixtures.text("cas_login.html")
        val execution = Html.hiddenInputValue(html, "execution")
        assertNotNull("真实 CAS 页面必须能解析出 execution", execution)
        val value = requireNotNull(execution)
        assertTrue("execution 长度异常：${value.length}", value.length > 1000)
        assertTrue(value.startsWith("FIXTURE-"))
        assertEquals("submit", Html.hiddenInputValue(html, "_eventId"))
        assertEquals("username_password", Html.hiddenInputValue(html, "type"))
    }

    @Test
    fun hiddenInputValue_acceptsAnyAttributeOrder() {
        assertEquals("v1", Html.hiddenInputValue("<input value=\"v1\" name=\"execution\">", "execution"))
        assertEquals("v2", Html.hiddenInputValue("<input name='execution' value='v2'/>", "execution"))
        assertNull(Html.hiddenInputValue("<input name=\"other\" value=\"x\">", "execution"))
    }

    @Test
    fun errorMessage_detectsInvalidCredentials() {
        val message = Html.errorMessage(
            "<html><body><div class='alert'>Invalid credentials.</div></body></html>",
        )
        assertNotNull(message)
        assertTrue(message!!.contains("账号或密码错误"))
    }

    @Test
    fun errorMessage_lockWinsOverCredentials() {
        val message = Html.errorMessage(
            "<html><body><div>密码错误次数过多，账号已被锁定</div></body></html>",
        )
        assertNotNull(message)
        assertTrue(message!!.contains("锁定"))
    }

    @Test
    fun errorMessage_tooManyWinsOverCredentials() {
        val message = Html.errorMessage(
            "<html><body><div>密码错误，尝试过于频繁</div></body></html>",
        )
        assertNotNull(message)
        assertTrue(message!!.contains("频繁"))
    }

    @Test
    fun captcha_nullOnPlainLoginPage() {
        assertNull(Html.captcha(TestFixtures.text("cas_login.html")))
    }

    @Test
    fun captcha_readsInlineCasConfig() {
        val html = """
            <html><script>
              config.captcha = { type: 'image', id: 'captcha-abc_123' };
            </script></html>
        """.trimIndent()
        val captcha = Html.captcha(html)
        assertNotNull(captcha)
        assertEquals("image", captcha!!.type)
        assertEquals("captcha-abc_123", captcha.id)
    }

    @Test
    fun captcha_acceptsJsonAndImageUrlShapes() {
        assertEquals(
            "captcha_2",
            Html.captcha("""<script>window.login={"captcha":{"type":"image","id":"captcha_2"}}</script>""")?.id,
        )
        assertEquals(
            "captcha-3",
            Html.captcha("""<img src="/captcha?captchaId=captcha-3&ts=1">""")?.id,
        )
    }

    @Test
    fun captcha_keepsUnsupportedTypeForCaller() {
        val captcha = Html.captcha("config.captcha = { type: 'slider', id: 'x1' }")
        assertNotNull(captcha)
        assertEquals("slider", captcha!!.type)
    }

    @Test
    fun errorMessage_doesNotTreatCaptchaPageAsFatal() {
        val message = Html.errorMessage(
            "<html><body><div>请输入验证码</div>" +
                    "<script>config.captcha = { type: 'image', id: 'x' }</script></body></html>",
        )
        assertNull(message)
    }

    @Test
    fun stripTags_removesScriptsAndTags() {
        val text = Html.stripTags("<div>余额 <b>123.45</b></div><script>var a=1;</script>")
        assertEquals("余额 123.45", text)
    }

    @Test
    fun queryParam_readsEncodedValues() {
        val url = "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/6/auth" +
                "?ticket=ST-1&openid=1F76%2BD504&usertype=6"
        assertEquals("ST-1", Html.queryParam(url, "ticket"))
        assertEquals("1F76+D504", Html.queryParam(url, "openid"))
        assertEquals("6", Html.queryParam(url, "usertype"))
        assertNull(Html.queryParam(url, "missing"))
    }

    @Test
    fun failPageMessage_decodesDataJson() {
        val location = "https://m.icw.buaa.edu.cn/card/?isError=ERROR#/pages/common/fail/fail" +
                "?data=%7B%22message%22%3A%22%E7%94%A8%E6%88%B7%E4%BF%A1%E6%81%AF%E4%B8%8D" +
                "%E5%8C%B9%E9%85%8D%22%7D"
        assertEquals("用户信息不匹配", Html.failPageMessage(location))
    }

    @Test
    fun jsonMessage_returnsBusinessMessage() {
        assertEquals(
            "资源受限，页面丢失!",
            Html.jsonMessage("""{"message":"资源受限，页面丢失!","success":false}"""),
        )
        assertNull(Html.jsonMessage("<html></html>"))
    }

    @Test
    fun urlEncode_keepsServiceUrlIntact() {
        val encoded = Html.urlEncode("https://m.icw.buaa.edu.cn/card/a?x=1&y=2")
        assertFalse(encoded.contains("?"))
        assertEquals("https://m.icw.buaa.edu.cn/card/a?x=1&y=2", Html.urlDecode(encoded))
    }
}
