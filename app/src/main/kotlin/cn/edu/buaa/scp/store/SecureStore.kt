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
 * 会话与登录凭据的持久化。
 *
 * - `CASTGC` / `JSESSIONID` / 校园网 Cookie 等同登录凭据，用 AndroidKeyStore 的
 *   AES/GCM 加密后写 prefs；设备密钥库不可用时退回明文（仅会话，且会告警）。
 * - 统一认证密码、校园网密码只在用户显式开启「记住密码」后保存，同样加密；
 *   密钥库不可用时**不会**退回明文，直接不保存。
 * - 学号 / 校园网账号默认记住，属于可公开的标识，明文存储。
 * - 「自动登录」必须同时开启「记住密码」，关闭「记住密码」会立即清除对应密码。
 */
class SecureStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- 校园卡（统一身份认证）会话 ----------------

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

    // ---------------- 账号（默认记住） ----------------

    fun rememberedStudentId(): String = prefs.getString(KEY_STUDENT, "").orEmpty()

    fun setRememberedStudentId(studentId: String?) {
        prefs.edit().putString(KEY_STUDENT, studentId.orEmpty()).apply()
    }

    fun rememberedZfwUsername(): String = prefs.getString(KEY_ZFW_USERNAME, "").orEmpty()

    fun setRememberedZfwUsername(username: String?) {
        prefs.edit().putString(KEY_ZFW_USERNAME, username.orEmpty()).apply()
    }

    // ---------------- 统一身份认证：记住密码 / 自动登录 ----------------

    fun casRememberPassword(): Boolean = prefs.getBoolean(KEY_CAS_REMEMBER, false)

    /** 关闭「记住密码」会同时清除已保存密码并关闭「自动登录」。 */
    fun setCasRememberPassword(value: Boolean) {
        prefs.edit().putBoolean(KEY_CAS_REMEMBER, value).apply()
        if (!value) {
            clearSecret(KEY_CAS_PASSWORD)
            setCasAutoLogin(false)
        }
    }

    fun casAutoLogin(): Boolean = prefs.getBoolean(KEY_CAS_AUTO, false)

    /** 「自动登录」依赖「记住密码」，未开启记住密码时强制为关闭。 */
    fun setCasAutoLogin(value: Boolean) {
        prefs.edit().putBoolean(KEY_CAS_AUTO, value && casRememberPassword()).apply()
    }

    fun saveCasPassword(password: String?) = saveSecret(KEY_CAS_PASSWORD, password)

    fun loadCasPassword(): String? =
        if (casRememberPassword()) loadSecret(KEY_CAS_PASSWORD) else null

    fun clearCasPassword() = clearSecret(KEY_CAS_PASSWORD)

    // ---------------- 校园网：记住密码 / 自动登录 ----------------

    fun zfwRememberPassword(): Boolean = prefs.getBoolean(KEY_ZFW_REMEMBER, false)

    fun setZfwRememberPassword(value: Boolean) {
        prefs.edit().putBoolean(KEY_ZFW_REMEMBER, value).apply()
        if (!value) {
            clearSecret(KEY_ZFW_PASSWORD)
            setZfwAutoLogin(false)
        }
    }

    fun zfwAutoLogin(): Boolean = prefs.getBoolean(KEY_ZFW_AUTO, false)

    fun setZfwAutoLogin(value: Boolean) {
        prefs.edit().putBoolean(KEY_ZFW_AUTO, value && zfwRememberPassword()).apply()
    }

    fun saveZfwPassword(password: String?) = saveSecret(KEY_ZFW_PASSWORD, password)

    fun loadZfwPassword(): String? =
        if (zfwRememberPassword()) loadSecret(KEY_ZFW_PASSWORD) else null

    fun clearZfwPassword() = clearSecret(KEY_ZFW_PASSWORD)

    // ---------------- 加密存储 ----------------

    /** 只接受 AndroidKeyStore 密文；解不开就丢弃，绝不回退明文保存密码。 */
    private fun saveSecret(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            clearSecret(key)
            return
        }
        try {
            prefs.edit().putString(key, GCM_PREFIX + encrypt(value)).apply()
        } catch (e: Exception) {
            LogBus.warn("密钥库不可用（${e.javaClass.simpleName}），未保存密码")
            prefs.edit().remove(key).apply()
        }
    }

    private fun loadSecret(key: String): String? {
        val blob = prefs.getString(key, null)
        if (blob.isNullOrEmpty()) return null
        if (!blob.startsWith(GCM_PREFIX)) {
            LogBus.warn("本地密码格式未知，已丢弃")
            prefs.edit().remove(key).apply()
            return null
        }
        return try {
            decrypt(blob.removePrefix(GCM_PREFIX)).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LogBus.warn("读取本地密码失败：${e.javaClass.simpleName}，已丢弃")
            prefs.edit().remove(key).apply()
            null
        }
    }

    private fun clearSecret(key: String) {
        prefs.edit().remove(key).apply()
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
        const val KEY_ZFW_USERNAME = "remembered_zfw_username"
        const val KEY_CAS_PASSWORD = "cas_password_blob"
        const val KEY_ZFW_PASSWORD = "zfw_password_blob"
        const val KEY_CAS_REMEMBER = "cas_remember_password"
        const val KEY_CAS_AUTO = "cas_auto_login"
        const val KEY_ZFW_REMEMBER = "zfw_remember_password"
        const val KEY_ZFW_AUTO = "zfw_auto_login"
        const val KEY_ALIAS = "scp.session.key.v1"
        const val GCM_PREFIX = "gcm:"
        const val PLAIN_PREFIX = "plain:"
    }
}
