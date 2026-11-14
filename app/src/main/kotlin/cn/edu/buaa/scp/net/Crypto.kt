package cn.edu.buaa.scp.net

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 对应前端 `handleKey(k, false)` + AES-128-ECB/PKCS7。
 *
 * 响应体形态：`{"datajson":"<前16字符为密钥素材><Base64 密文>"}`。
 * 使用 [java.util.Base64]（Android 8.0+ 提供），不依赖 android.util。
 */
object Crypto {

    /** 前端 handleKey：先整体反转，再（后 6 位 + 前 6 位）。 */
    fun handleKey(keyMaterial16: String): String {
        require(keyMaterial16.length == 16) { "密钥素材必须是 16 字符" }
        val reversed = keyMaterial16.reversed()
        return reversed.substring(6) + reversed.substring(0, 6)
    }

    /** 解密 datajson 并解析为 JSON 对象。 */
    @Throws(JSONException::class)
    fun decrypt(datajson: String?): JSONObject {
        if (datajson == null || datajson.length <= 16) {
            throw JSONException("datajson 长度异常")
        }
        return try {
            val plain = decryptBytes(datajson.substring(0, 16), decodeBase64(datajson.substring(16)))
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw e
        } catch (e: Exception) {
            throw JSONException("解密失败: ${e.javaClass.simpleName}")
        }
    }

    fun decryptBytes(keyMaterial16: String, cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(handleKey(keyMaterial16).toByteArray(Charsets.UTF_8), "AES"),
        )
        return cipher.doFinal(cipherBytes)
    }

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 按前端同一算法构造 datajson（请求与响应共用）。 */
    fun encryptToDatajson(keyMaterial16: String, plainJson: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(handleKey(keyMaterial16).toByteArray(Charsets.UTF_8), "AES"),
        )
        val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        return keyMaterial16 + Base64.getEncoder().encodeToString(encrypted)
    }

    /** 官方 `genKey`：16 个 [A-Za-z0-9] 随机字符。 */
    fun randomKey16(): String = buildString {
        repeat(16) { append(ALPHABET[kotlin.random.Random.nextInt(ALPHABET.length)]) }
    }

    /** 请求信封：`{"datajson":"<前缀><Base64密文>"}`。 */
    fun wrapRequest(payload: JSONObject): String =
        encryptToDatajson(randomKey16(), payload.toString())

    internal fun decodeBase64(text: String): ByteArray {
        val trimmed = text.trim()
        return runCatching { Base64.getMimeDecoder().decode(trimmed) }
            .recoverCatching { Base64.getUrlDecoder().decode(trimmed) }
            .getOrElse { Base64.getDecoder().decode(trimmed) }
    }
}
