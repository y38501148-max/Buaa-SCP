package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.CardPaymentApi
import cn.edu.buaa.scp.net.Crypto
import cn.edu.buaa.scp.net.PaymentCode
import cn.edu.buaa.scp.net.ScanOutcome
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CardPaymentTest {
    @Test fun decodesOfficialEnvelopeAndBinaryQrWithoutUtf8Expansion() {
        val raw = "5638007f80ff0120aabbccdd"
        val envelope = Crypto.encryptToDatajson("AbCdEf0123456789", """{"success":true,"data":{"code":"$raw","cardbal":"20.10"}}""")
        val root = CardPaymentApi.decode(200, JSONObject().put("datajson", envelope).toString())
        val payment = PaymentCode.parse(root)
        assertEquals("20.10", payment.balance)
        val bytes = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(bytes, payment.qrContent().toByteArray(Charsets.ISO_8859_1))
        val matrix = QRCodeWriter().encode(payment.qrContent(), BarcodeFormat.QR_CODE, 320, 320,
            mapOf(EncodeHintType.MARGIN to 4))
        val pixels = IntArray(320 * 320) { i -> if (matrix[i % 320, i / 320]) 0xff000000.toInt() else -1 }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(320, 320, pixels))))
        @Suppress("UNCHECKED_CAST")
        val segments = decoded.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertArrayEquals(bytes, segments.single())
    }

    @Test fun rejectsMissingOrMalformedCode() {
        for (code in listOf("", "null", "5638A", "5638ZZ", "a".repeat(8193))) {
            assertThrows(Exception::class.java) {
                PaymentCode.parse(JSONObject().put("success", true).put("data", JSONObject().put("code", code)))
            }
        }
        assertThrows(ApiException::class.java) { PaymentCode.parse(JSONObject("""{"success":false,"message":"未开通"}""")) }
        assertEquals("test-content", PaymentCode("test-content").qrContent())
    }

    @Test fun handlesAuthFailureAndDoesNotLeakResponseBody() {
        val error = assertThrows(ApiException::class.java) { CardPaymentApi.decode(403, "secret") }
        assertTrue(error.authExpired)
        assertTrue(assertThrows(ApiException::class.java) {
            CardPaymentApi.decode(200, """{"success":false,"message":"资源受限"}""")
        }.authExpired)
        for (body in listOf("<html>secret</html>", "{}", """{"datajson":"secret"}""")) {
            val invalid = assertThrows(ApiException::class.java) { CardPaymentApi.decode(200, body) }
            assertFalse(invalid.message.orEmpty().contains("secret"))
        }
    }

    @Test fun preservesFailedResponseWithContinuation() {
        val root = CardPaymentApi.decode(200, """{"success":false,"resultData":{"url":"/pages/scan/confirm","amount":"1.00"}}""")
        val result = ScanOutcome.parse(root, "openid-test")
        assertFalse(result.success)
        assertTrue(result.followUp!!.startsWith("https://m.icw.buaa.edu.cn/card/#/pages/scan/confirm?data="))
        assertTrue(result.followUp.contains("%22amount%22"))
    }

    @Test fun distinguishesScanOutcomes() {
        for ((type, message) in mapOf("scj" to "签到成功", "opendevice" to "设备开阀成功", "bindTray" to "绑盘成功", "" to "支付成功")) {
            val result = ScanOutcome.parse(JSONObject().put("success", true).put("resultData", JSONObject().put("type", type)), "oid")
            assertEquals(message, result.message)
            assertNull(result.followUp)
        }
        assertEquals("余额不足", ScanOutcome.parse(JSONObject("""{"success":false,"message":"余额不足"}"""), "oid").message)
    }

    @Test fun rejectsUntrustedContinuationUrls() {
        for (url in listOf("https://evil.example/", "//evil.example/", "/pages/../evil", "/pages/a#evil", "javascript:alert(1)", "/pages/a%2F..", "/pages/a\\evil")) {
            assertThrows(IllegalArgumentException::class.java) { ScanOutcome.pageUrl(url, JSONObject(), "oid") }
        }
    }

    @Test fun scanPayloadPreservesCodeAndTimestamp() {
        val code = "https://example.invalid/pay?a=1&b=2"
        val payload = CardPaymentApi.scanPayload(code, 123456789L)
        assertEquals(code, payload.getString("qrcode"))
        assertEquals(123456789L, payload.getLong("paytime"))
        assertThrows(IllegalArgumentException::class.java) { CardPaymentApi.scanPayload("", 1L) }
        assertThrows(IllegalArgumentException::class.java) { CardPaymentApi.scanPayload("code", 0L) }
    }
}
