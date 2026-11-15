package cn.edu.buaa.scp

import android.app.Application
import android.content.Context
import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.CardApi
import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.CookieJar
import cn.edu.buaa.scp.net.FinanceApi
import cn.edu.buaa.scp.net.HttpEngine
import cn.edu.buaa.scp.net.PortalApi
import cn.edu.buaa.scp.net.ZfwApi
import cn.edu.buaa.scp.net.ZfwAuth
import cn.edu.buaa.scp.store.SecureStore
import cn.edu.buaa.scp.store.SessionData
import cn.edu.buaa.scp.store.ZfwSession
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
    val portalApi: PortalApi by lazy { PortalApi(http) }

    /** 财务系统（chaxun.icw.buaa.edu.cn）收入查询，复用统一认证 Cookie。 */
    val financeApi: FinanceApi by lazy { FinanceApi(http) }
    val store: SecureStore by lazy { SecureStore(this) }

    /** 校园网会话独立存放，避免与统一认证 / 校园卡 Cookie 混在一起。 */
    val zfwJar: CookieJar by lazy { CookieJar() }
    val zfwHttp: HttpEngine by lazy { HttpEngine(zfwJar) }
    val zfwApi: ZfwApi by lazy { ZfwApi(zfwHttp) }
    val zfwAuth: ZfwAuth by lazy { ZfwAuth(zfwHttp) }

    @Volatile
    var zfwSession: ZfwSession? = null
        private set

    private val sessionMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    var session: SessionData? = null
        private set

    /** 仅 debug 构建使用：不连网，用本地示例数据渲染界面，便于截图和布局检查。 */
    @Volatile
    var demoMode: Boolean = false
        private set

    fun enterDemoMode() {
        demoMode = true
        val now = System.currentTimeMillis()
        session = SessionData(
            studentId = "20000000",
            openid = "DEMO0000000000000000",
            castgc = "demo-castgc",
            jsessionid = "demo-jsessionid",
            savedAt = now - 2 * 60 * 60 * 1000L,
            lastSyncAt = now - 5 * 60 * 1000L,
        )
    }

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
        val zfw = store.loadZfwSession()
        if (zfw != null) {
            zfwSession = zfw
            zfwApi.applyCookieHeader(zfw.cookie)
            LogBus.step("已加载校园网会话（${zfw.username.ifBlank { "账号未知" }}）")
        }
    }

    fun hasSession(): Boolean = demoMode || session?.hasAnything == true

    @Volatile
    private var homeRequested = false

    @Volatile
    private var homeRefreshRequested = false

    /** 充值 / 校园网登录完成后请求宿主页面回到首页。 */
    fun requestHome() {
        homeRequested = true
        homeRefreshRequested = true
    }

    fun consumeHomeRequest(): Boolean {
        val requested = homeRequested
        homeRequested = false
        return requested
    }

    /** 首页数据需要重新拉取（校园卡状态或校园网会话变化）。 */
    fun requestHomeRefresh() {
        homeRefreshRequested = true
    }

    fun consumeHomeRefresh(): Boolean {
        val requested = homeRefreshRequested
        homeRefreshRequested = false
        return requested
    }

    fun openid(): String = session?.openid.orEmpty()

    fun studentId(): String = session?.studentId.orEmpty()

    /**
     * 用现有凭据验证业务会话是否可用。
     *
     * 不能用 `openCardPay` 作为唯一判据：它依赖“缴费时段”，夜间/维护期会返回
     * `success=false, message=不在缴费期间`，但会话其实是有效的。
     * 因此先用不依赖缴费时段的卡务读接口 `openCardReportLoss` 验证；
     * 只有明确 401/403/会话失效才判定登录失败。
     */
    suspend fun validateSession(): Boolean {
        try {
            api.reportLossInfo()
            LogBus.step("会话校验通过（卡务读接口）")
            return true
        } catch (e: ApiException) {
            if (e.authExpired) {
                LogBus.warn("会话校验未通过：${e.message}")
                return false
            }
            LogBus.warn("卡务读接口业务失败（${e.message}），尝试充值初始化接口")
        } catch (e: Exception) {
            LogBus.warn("会话校验异常：${e.javaClass.simpleName} ${e.message}")
        }

        return try {
            val root = api.openCardPay()
            val data = root.optJSONObject("data")
            if (data != null) {
                val cardPay = data.optJSONObject("cardPay")
                if (cardPay == null) {
                    LogBus.warn("会话校验：未返回 cardPay（可能未绑卡），但仍视为已认证")
                } else {
                    LogBus.step("会话校验通过，余额 ${cardPay.optString("cardbal", "?")} 元")
                }
                true
            } else {
                LogBus.step("充值初始化返回业务提示，仍视为已认证")
                true
            }
        } catch (e: ApiException) {
            // “不在缴费期间”等业务失败说明请求已到达业务层，Cookie 有效。
            if (e.authExpired) {
                LogBus.warn("会话校验未通过：${e.message}")
                false
            } else {
                LogBus.step("充值初始化业务失败（${e.message}），会话仍有效")
                true
            }
        } catch (e: Exception) {
            LogBus.warn("会话校验异常：${e.javaClass.simpleName} ${e.message}")
            false
        }
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
            if (previous?.studentId != data.studentId || previous?.openid != data.openid) {
                // 换了账号：清掉财务系统的内存 token，避免读到上一个账号的收入记录。
                financeApi.reset()
            }
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

    // ---------------- 校园网（zfw）会话 ----------------

    fun hasZfwSession(): Boolean = demoMode || zfwSession?.hasCookie == true

    /**
     * 保存 WebView 登录后取得的校园网 Cookie。
     *
     * 只保存 Cookie，不保存校园网密码；Cookie 与卡片会话一样走 AndroidKeyStore 加密。
     */
    fun saveZfwSession(username: String, name: String, cookie: String) {
        if (cookie.isBlank()) return
        synchronized(stateLock) {
            val previous = zfwSession
            val data = ZfwSession(
                username = username.ifBlank { previous?.username.orEmpty() },
                name = name.ifBlank { previous?.name.orEmpty() },
                cookie = cookie,
                savedAt = System.currentTimeMillis(),
                lastSyncAt = previous?.lastSyncAt ?: 0L,
            )
            zfwSession = data
            store.saveZfwSession(data)
            zfwApi.applyCookieHeader(cookie)
        }
    }

    /** 服务端 Cookie 可能续期，用它覆盖本地保存值。 */
    fun refreshZfwCookie(cookie: String) {
        val current = zfwSession ?: return
        if (cookie.isBlank() || cookie == current.cookie) return
        saveZfwSession(current.username, current.name, cookie)
    }

    /** 用首页解析出的账号/姓名补齐本地资料（不改 Cookie 时间）。 */
    fun updateZfwProfile(username: String, name: String) {
        val current = zfwSession ?: return
        if (username.isBlank() && name.isBlank()) return
        if (current.username == username && current.name == name) return
        val updated = current.copy(
            username = username.ifBlank { current.username },
            name = name.ifBlank { current.name },
        )
        zfwSession = updated
        store.saveZfwSession(updated)
    }

    fun markZfwSynced() {
        synchronized(stateLock) {
            val data = zfwSession ?: return
            val updated = data.copy(lastSyncAt = System.currentTimeMillis())
            zfwSession = updated
            store.saveZfwSession(updated)
        }
    }

    fun clearZfwSession() {
        synchronized(stateLock) {
            zfwSession = null
            zfwJar.clear()
            store.clearZfwSession()
            LogBus.step("已清除校园网会话")
        }
    }

    fun logout() {
        synchronized(stateLock) {
            demoMode = false
            session = null
            zfwSession = null
            financeApi.reset()
            jar.clear()
            zfwJar.clear()
            store.clearSession()
            store.clearZfwSession()
            // 退出登录等于「忘记本机凭据」：密码与自动登录一并清除，学号仍保留。
            store.setCasRememberPassword(false)
            store.setZfwRememberPassword(false)
            LogBus.step("已退出登录，本地会话与保存的密码已清除")
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
