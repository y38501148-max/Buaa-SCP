package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.RechargeFailure
import cn.edu.buaa.scp.net.RechargeFailureKind
import cn.edu.buaa.scp.net.RechargeFailureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 充值失败原因分类测试。
 *
 * 夹具使用官方真实跳转形状：
 * - fail 页：`/card/?isError=ERROR#/pages/common/fail/fail?data=<urlencoded JSON>`
 * - error 页：`/pages/common/error/error?message=<urlencoded 文本>`
 * 中文参数均按真实 URL 做百分号编码，避免只用明文而漏掉解码问题。
 */
class RechargeFailureTest {

    private val failPagePrefix =
        "https://m.icw.buaa.edu.cn/card/?isError=ERROR#/pages/common/fail/fail?data="
    private val errorPagePrefix =
        "https://m.icw.buaa.edu.cn/card/?isError=ERROR#/pages/common/error/error?message="

    // ---------------- classify：官方 message 文本 ----------------

    @Test
    fun classify_notInWindow() {
        val failure = RechargeFailureParser.classify("不在缴费期间")
        assertEquals(RechargeFailureKind.NOT_IN_WINDOW, failure.kind)
        assertEquals("当前不在校园卡缴费时段，充值入口暂未开放", failure.message)
    }

    @Test
    fun classify_notInWindowVariants() {
        listOf("不在缴费时段", "当前不在充值时间", "非缴费时段").forEach { raw ->
            assertEquals(raw, RechargeFailureKind.NOT_IN_WINDOW, RechargeFailureParser.classify(raw).kind)
        }
    }

