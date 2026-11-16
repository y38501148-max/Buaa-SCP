package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ScanOutcome
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class CardContinuationTest {
    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun pollingWaitsUntilThereIsAnOutcome() {
        assertNull(ScanOutcome.order(JSONObject("""{"success":true}"""), "id"))
        assertNull(ScanOutcome.order(JSONObject("""{"success":true,"url":""}"""), "id"))
    }

    @Test fun pollingPreservesTopLevelPayloadForPasswordPage() {
        val root = JSONObject("""{"success":true,"url":"/pages/common/inputPass/inputPass","data":{"qrcode":"TEST-QR"}}""")
        val outcome = ScanOutcome.order(root, "test-id")!!
        val payload = JSONObject(query(outcome.followUp!!).getValue("data"))
        assertEquals("TEST-QR", payload.getJSONObject("data").getString("qrcode"))
        assertEquals("test-id", payload.getString("openid"))
        assertFalse(root.has("openid"))
        assertEquals("请继续处理支付", outcome.message)
    }

    @Test fun pollingFailureIsNotMistakenForSuccessOrFollowUp() {
        val result = ScanOutcome.order(JSONObject("""{"success":false,"message":"余额不足","url":"/pages/common/success/success"}"""), "id")!!
        assertFalse(result.success)
        assertEquals("余额不足", result.message)
        assertNull(result.followUp)
    }

    @Test fun queryParametersSurviveWithoutOverridingIdentityOrPayload() {
        val url = ScanOutcome.pageUrl("/pages/common/inputPass/inputPass?foo=a%26b%3D1&label=%E4%B8%AD%E6%96%87&%6fpenid=stale&data=stale&usertype=1",
            JSONObject().put("qrcode", "TEST-QR"), "id+&='\"\\\n")
        val params = query(url)
        assertEquals("a&b=1", params["foo"])
        assertEquals("中文", params["label"])
        assertEquals("id+&='\"\\\n", params["openid"])
        assertEquals("6", params["usertype"])
        assertEquals("TEST-QR", JSONObject(params.getValue("data")).getString("qrcode"))
        assertEquals(5, params.size)
    }

    @Test fun rejectsMalformedRoutesAndQueries() {
        for (route in listOf("https://evil.test/pages/a", "//evil.test/pages/a", "/pages/a#fragment", "/pages/a?foo=%", "/pages/a?foo=%0A", "/pages/../a", "/pages/%2e%2e/a", "/pages//a")) {
            assertThrows(route, IllegalArgumentException::class.java) { ScanOutcome.pageUrl(route, JSONObject(), "id") }
        }
    }

    @Test fun unsupportedFollowUpExplainsThatContinuationIsBlocked() {
        val result = ScanOutcome.parse(JSONObject("""{"success":true,"resultData":{"url":"https://evil.test/"}}"""), "id")
        assertNull(result.followUp)
        assertTrue(result.message.contains("服务端要求继续处理"))
        assertFalse(result.message.contains("支付成功"))
    }

    @Test fun navigationAndResultDetectionRequireTheOfficialDocument() {
        val prefix = "https://m.icw.buaa.edu.cn/card/#"
        assertEquals("操作成功", ScanOutcome.terminalMessage(prefix + "/pages/common/success/success?data=%7B%7D"))
        assertEquals("操作未完成", ScanOutcome.terminalMessage(prefix + "/pages/common/fail/fail"))
        assertNull(ScanOutcome.terminalMessage(prefix + "/pages/common/inputPass/inputPass"))
        for (url in listOf("https://evil.test/card/#/pages/common/success/success", "https://m.icw.buaa.edu.cn/card-evil/", "https://m.icw.buaa.edu.cn:444/card/", "https://x@m.icw.buaa.edu.cn/card/", "http://m.icw.buaa.edu.cn/card/", "https://m.icw.buaa.edu.cn/card/../evil/")) {
            assertFalse(url, ScanOutcome.isTrustedPage(url))
            assertNull(ScanOutcome.terminalMessage(url))
        }
    }
}
