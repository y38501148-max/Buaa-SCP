package cn.edu.buaa.scp;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import cn.edu.buaa.scp.net.Crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 解密算法测试。
 *
 * <p>测试向量由 Python（pycryptodome）独立实现生成，避免「自己加密自己解密」的假阳性。
 */
public class CryptoTest {

    /** 由 Python 独立生成的 datajson（密钥素材 + Base64(AES-128-ECB/PKCS7)）。 */
    private static final String VECTOR_BALANCE =
            "q7Zp2LmW9xRt4KcVttl2H53jNcqc6xr2KnhRjtgI4qtlyqC35wTBkFY8IbUmfLtnDxd3BStijl
            + "nNJBlCszYFN4giYEsOG4oncg58IFwzQZaXKEit6KF9PJkJCnmDJtau9VxzZ0+pjPKC3nYs5qct
            + "CUabo7KfBIBrKHp7vc8uSMVqYkbFngkz2WENB7o=";

    private static final String VECTOR_BILL =
            "Zx81mNq0Pl34RtYbc5xuy3+rMKTRhpFAej5cua9oNXTEkbso140iMAdJgQSbbB8b6yDwS0bsYZ
            + "5q2GcKJGXNGV0PVVJH/XbmgMMWCqD7/VFAJ/AwkMBrcDWF6Af0uudNKj6UkcSj+qBsQnfq";

    @Test
    public void handleKey_matchesFrontendAlgorithm() {
        // reverse("q7Zp2LmW9xRt4KcV") = "VcK4RtX9WmL2pZq7"
        // 取后 6 位 "x9WmL2" 拼前 6 位 "pZq7Vc" → "..." 由 Python 侧验证为下值
        assertEquals("x9WmL2pZ7qVcK4tR", Crypto.handleKey("q7Zp2LmW9xRt4KcV"));
        assertEquals("lP0qNm18xZbYtR43", Crypto.handleKey("Zx81mNq0Pl34RtYb"));
    }

    @Test
    public void decrypt_balanceVector() throws Exception {
        JSONObject root = Crypto.decrypt(VECTOR_BALANCE);
        assertTrue(root.getBoolean("success"));
        JSONObject cardPay = root.getJSONObject("data").getJSONObject("cardPay");
        assertEquals("20241234", cardPay.getString("idserial"));
        assertEquals("123.45", cardPay.getString("cardbal"));
    }

    @Test
    public void decrypt_billVector() throws Exception {
        JSONObject root = Crypto.decrypt(VECTOR_BILL);
        assertTrue(root.getBoolean("success"));
        JSONObject row = root.getJSONObject("resultData").getJSONArray("billList")
                .getJSONObject(0);
        assertEquals("2026-11", row.getString("txdate"));
        assertEquals(5180, row.getInt("txamt"));
    }

    @Test
    public void roundTrip_withChinese() throws Exception {
        String plain = "{\"message\":\"会话失效，请刷新页面！\",\"success\":false}";
        String datajson = Crypto.encryptToDatajson("Zx81mNq0Pl34RtYb", plain);
        JSONObject root = Crypto.decrypt(datajson);
        assertFalse(root.getBoolean("success"));
        assertEquals("会话失效，请刷新页面！", root.getString("message"));
    }

    @Test
    public void decrypt_rejectsShortInput() {
        try {
            Crypto.decrypt("too-short");
            fail("应当抛出 JSONException");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("长度"));
        }
    }
}
