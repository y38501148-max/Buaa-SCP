package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.CardOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 卡务写操作参数与校验测试：字段名按官方 bundle 逐字锁定。 */
class CardOpsTest {

    @Test
    fun reportLossPayloadMatchesOfficialFields() {
        val json = CardOps.reportLoss("openid-1", "张同学", "20250001", "123456")
        assertEquals("openid-1", json.getString("openid"))
        assertEquals("张同学", json.getString("username"))
        assertEquals("20250001", json.getString("idserial"))
        assertEquals("123456", json.getString("bankpwd"))
    }

    @Test
    fun solutionHangUsesCardPasswordField() {
        val json = CardOps.solutionHang("openid-1", "张同学", "20250001", "654321")
        assertEquals("654321", json.getString("bankpwd"))
    }

    @Test
    fun modifyPasswordCarriesFlagAndConfirmation() {
        val json = CardOps.modifyPassword("20250001", "openid-1", "111111", "222222", "222222")
        assertEquals("20250001", json.getString("idserial"))
        assertEquals("111111", json.getString("oldpassword"))
        assertEquals("222222", json.getString("newpwd"))
        assertEquals("222222", json.getString("txpassword"))
        assertEquals("openid-1", json.getString("openid"))
        assertEquals(1, json.getInt("flag"))
    }

    @Test
    fun cardQuotaUsesCardFieldNames() {
        val query = CardOps.cardQuotaQuery(
            "openid-1", "20250001", "CARD-ID", "5000", "20000", "123456",
        )
        assertEquals("openid-1", query["openid"])
        assertEquals("20250001", query["idserial"])
        assertEquals("5000", query["maxconsamt"])
        assertEquals("20000", query["maxconstolamt"])
        assertEquals("CARD-ID", query["cardid"])
        assertEquals("123456", query["txpasswd"])
    }

    @Test
    fun qrcodeQuotaUsesSlightlyDifferentFieldName() {
        val query = CardOps.qrcodeQuotaQuery(
            "openid-1", "20250001", "QR-ID", "5000", "20000", "123456",
        )
        assertEquals("QR-ID", query["qrcodeid"])
        // 官方校园码接口的“单日限额”字段是 maxconstotalamt，与校园卡不同。
        assertEquals("20000", query["maxconstotalamt"])
        assertFalse(query.containsKey("maxconstolamt"))
    }

    @Test
    fun bindAndUnbindBankCardPayload() {
        val bind = CardOps.bindBankCard("openid-1", "20250001", "6222020200001235")
        assertEquals("6222020200001235", bind.getString("bankcdno"))
        assertTrue(bind.getBoolean("wechatui"))
        assertFalse(bind.has("bankpwd"))

        val unbind = CardOps.unbindBankCard("openid-1", "20250001", "6222020200001235", "123456")
        assertEquals("6222020200001235", unbind.getString("bankcdno"))
        assertEquals("123456", unbind.getString("bankpwd"))
        assertTrue(unbind.getBoolean("wechatui"))
    }

    @Test
    fun yuanToFenMatchesOfficialConversion() {
        assertEquals("1200", CardOps.yuanToFen("12"))
        assertEquals("1230", CardOps.yuanToFen("12.3"))
        assertEquals("1234", CardOps.yuanToFen("12.34"))
        assertEquals("1", CardOps.yuanToFen("0.01"))
        assertEquals("0", CardOps.yuanToFen(""))
        assertEquals("0", CardOps.yuanToFen("abc"))
    }

    @Test
    fun cardPasswordValidation() {
        assertTrue(CardOps.isCardPassword("123456"))
        assertFalse(CardOps.isCardPassword("12345"))
        assertFalse(CardOps.isCardPassword("1234567"))
        assertFalse(CardOps.isCardPassword("12345a"))
        assertFalse(CardOps.isCardPassword(""))
    }

    @Test
    fun bankCardValidationUsesLuhnAndPrefix() {
        assertTrue(CardOps.isBankCardNo("6222020200001235"))
        assertTrue(CardOps.isBankCardNo("6222020200001234562"))
        assertFalse(CardOps.isBankCardNo("6222020200001234"))
        assertFalse(CardOps.isBankCardNo("622202020000123"))
        assertFalse(CardOps.isBankCardNo("1234567890123456"))
        assertFalse(CardOps.isBankCardNo("622202020000123a"))
    }

    @Test
    fun bankCardMaskKeepsPrefixAndSuffix() {
        assertEquals("622202 **** **** 1235", CardOps.maskBankCardNo("6222020200001235"))
        assertEquals("", CardOps.maskBankCardNo(""))
    }
}
