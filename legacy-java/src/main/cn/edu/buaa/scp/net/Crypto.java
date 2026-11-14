package cn.edu.buaa.scp.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 对应前端 {@code handleKey(k, false)} + AES-128-ECB/PKCS7。
 *
 * <p>响应体形态：{@code {"datajson":"<前16字符为密钥素材><Base64 密文>"}}。
 * 用 {@link java.util.Base64}（Android 8.0+ 提供），不依赖 android.util。
 */
public final class Crypto {

    private Crypto() {
    }

    /** 前端 handleKey：先整体反转，再（后 6 位 + 前 6 位）。 */
    public static String handleKey(String keyMaterial16) {
        if (keyMaterial16 == null || keyMaterial16.length() != 16) {
            throw new IllegalArgumentException("密钥素材必须是 16 字符");
        }
        String reversed = new StringBuilder(keyMaterial16).reverse().toString();
        return reversed.substring(6) + reversed.substring(0, 6);
    }

    /** 解密 datajson 并解析为 JSON 对象。 */
    public static JSONObject decrypt(String datajson) throws JSONException {
        if (datajson == null || datajson.length() <= 16) {
            throw new JSONException("datajson 长度异常");
        }
        try {
            byte[] plain = decryptBytes(datajson.substring(0, 16),
                    decodeBase64(datajson.substring(16)));
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw e;
        } catch (Exception e) {
            throw new JSONException("解密失败: " + e.getClass().getSimpleName());
        }
    }

    public static byte[] decryptBytes(String keyMaterial16, byte[] cipherBytes) throws Exception {
        String key = handleKey(keyMaterial16);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), "AES"));
        return cipher.doFinal(cipherBytes);
    }

    /** 仅供单元测试使用：按同一算法构造 datajson。 */
    public static String encryptToDatajson(String keyMaterial16, String plainJson) throws Exception {
        String key = handleKey(keyMaterial16);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(
                key.getBytes(StandardCharsets.UTF_8), "AES"));
        byte[] encrypted = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
        return keyMaterial16 + Base64.getEncoder().encodeToString(encrypted);
    }

    static byte[] decodeBase64(String text) {
        try {
            return Base64.getMimeDecoder().decode(text.trim());
        } catch (IllegalArgumentException first) {
            try {
                return Base64.getUrlDecoder().decode(text.trim());
            } catch (IllegalArgumentException second) {
                return Base64.getDecoder().decode(text.trim());
            }
        }
    }
}