    @Test
    fun classify_sessionExpired() {
        val failure = RechargeFailureParser.classify("会话失效，请刷新页面！")
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure.kind)
        assertEquals("登录已失效，请返回重新登录后再试", failure.message)
    }

    @Test
    fun classify_unauthenticatedRequest() {
        val failure = RechargeFailureParser.classify("未认证的请求，请认证后访问")
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure.kind)
        assertEquals("登录已失效，请返回重新登录后再试", failure.message)
    }

    @Test
    fun classify_loginExpired() {
        val failure = RechargeFailureParser.classify("登录过期，请退出重新访问")
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure.kind)
    }

    @Test
    fun classify_unknownMessageIsOther() {
        val failure = RechargeFailureParser.classify("系统繁忙，请稍后再试")
        assertEquals(RechargeFailureKind.OTHER, failure.kind)
        assertEquals("充值入口暂时不可用：系统繁忙，请稍后再试", failure.message)
    }

    @Test
    fun classify_blankIsOtherWithoutCause() {
        assertEquals(
            RechargeFailure(RechargeFailureKind.OTHER, "充值入口暂时不可用"),
            RechargeFailureParser.classify(null),
        )
        assertEquals(
            RechargeFailure(RechargeFailureKind.OTHER, "充值入口暂时不可用"),
            RechargeFailureParser.classify("   "),
        )
    }

    // ---------------- fromPageUrl：WebView 失败页 ----------------

    @Test
    fun fromPageUrl_failPageWithEncodedDataJson() {
        // data=%7B%22title%22%3A%22...%22%2C%22message%22%3A%22...%22%7D
        val url = failPagePrefix + "%7B%22title%22%3A%22%E9%A1%B5%E9%9D%A2%E9%94%99" +
            "%E8%AF%AF%22%2C%22message%22%3A%22%E4%B8%8D%E5%9C%A8%E7%BC%B4%E8%B4%B9" +
            "%E6%9C%9F%E9%97%B4%22%7D"
        val failure = RechargeFailureParser.fromPageUrl(url)
        assertEquals(RechargeFailureKind.NOT_IN_WINDOW, failure?.kind)
        assertEquals("当前不在校园卡缴费时段，充值入口暂未开放", failure?.message)
    }

    @Test
    fun fromPageUrl_failPageWithSessionExpiredMessage() {
        val url = failPagePrefix + "%7B%22title%22%3A%22%E9%A1%B5%E9%9D%A2%E9%94%99" +
            "%E8%AF%AF%22%2C%22message%22%3A%22%E4%BC%9A%E8%AF%9D%E5%A4%B1%E6%95%88" +
            "%EF%BC%8C%E8%AF%B7%E5%88%B7%E6%96%B0%E9%A1%B5%E9%9D%A2%EF%BC%81%22%7D"
        val failure = RechargeFailureParser.fromPageUrl(url)
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure?.kind)
        assertEquals("登录已失效，请返回重新登录后再试", failure?.message)
    }

    @Test
    fun fromPageUrl_errorPageWithEncodedMessage() {
        val url = errorPagePrefix + "%E6%9C%AA%E8%AE%A4%E8%AF%81%E7%9A%84%E8%AF%B7" +
            "%E6%B1%82%EF%BC%8C%E8%AF%B7%E8%AE%A4%E8%AF%81%E5%90%8E%E8%AE%BF%E9%97%AE"
        val failure = RechargeFailureParser.fromPageUrl(url)
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure?.kind)
        assertEquals("登录已失效，请返回重新登录后再试", failure?.message)
    }

    @Test
    fun fromPageUrl_normalRechargePageReturnsNull() {
        assertNull(
            RechargeFailureParser.fromPageUrl(
                "https://m.icw.buaa.edu.cn/card/#/pages_other/recharge/cardrecharge/cardrecharge" +
                    "?openid=1F76%2BD504&usertype=6",
            ),
        )
        assertNull(RechargeFailureParser.fromPageUrl("https://m.icw.buaa.edu.cn/card/"))
        assertNull(RechargeFailureParser.fromPageUrl(null))
        assertNull(RechargeFailureParser.fromPageUrl(""))
    }

    @Test
    fun fromPageUrl_failPageWithoutMessageIsOther() {
        // 没有 data / message 参数，只能给出泛化文案。
        val failure = RechargeFailureParser.fromPageUrl(
            "https://m.icw.buaa.edu.cn/card/?isError=ERROR#/pages/common/fail/fail",
        )
        assertEquals(RechargeFailureKind.OTHER, failure?.kind)
        assertEquals("充值入口暂时不可用", failure?.message)
    }

    @Test
    fun fromPageUrl_unencodedDataJsonStillParsed() {
        val url = "https://m.icw.buaa.edu.cn/card/#/pages/common/fail/fail" +
            "?data={\"title\":\"页面错误\",\"message\":\"不在缴费期间\"}"
        val failure = RechargeFailureParser.fromPageUrl(url)
        assertEquals(RechargeFailureKind.NOT_IN_WINDOW, failure?.kind)
    }

    // ---------------- fromOpenCardPay：预检异常 ----------------

    @Test
    fun fromOpenCardPay_nullMeansSuccess() {
        assertNull(RechargeFailureParser.fromOpenCardPay(null))
    }

    @Test
    fun fromOpenCardPay_notInWindow() {
        val failure = RechargeFailureParser.fromOpenCardPay(
            ApiException("不在缴费期间", authExpired = false),
        )
        assertEquals(RechargeFailureKind.NOT_IN_WINDOW, failure?.kind)
        assertEquals("当前不在校园卡缴费时段，充值入口暂未开放", failure?.message)
    }

    @Test
    fun fromOpenCardPay_authExpired() {
        val failure = RechargeFailureParser.fromOpenCardPay(
            ApiException("会话无效（HTTP 401），请重新登录", authExpired = true),
        )
        assertEquals(RechargeFailureKind.SESSION_EXPIRED, failure?.kind)
        assertEquals("登录已失效，请返回重新登录后再试", failure?.message)
    }

    @Test
    fun fromOpenCardPay_plainBusinessMessage() {
        val failure = RechargeFailureParser.fromOpenCardPay(
            ApiException("未绑定银行卡", authExpired = false),
        )
        assertEquals(RechargeFailureKind.OTHER, failure?.kind)
        assertEquals("充值入口暂时不可用：未绑定银行卡", failure?.message)
    }
}
