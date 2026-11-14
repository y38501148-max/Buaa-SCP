package cn.edu.buaa.scp

import android.app.Application
import android.content.Context
import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.CardApi
import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.store.SecureStore
import cn.edu.buaa.scp.store.SessionData
import cn.edu.buaa.scp.util.DebugDump
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 应用级单例：网络栈、会话仓库。
 *
 * 会话管理策略（依据实测链路）：
 * 1. 启动时先用本地 JSESSIONID 试一次业务接口；
 * 2. 失败且存有 CASTGC 时，走 CAS 免密续期，换新 JSESSIONID；
 * 3. 两者都失效才要求重新输入密码。
 */
class ScpApp : Application() {

    val jar: CookieJar by lazy { CookieJar() }
    val http: HttpEngine by lazy { HttpEngine(jar) }
    val cas: CasAuth by lazy { CasAuth(http) }
    val api: CardApi by lazy { CardApi(http) }
    val store: SecureStore by lazy { SecureStore(this) }

    private val sessionMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    var session: SessionData? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) {
            DebugDump.dir = java.io.File(filesDir, "debug")
        }
        val saved = store.loadSession()
        if (saved != null) {
            session = saved
            applyCookies(saved)
            LogBus.step(
                "已加载本地会话（学号 ${saved.studentId}，CASTGC " +
                        (if (saved.hasCastgc) "有" else "无") + "，JSESSIONID " +
                        (if (saved.hasJsessionid) "有" else "无") + "）",
            )
        }
    }

    fun hasSession(): Boolean = session?.hasAnything == true

    fun openid(): String = session?.openid.orEmpty()

    fun studentId(): String = session?.studentId.orEmpty()

    /**
     * 用现有凭据验证业务会话是否可用。
     *
     * 判定标准：`openCardPay` 返回 success=true 且带 data；未绑卡（缺 cardPay）
     * 仍视为已认证，只是没有余额信息。
     */
    suspend fun validateSession(): Boolean = try {
        val root = api.openCardPay()
        val data = root.optJSONObject("data")
        if (data == null) {
            LogBus.warn("会话校验：openCardPay 返回缺少 data 字段")
            false
        } else {
            val cardPay = data.optJSONObject("cardPay")
            if (cardPay == null) {
                LogBus.warn("会话校验：未返回 cardPay（可能未绑卡），但仍视为已认证")
            } else {
                LogBus.step("会话校验通过，余额 ${cardPay.optString("cardbal", "?")} 元")
            }
            true
        }
    } catch (e: ApiException) {
        LogBus.warn("会话校验未通过：${e.message}")
        false
    } catch (e: Exception) {
        LogBus.warn("会话校验异常：${e.javaClass.simpleName} ${e.message}")
        false
    }

    /**
     * 免密续期：先试本地 JSESSIONID，再用 CASTGC 换新票据 → 新 JSESSIONID，并校验业务会话。
     */
    suspend fun recoverSession(): Boolean = sessionMutex.withLock {
        val data = session ?: return@withLock false
        if (data.hasJsessionid) {
            LogBus.step("尝试复用本地 JSESSIONID")
            jar.put(CasAuth.CARD_HOST, "JSESSIONID", data.jsessionid)
            if (validateSession()) return@withLock true
        }
        if (!data.hasCastgc) {
            LogBus.warn("本地无 CASTGC，无法免密续期")
            return@withLock false
        }
        try {
            when (val result = cas.restore()) {
                is CasAuth.Result.Failure -> {
                    LogBus.warn("免密续期失败：${result.message}")
                    false
                }

                is CasAuth.Result.Success -> {
                    if (!validateSession()) {
                        LogBus.warn("CASTGC 换取会话成功，但业务会话不可用")
                        false
                    } else {
                        rememberSession(data.studentId, result.openid)
                        true
                    }
                }
            }
        } catch (e: Exception) {
            LogBus.warn("免密续期异常：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 登录/续期成功后：抓取最新 Cookie 落盘。 */
    fun rememberSession(studentId: String?, openid: String?) {
        synchronized(stateLock) {
            val castgc = jar.valueOf(CasAuth.SSO_HOST, "CASTGC").orEmpty()
            val jsessionid = jar.valueOf(CasAuth.CARD_HOST, "JSESSIONID").orEmpty()
            val previous = session
            val data = SessionData(
                studentId = studentId.orEmpty(),
                openid = openid.orEmpty(),
                castgc = castgc,
                jsessionid = jsessionid,
                savedAt = System.currentTimeMillis(),
                lastSyncAt = previous?.lastSyncAt ?: 0L,
            )
            session = data
            store.saveSession(data)
            if (data.studentId.isNotEmpty()) {
                store.setRememberedStudentId(data.studentId)
            }
        }
    }

    /** 记录一次成功的数据刷新时间。 */
    fun markSynced() {
        synchronized(stateLock) {
            val data = session ?: return
            val updated = data.copy(lastSyncAt = System.currentTimeMillis())
            session = updated
            store.saveSession(updated)
        }
    }

    fun logout() {
        synchronized(stateLock) {
            session = null
            jar.clear()
            store.clearSession()
            LogBus.step("已退出登录，本地会话已清除")
        }
    }

    /**
     * 业务调用失败且会话过期时，自动续期并重试一次。
     */
    suspend fun <T> withRecovery(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.authExpired && recoverSession()) {
            LogBus.step("会话已自动续期，重试请求")
            block()
        } else {
            throw e
        }
    }

    private fun applyCookies(data: SessionData) {
        if (data.hasCastgc) jar.put(CasAuth.SSO_HOST, "CASTGC", data.castgc)
        if (data.hasJsessionid) jar.put(CasAuth.CARD_HOST, "JSESSIONID", data.jsessionid)
    }

    companion object {
        @Volatile
        private var instance: ScpApp? = null

        fun get(context: Context): ScpApp = context.applicationContext as ScpApp

        fun get(): ScpApp = checkNotNull(instance) { "ScpApp 尚未初始化" }
    }
}
