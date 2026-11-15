package cn.edu.buaa.scp

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.edu.buaa.scp.store.SecureStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机 / 模拟器上的凭据存储测试：验证 AndroidKeyStore 加密往返、开关联动，
 * 以及旧版明文密码不会被接受。
 *
 * 注意：测试会清空 app 的 `scp_session` prefs，只应在模拟器或专用测试机上运行，
 * 不要在保存了真实登录态/密码的日常设备上执行 connectedAndroidTest。
 */
@RunWith(AndroidJUnit4::class)
class SecureStoreTest {

    private lateinit var context: Context
    private lateinit var store: SecureStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = SecureStore(context)
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() = context.getSharedPreferences("scp_session", Context.MODE_PRIVATE)

    /** SharedPreferences.apply() 是异步写盘；用一个 commit 把队列里的写入落盘。 */
    private fun flushPrefs() {
        prefs().edit().putBoolean("__flush__", true).commit()
        prefs().edit().remove("__flush__").commit()
    }

    private fun prefsFileText(): String =
        File(context.applicationInfo.dataDir, "shared_prefs/scp_session.xml").readText()

    @Test
    fun casPasswordRoundTripsThroughKeystore() {
        store.setCasRememberPassword(true)
        store.saveCasPassword("p@ss 中文 & 空格")

        assertEquals("p@ss 中文 & 空格", store.loadCasPassword())
        val raw = prefs().getString("cas_password_blob", "").orEmpty()
        assertTrue(raw.startsWith("gcm:"))
        assertFalse(raw.contains("p@ss"))
        assertFalse(raw.contains("中文"))

        // 落盘内容同样只能是密文。
        flushPrefs()
        val xml = prefsFileText()
        assertTrue(xml.contains("cas_password_blob"))
        assertFalse(xml.contains("p@ss"))
        assertFalse(xml.contains("中文"))
    }

    @Test
    fun zfwPasswordRoundTripsThroughKeystore() {
        store.setZfwRememberPassword(true)
        store.saveZfwPassword("zfw-secret-123")
        assertEquals("zfw-secret-123", store.loadZfwPassword())
        assertTrue(prefs().getString("zfw_password_blob", "").orEmpty().startsWith("gcm:"))
    }

    @Test
    fun disablingRememberPasswordClearsPasswordAndAutoLogin() {
        store.setCasRememberPassword(true)
        store.saveCasPassword("secret")
        store.setCasAutoLogin(true)
        assertTrue(store.casAutoLogin())

        store.setCasRememberPassword(false)

        assertNull(store.loadCasPassword())
        assertFalse(store.casAutoLogin())
        assertNull(prefs().getString("cas_password_blob", null))
    }

    @Test
    fun autoLoginCannotBeEnabledWithoutRememberPassword() {
        store.setCasRememberPassword(false)
        store.setCasAutoLogin(true)
        assertFalse(store.casAutoLogin())

        store.setZfwRememberPassword(false)
        store.setZfwAutoLogin(true)
        assertFalse(store.zfwAutoLogin())
    }

    @Test
    fun legacyPlaintextPasswordIsRejected() {
        store.setCasRememberPassword(true)
        prefs().edit().putString("cas_password_blob", "plain:secret").commit()

        assertNull(store.loadCasPassword())
        assertNull(prefs().getString("cas_password_blob", null))
    }
}
