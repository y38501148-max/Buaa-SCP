package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ZfwPay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 网费充值二维码解析测试。 */
class ZfwPayTest {

    @Test
    fun extractsCashierUrlFromRelativeSrc() {
        val src = "/pay/qrcode?url=https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3Dabc123"
        assertEquals("https://cashier.cc-pay.cn/cashier?id=abc123", ZfwPay.cashierUrlFromQrSrc(src))
    }

    @Test
    fun extractsCashierUrlFromAbsoluteSrc() {
        val src = "https://zfw.buaa.edu.cn/pay/qrcode?t=1&url=" +
                "https%3A%2F%2Fcashier.cc-pay.cn%2Fcashier%3Fid%3Dxyz"
        assertEquals("https://cashier.cc-pay.cn/cashier?id=xyz", ZfwPay.cashierUrlFromQrSrc(src))
    }

    @Test
    fun rejectsNonCashierOrEmpty() {
        assertNull(ZfwPay.cashierUrlFromQrSrc(null))
        assertNull(ZfwPay.cashierUrlFromQrSrc(""))
        assertNull(ZfwPay.cashierUrlFromQrSrc("/pay/qrcode?url=https%3A%2F%2Fevil.example%2Fpay"))
        assertNull(ZfwPay.cashierUrlFromQrSrc("/images/logo.png"))
    }

    @Test
    fun parsesCashierId() {
        assertEquals("abc123", ZfwPay.cashierId("https://cashier.cc-pay.cn/cashier?id=abc123"))
        assertEquals("", ZfwPay.cashierId("https://cashier.cc-pay.cn/cashier"))
        assertEquals("", ZfwPay.cashierId(null))
    }
}
