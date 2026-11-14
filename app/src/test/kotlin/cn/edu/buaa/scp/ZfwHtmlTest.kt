package cn.edu.buaa.scp

import cn.edu.buaa.scp.model.ZfwHome
import cn.edu.buaa.scp.net.ZfwHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** zfw 校园网首页解析测试（夹具已脱敏）。 */
class ZfwHtmlTest {

    private val html: String = TestFixtures.text("html/zfw_home.html")

    @Test
    fun parsesUserInfoAndTrafficTable() {
        val home = ZfwHtml.parseHome(html)
        assertNotNull(home)
        home!!
        assertEquals("20250001", home.username)
        assertEquals("张同学", home.name)
        assertEquals("正常", home.status)
        assertEquals("学生", home.product)
        assertEquals("85.689G", home.usedTraffic)
        assertEquals("415.181G", home.billedRemain)
        assertEquals("0byte", home.freeRemain)
        assertEquals("2026-12-01", home.settleDate)
        assertEquals(3, home.onlineCount)
        assertTrue(home.statusNormal)
    }

    @Test
    fun formatsTrafficText() {
        val home = ZfwHtml.parseHome(html)!!
        assertEquals("415.18 GB", home.billedRemainText)
        assertEquals("85.69 GB", home.usedTrafficText)
        assertEquals("0", home.freeRemainText)
        assertEquals("1.5 GB", ZfwHome.trafficText("1.5G"))
        assertEquals("512 MB", ZfwHome.trafficText("512M"))
        assertEquals("0", ZfwHome.trafficText("0byte"))
        assertEquals("—", ZfwHome.trafficText(""))
        assertEquals("未知", ZfwHome.trafficText("未知"))
    }

    @Test
    fun loginPageReturnsNull() {
        val login = """
            <html><body>
            <form id="login-form" action="/" method="post">
              <input name="LoginForm[username]">
              <input name="LoginForm[password]">
            </form>
            </body></html>
        """.trimIndent()
        assertTrue(ZfwHtml.isLoginPage(login))
        assertNull(ZfwHtml.parseHome(login))
    }

    @Test
    fun userInfoWithoutProductTableStillParses() {
        val partial = """
            <html><body>
            <ul class="list-group">
              <li class="list-group-item"><label class="list-group-label">用户名</label>20250001</li>
              <li class="list-group-item"><label class="list-group-label">姓名</label>张同学</li>
            </ul>
            </body></html>
        """.trimIndent()
        val home = ZfwHtml.parseHome(partial)
        assertNotNull(home)
        home!!
        assertEquals("20250001", home.username)
        assertEquals("张同学", home.name)
        assertEquals("—", home.billedRemainText)
        assertEquals(0, home.onlineCount)
    }

    @Test
    fun emptyPageReturnsNull() {
        assertNull(ZfwHtml.parseHome("<html><body>维护中</body></html>"))
    }
}
