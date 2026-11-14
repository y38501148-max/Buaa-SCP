package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.ZfwAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** 校园网原生登录的 HTML 解析与 RSA 加密兼容性测试。 */
class ZfwAuthTest {

    @Test
    fun parsesCsrfAndPublicKey() {
        val pem = publicPem()
        val html = """
            <html><body>
            <form id="login-form">
              <input type="hidden" name="_csrf-8800" value="csrf-token-123">
              <input type="hidden" id="public" value="$pem">
              <input name="LoginForm[username]">
            </form>
            </body></html>
        """.trimIndent()
        val page = ZfwAuth.parseLoginPageHtml(html)
        assertNotNull(page)
        assertEquals("csrf-token-123", page!!.csrfToken)
        assertTrue(page.publicKeyPem.contains("BEGIN PUBLIC KEY"))
    }

    @Test
    fun parsesCsrfFromMetaFallback() {
        val pem = publicPem()
        val html = """
            <html><head><meta name="csrf-token" content="meta-token"></head>
            <body><input type="hidden" id="public" value="$pem"></body></html>
        """.trimIndent()
        assertEquals("meta-token", ZfwAuth.parseLoginPageHtml(html)?.csrfToken)
    }

    @Test
    fun rejectsPageWithoutKeyOrToken() {
        assertNull(ZfwAuth.parseLoginPageHtml("<html><body>维护中</body></html>"))
    }

    /** Android Cipher 的 PKCS#1 v1.5 加密结果必须能被私钥解开，且与 JSEncrypt 语义一致。 */
    @Test
    fun rsaEncryptsPasswordForJsEncrypt() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val pem = buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.getEncoder().encodeToString(pair.public.encoded))
            append("\n-----END PUBLIC KEY-----")
        }
        val encrypted = ZfwAuth.encryptPassword(pem, "campus-password")
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, pair.private)
        val plain = cipher.doFinal(Base64.getDecoder().decode(encrypted))
        assertEquals("campus-password", String(plain, Charsets.UTF_8))
    }

    private fun publicPem(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        return buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.getEncoder().encodeToString(pair.public.encoded))
            append("\n-----END PUBLIC KEY-----")
        }
    }
}
