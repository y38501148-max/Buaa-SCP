package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cookie 存储测试：跨域隔离、路径匹配、Secure、过期删除。 */
class CookieJarTest {

    @Test
    fun absorbAndHeaderPerHost() {
        val jar = CookieJar()
        jar.absorb(
            "https://sso.buaa.edu.cn/login?service=x",
            listOf("CASTGC=TGT-abc; Path=/; Secure; HttpOnly"),
        )

        val ssoHeader = jar.header("https://sso.buaa.edu.cn/login?service=x")
        assertNotNull(ssoHeader)
        assertTrue(ssoHeader!!.contains("CASTGC=TGT-abc"))
        assertEquals("TGT-abc", jar.valueOf("sso.buaa.edu.cn", "CASTGC"))
        assertNull(jar.header("https://m.icw.buaa.edu.cn/card/goIndex"))
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "CASTGC"))
    }

    @Test
    fun multipleSetCookieInOneResponse() {
        val jar = CookieJar()
        jar.absorb(
            "https://m.icw.buaa.edu.cn/card/goIndex?openid=1",
            listOf("JSESSIONID=ABC123; Path=/; HttpOnly", "other=1; Path=/"),
        )
        val header = jar.header("https://m.icw.buaa.edu.cn/card/goIndex")
        assertNotNull(header)
        assertTrue(header!!.contains("JSESSIONID=ABC123"))
        assertTrue(header.contains("other=1"))
        assertEquals("ABC123", jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"))
    }

    @Test
    fun pathScopedCookieOnlyMatchesSubtree() {
        val jar = CookieJar()
        jar.absorb(
            "https://m.icw.buaa.edu.cn/card/login",
            listOf("token=t1; Path=/card"),
        )
        assertNotNull(jar.header("https://m.icw.buaa.edu.cn/card/goIndex"))
        assertNull(jar.header("https://m.icw.buaa.edu.cn/htwx/index"))
    }

    @Test
    fun maxAgeZeroDeletesCookie() {
        val jar = CookieJar()
        jar.absorb("https://m.icw.buaa.edu.cn/card/a", listOf("JSESSIONID=s1; Path=/"))
        assertNotNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"))

        jar.absorb(
            "https://m.icw.buaa.edu.cn/card/b",
            listOf("JSESSIONID=deleted; Path=/; Max-Age=0"),
        )
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"))
    }

    @Test
    fun domainCookieMatchesParentDomain() {
        val jar = CookieJar()
        jar.absorb("https://sso.buaa.edu.cn/login", listOf("buaa=1; Domain=.buaa.edu.cn; Path=/"))
        assertTrue(jar.header("https://sso.buaa.edu.cn/login")!!.contains("buaa=1"))
        assertTrue(jar.header("https://m.icw.buaa.edu.cn/card")!!.contains("buaa=1"))
        assertNull(jar.header("https://example.com/"))
    }

    @Test
    fun secureCookieNotSentOverPlainHttp() {
        val jar = CookieJar()
        jar.absorb("https://m.icw.buaa.edu.cn/card", listOf("secureOnly=1; Path=/; Secure"))
        assertNotNull(jar.header("https://m.icw.buaa.edu.cn/card"))
        assertNull(jar.header("http://m.icw.buaa.edu.cn/card"))
    }

    @Test
    fun putAndClear() {
        val jar = CookieJar()
        jar.put("m.icw.buaa.edu.cn", "JSESSIONID", "restored-session")
        assertEquals("restored-session", jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"))
        assertFalse(jar.isEmpty())
        jar.clear()
        assertTrue(jar.isEmpty())
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"))
    }
}
