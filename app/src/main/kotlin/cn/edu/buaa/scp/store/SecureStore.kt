package cn.edu.buaa.scp.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cn.edu.buaa.scp.util.LogBus
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 会话持久化：CASTGC 等同登录凭据，因此用 AndroidKeyStore 的 AES/GCM 加密后写 prefs。
 *
 * 设备密钥库不可用时退回明文存储，仅在调试版本可接受，日志里会明确告警。
 * 密码从不出现在本类中。
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveSession(data: SessionData?) {
        if (data == null) return
        val json = data.toJson().toString()
        try {
            prefs.edit().putString(KEY_SESSION, GCM_PREFIX + encrypt(json)).apply()
        } catch (e: Exception) {
            LogBus.warn("密钥库不可用（${e.javaClass.simpleName}），会话以明文保存")
            prefs.edit().putString(KEY_SESSION, PLAIN_PREFIX + json).apply()
        }
    }

    fun loadSession(): SessionData? {
        val blob = prefs.getString(KEY_SESSION, null)
        if (blob.isNullOrEmpty()) return null
        return try {
            val json = when {
                blob.startsWith(GCM_PREFIX) -> decrypt(blob.removePrefix(GCM_PREFIX))
                blob.startsWith(PLAIN_PREFIX) -> blob.removePrefix(PLAIN_PREFIX)
                else -> blob
            }
            SessionData.fromJson(JSONObject(json)).takeIf { it.hasAnything }
        } catch (e: Exception) {
            LogBus.warn("读取本地会话失败：${e.javaClass.simpleName}，已丢弃")
            prefs.edit().remove(KEY_SESSION).apply()
            null
        }
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    // ---------------- 校园网（zfw）会话 ----------------

    fun saveZfwSession(data: ZfwSession?) {
        if (data == null) {
            prefs.edit().remove(KEY_ZFW_SESSION).apply()
            return
        }
        val json = data.toJson().toString()
        try {
            prefs.edit().putString(KEY_ZFW_SESSION, GCM_PREFIX + encrypt(json)).apply()
        } catch (e: Exception) {
            LogBus.warn("密钥库不可用（${e.javaClass.simpleName}），校园网会话以明文保存")
            prefs.edit().putString(KEY_ZFW_SESSION, PLAIN_PREFIX + json).apply()
        }
    }

    fun loadZfwSession(): ZfwSession? {
        val blob = prefs.getString(KEY_ZFW_SESSION, null)
        if (blob.isNullOrEmpty()) return null
        return try {
            val json = when {
                blob.startsWith(GCM_PREFIX) -> decrypt(blob.removePrefix(GCM_PREFIX))
                blob.startsWith(PLAIN_PREFIX) -> blob.removePrefix(PLAIN_PREFIX)
                else -> blob
            }
            ZfwSession.fromJson(JSONObject(json))
        } catch (e: Exception) {
            LogBus.warn("读取校园网会话失败：${e.javaClass.simpleName}，已丢弃")
            prefs.edit().remove(KEY_ZFW_SESSION).apply()
            null
        }
    }

    fun clearZfwSession() {
        prefs.edit().remove(KEY_ZFW_SESSION).apply()
    }

    fun rememberedStudentId(): String = prefs.getString(KEY_STUDENT, "").orEmpty()

    fun setRememberedStudentId(studentId: String?) {
        prefs.edit().putString(KEY_STUDENT, studentId.orEmpty()).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val cipherText = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "scp_session"
        const val KEY_SESSION = "session_blob"
        const val KEY_ZFW_SESSION = "zfw_session_blob"
        const val KEY_STUDENT = "remembered_student_id"
        const val KEY_ALIAS = "scp.session.key.v1"
        const val GCM_PREFIX = "gcm:"
        const val PLAIN_PREFIX = "plain:"
    }
}
