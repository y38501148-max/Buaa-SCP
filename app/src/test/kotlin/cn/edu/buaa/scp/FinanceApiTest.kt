package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.FinanceApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** 财务登录逐跳地址处理（纯函数）测试。 */
class FinanceApiTest {

    @Test
    fun normalizeLocation_forcesHttps() {
        // 初始 Location 是 http 的 icw 回调地址，必须改成 https 再请求。
        assertEquals(
            "https://icw.buaa.edu.cn/checkbuaa.aspx?ticket=ST-1",
            FinanceApi.normalizeLocation(
                "https://sso.buaa.edu.cn/login?service=x",
                "http://icw.buaa.edu.cn/checkbuaa.aspx?ticket=ST-1",
            ),
        )
    }

    @Test
    fun normalizeLocation_dropsPort() {
        assertEquals(
            "https://icw.buaa.edu.cn/checkbuaa.aspx",
            FinanceApi.normalizeLocation(
                "https://sso.buaa.edu.cn/login",
                "http://icw.buaa.edu.cn:8080/checkbuaa.aspx",
            ),
        )
        assertEquals(
            "https://chaxun.icw.buaa.edu.cn/gxzhcxzhb/login?Userid=u&Cypt=c&Stamp=s",
            FinanceApi.normalizeLocation(
                "https://icw.buaa.edu.cn/jump.aspx?sysid=wscx",
                "http://chaxun.icw.buaa.edu.cn:80/gxzhcxzhb/login?Userid=u&Cypt=c&Stamp=s",
            ),
        )
    }

    @Test
    fun normalizeLocation_resolvesRelativeLocation() {
        // 相对地址按当前响应 URL 解析，host 随 base 变化。
        assertEquals(
            "https://sso.buaa.edu.cn/checkbuaa.aspx?ticket=ST-2",
            FinanceApi.normalizeLocation(
                "https://sso.buaa.edu.cn/login?service=x",
                "/checkbuaa.aspx?ticket=ST-2",
            ),
        )
        assertEquals(
            "https://icw.buaa.edu.cn/checkbuaa.aspx?ticket=ST-2",
            FinanceApi.normalizeLocation(
                "https://icw.buaa.edu.cn/jump.aspx?sysid=wscx",
                "/checkbuaa.aspx?ticket=ST-2",
            ),
        )
        assertEquals(
            "https://sso.buaa.edu.cn/login?service=x",
            FinanceApi.normalizeLocation("https://icw.buaa.edu.cn/jump.aspx", "https://sso.buaa.edu.cn/login?service=x"),
        )
    }

    @Test
    fun normalizeLocation_rejectsUnknownHost() {
        val error = assertThrows(ApiException::class.java) {
            FinanceApi.normalizeLocation(
                "https://sso.buaa.edu.cn/login",
                "https://evil.example.com/steal?ticket=ST-1",
            )
        }
        assertEquals("财务登录跳转到未知地址", error.message)
        assertFalse(error.authExpired)
    }
}
