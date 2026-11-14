package cn.edu.buaa.scp.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import cn.edu.buaa.scp.util.LogBus;

/**
 * 会话持久化：CASTGC 等同登录凭据，因此用 AndroidKeyStore 的 AES/GCM 加密后写 prefs。
 *
 * <p>设备密钥库不可用时退回明文存储，仅在调试版本可接受，日志里会明确告警。
 * 密码从不出现在本类中。
 */
public final class SecureStore {

    private static final String PREFS = "scp_session";
    private static final String KEY_SESSION = "session_blob";
    private static final String KEY_STUDENT = "remembered_student_id";
    private static final String KEY_ALIAS = "scp.session.key.v1";
    private static final String GCM_PREFIX = "gcm:";
    private static final String PLAIN_PREFIX = "plain:";

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSession(SessionData data) {
        if (data == null) {
            return;
        }
        String json = data.toJson().toString();
        try {
            prefs.edit().putString(KEY_SESSION, GCM_PREFIX + encrypt(json)).apply();
        } catch (Exception e) {
            LogBus.warn("密钥库不可用（" + e.getClass().getSimpleName() + "），会话以明文保存");
            prefs.edit().putString(KEY_SESSION, PLAIN_PREFIX + json).apply();
        }
    }

    public SessionData loadSession() {
        String blob = prefs.getString(KEY_SESSION, null);
        if (blob == null || blob.isEmpty()) {
            return null;
        }
        try {
            String json;
            if (blob.startsWith(GCM_PREFIX)) {
                json = decrypt(blob.substring(GCM_PREFIX.length()));
            } else if (blob.startsWith(PLAIN_PREFIX)) {
                json = blob.substring(PLAIN_PREFIX.length());
            } else {
                json = blob;
            }
            SessionData data = SessionData.fromJson(new JSONObject(json));
            return data.hasAnything() ? data : null;
        } catch (Exception e) {
            LogBus.warn("读取本地会话失败：" + e.getClass().getSimpleName() + "，已丢弃");
            prefs.edit().remove(KEY_SESSION).apply();
            return null;
        }
    }

    public void clearSession() {
        prefs.edit().remove(KEY_SESSION).apply();
    }

    public String getRememberedStudentId() {
        return prefs.getString(KEY_STUDENT, "");
    }

    public void setRememberedStudentId(String studentId) {
        prefs.edit().putString(KEY_STUDENT, studentId == null ? "" : studentId).apply();
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] all = Base64.decode(encoded, Base64.NO_WRAP);
        byte[] iv = new byte[12];
        byte[] cipherText = new byte[all.length - 12];
        System.arraycopy(all, 0, iv, 0, 12);
        System.arraycopy(all, 12, cipherText, 0, cipherText.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
