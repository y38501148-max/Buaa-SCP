package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.Crypto
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解密算法测试。
 *
 * 测试向量由 Python（pycryptodome）独立实现生成，避免「自己加密自己解密」的假阳性。
 */
class CryptoTest {

    /** 由 Python 独立生成的 datajson（密钥素材 + Base64(AES-128-ECB/PKCS7)）。 */
    private val vectorBalance =
        "q7Zp2LmW9xRt4KcVttl2H53jNcqc6xr2KnhRjtgI4qtlyqC35wTBkFY8IbUmfLtnDxd3BStijlnNJBlCszYFN4giYEsOG4oncg58IFwzQZaXKEit6KF9PJkJCnmDJtau9VxzZ0+pjPKC3nYs5qctCUabo7KfBIBrKHp7vc8uSMVqYkbFngkz2WENB7o="

    private val vectorBill =
        "Zx81mNq0Pl34RtYbc5xuy3+rMKTRhpFAej5cua9oNXTEkbso140iMAdJgQSbbB8b6yDwS0bsYZ5q2GcKJGXNGV0PVVJH/XbmgMMWCqD7/VFAJ/AwkMBrcDWF6Af0uudNKj6UkcSj+qBsQnfq"

    @Test
    fun handleKey_matchesFrontendAlgorithm() {
        assertEquals("x9WmL2pZ7qVcK4tR", Crypto.handleKey("q7Zp2LmW9xRt4KcV"))
        assertEquals("lP0qNm18xZbYtR43", Crypto.handleKey("Zx81mNq0Pl34RtYb"))
    }

    @Test
    fun decrypt_balanceVector() {
        val root = Crypto.decrypt(vectorBalance)
        assertTrue(root.getBoolean("success"))
        val cardPay = root.getJSONObject("data").getJSONObject("cardPay")
        assertEquals("20241234", cardPay.getString("idserial"))
        assertEquals("123.45", cardPay.getString("cardbal"))
    }

    @Test
    fun decrypt_billVector() {
        val root = Crypto.decrypt(vectorBill)
        val row = root.getJSONObject("resultData").getJSONArray("billList").getJSONObject(0)
        assertEquals("2026-11", row.getString("txdate"))
        assertEquals(5180, row.getInt("txamt"))
    }

    @Test
    fun roundTrip_withChinese() {
        val plain = """{"message":"会话失效，请刷新页面！","success":false}"""
        val datajson = Crypto.encryptToDatajson("Zx81mNq0Pl34RtYb", plain)
        val root = Crypto.decrypt(datajson)
        assertFalse(root.getBoolean("success"))
        assertEquals("会话失效，请刷新页面！", root.getString("message"))
    }

    @Test(expected = JSONException::class)
    fun decrypt_rejectsShortInput() {
        Crypto.decrypt("too-short")
    }

    /** 官方前端同样用这套算法加密请求体；向量来自官方 bundle 的独立实现。 */
    @Test
    fun requestEnvelopeMatchesOfficialVector() {
        assertEquals(
            "0123456789ABCDEFQUC/RjeZI7uX6hvq57W3O+gndGvKFcxYHY8K8NcWYrc=",
            Crypto.encryptToDatajson("0123456789ABCDEF", """{"ok":true,"n":1}"""),
        )
    }

    @Test
    fun randomKey16UsesOfficialAlphabet() {
        val key = Crypto.randomKey16()
        assertEquals(16, key.length)
        assertTrue(key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' })
    }
}
