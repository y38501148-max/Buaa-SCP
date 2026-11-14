package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.model.FinanceIncome
import cn.edu.buaa.scp.model.FinanceIncomes
import cn.edu.buaa.scp.model.FinanceModels
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * 财务查询（chaxun.icw.buaa.edu.cn）个人收入记录。
 *
 * 链路全部走 https、显式逐跳（[HttpEngine] 不自动跟随 3xx）：
 * 1. `sso.buaa.edu.cn/login?service=…` 用现有 CASTGC 换票，落到 icw 财务门户；
 * 2. `icw.buaa.edu.cn/jump.aspx?sysid=wscx` 跳到财务查询系统的 login 入口，
 *    入口查询串里带 `Userid` / `Cypt` / `Stamp`；
 * 3. POST `api-zhcx/login` 换财务系统 token；
 * 4. POST `api-zhcx/getStudentSRCX` 分页读取收入记录（limit=100）。
 *
 * 财务门户与查询系统的 Cookie 落在共享的 [HttpEngine.jar]，统一认证 CASTGC 也由
 * 应用级 CookieJar 持有；本类只额外缓存内存里的 `Userid + token`。
 * token 失效（401 / 被重定向到 SSO 登录页）时清空缓存并用同一套流程重登一次。
 */
class FinanceApi(private val http: HttpEngine) {

    private data class Credential(val userId: String, val token: String)

    private data class Entrance(
        val url: String,
        val userId: String,
        val cypt: String,
        val stamp: String,
    )

    private val mutex = Mutex()

    @Volatile
    private var credential: Credential? = null

    /** 拉取全部收入记录；带内存凭据复用，会话失效时自动重登一次。 */
    suspend fun incomes(): FinanceIncomes = withContext(Dispatchers.IO) {
        val first = credentialOrLogin()
        try {
            readAll(first)
        } catch (e: ApiException) {
            if (!e.authExpired) throw e
            LogBus.step("财务查询：会话失效，重新登录后重试")
            readAll(relogin())
        }
    }

    /** 清空内存凭据；退出登录 / 切换账号时由上层调用（Cookie 由应用级 CookieJar 清理）。 */
    fun reset() {
        credential = null
        LogBus.step("财务查询：已清除内存凭据")
    }

    private suspend fun credentialOrLogin(): Credential = mutex.withLock {
        credential ?: login().also { credential = it }
    }

    private suspend fun relogin(): Credential = mutex.withLock {
        credential = null
        login().also { credential = it }
    }

    /** 统一认证 → icw 门户 → 财务系统登录，取得 userId 与 token。 */
    private fun login(): Credential {
        authenticateCas()
        val entrance = entrance()
        val token = loginFinance(entrance)
        LogBus.step("财务查询：财务系统登录成功")
        return Credential(entrance.userId, token)
    }

    /**
     * 第 1 步：CAS 登录。CASTGC 有效时 CAS 直接签票并回跳 icw；
     * 落到 SSO 登录页（200）说明 CASTGC 已失效。
     */
    private fun authenticateCas() {
        LogBus.step("财务查询：通过统一身份认证进入财务门户")
        var url = "$SSO/login?service=" + Html.urlEncode(SERVICE)
        var hops = 0
        while (true) {
            val response = http.get(url)
            if (response.isRedirect) {
                val location = response.location
                if (location.isNullOrEmpty()) throw ApiException("财务登录跳转缺少地址")
                hops++
                if (hops > MAX_LOGIN_HOPS) throw ApiException("财务登录跳转次数过多")
                // 初始 Location 可能是 http://icw…?ticket=…，必须先改 https 再请求。
                url = normalizeLocation(response.url, location)
                continue
            }
            if (response.status == 200) {
                when (hostOf(url)) {
                    SSO_HOST -> throw ApiException(CAS_EXPIRED_MESSAGE, authExpired = true)
                    ICW_HOST -> return
                    else -> throw ApiException("财务门户登录失败（HTTP ${response.status}）")
                }
            }
            throw ApiException("财务门户登录失败（HTTP ${response.status}）")
        }
    }

    /** 第 2 步：财务门户跳转入口，取 Userid / Cypt / Stamp。 */
    private fun entrance(): Entrance {
        LogBus.step("财务查询：进入财务查询系统")
        val response = http.get("$ICW/jump.aspx?sysid=wscx")
        if (!response.isRedirect) {
            if (response.status == 401 || response.status == 403 || looksLikeCasLogin(response.body)) {
                throw ApiException(CAS_EXPIRED_MESSAGE, authExpired = true)
            }
            throw ApiException("财务系统入口跳转失败（HTTP ${response.status}）")
        }
        val location = response.location
        if (location.isNullOrEmpty()) throw ApiException("财务系统入口跳转缺少地址")
        val host = hostOf(HttpEngine.resolve(response.url, location))
        if (host == SSO_HOST) throw ApiException(CAS_EXPIRED_MESSAGE, authExpired = true)
        if (host != CHAXUN_HOST) throw ApiException("财务系统入口跳转到未知地址")
        val url = normalizeLocation(response.url, location)
        val path = runCatching { URI.create(url).rawPath }.getOrNull().orEmpty()
        if (!path.contains(ENTRANCE_PATH)) throw ApiException("财务系统入口地址异常")
        val userId = Html.queryParam(url, "Userid").orEmpty().trim()
        val cypt = Html.queryParam(url, "Cypt").orEmpty()
        val stamp = Html.queryParam(url, "Stamp").orEmpty().trim()
        if (userId.isEmpty() || cypt.isEmpty() || stamp.isEmpty()) {
            throw ApiException("财务系统入口参数缺失")
        }
        return Entrance(url, userId, cypt, stamp)
    }

    /** 第 3 步：财务系统登录，取 token。不得把 token / Cypt / Userid 写入日志。 */
    private fun loginFinance(entrance: Entrance): String {
        val form = buildString {
            append("username=").append(Html.urlEncode(entrance.userId))
            append("&password=").append(Html.urlEncode(entrance.cypt))
            append("&yzm=yzm")
            append("&stamp=").append(Html.urlEncode(entrance.stamp))
        }
        val response = http.post(
            "$CHAXUN/api-zhcx/login",
            HttpEngine.headers(
                "Content-Type", "application/x-www-form-urlencoded;charset=UTF-8",
                "Referer", entrance.url,
            ),
            form,
        )
        val data = envelopeData(response, "财务系统登录失败")
        val token = data.optString("token").trim()
        if (token.isEmpty()) throw ApiException("财务系统登录失败（未返回令牌）")
        return token
    }

    /** 第 4 步：分页读取，按 lsh 去重，直到凑满 total。 */
    private fun readAll(credential: Credential): FinanceIncomes {
        val collected = LinkedHashMap<String, FinanceIncome>()
        var total = 0
        var page = 1
        while (true) {
            if (page > MAX_PAGES) throw ApiException("财务收入分页过多，请稍后重试")
            val parsed = FinanceModels.parsePage(fetchPage(credential, page))
                ?: throw ApiException("财务收入数据格式异常")
            if (parsed.total > MAX_TOTAL) {
                throw ApiException("财务收入记录过多（${parsed.total} 条），暂不支持全部加载")
            }
            total = parsed.total
            if (parsed.entries.isEmpty() && collected.size < total) {
                // 行数据空了但总数没凑齐：服务端结构变化或数据回滚。
                throw ApiException("财务收入数据不完整，请稍后重试")
            }
            var added = 0
            for (entry in parsed.entries) {
                val existing = collected[entry.id]
                if (existing == null) {
                    collected[entry.id] = entry
                    added++
                } else if (existing != entry) {
                    // 同一流水号内容不一致：分页快照在变化，无法保证数据正确。
                    throw ApiException("财务收入数据重复，请稍后重试")
                }
            }
            if (collected.size >= total) break
            if (added == 0) throw ApiException("财务收入数据未能收敛，请稍后重试")
            page++
        }
        val count = maxOf(total, collected.size)
        LogBus.step("财务查询：已读取 ${collected.size} 条收入记录")
        return FinanceIncomes(collected.values.toList(), count)
    }

    private fun fetchPage(credential: Credential, page: Int): JSONObject {
        val body = JSONObject()
            .put("userId", credential.userId)
            .put("currentPage", page)
            .put("limit", PAGE_SIZE)
            .toString()
        val response = http.post(
            "$CHAXUN/api-zhcx/getStudentSRCX",
            HttpEngine.headers(
                "Content-Type", "application/json;charset=UTF-8",
                "Authorization", "Bearer ${credential.token}",
            ),
            body,
        )
        return envelopeData(response, "财务收入查询失败")
    }

    /**
     * 解析 `{code, data, message}` envelope：code=200 返回 data；
     * 401 / 重定向到登录页 / 返回 HTML → authExpired；其它 code 抛业务错误。
     */
    private fun envelopeData(response: HttpEngine.Response, operation: String): JSONObject {
        if (response.isRedirect || response.status == 401 || response.status == 403) {
            throw ApiException(AUTH_EXPIRED_MESSAGE, authExpired = true)
        }
        if (response.status != 200) throw ApiException("$operation（HTTP ${response.status}）")
        val text = response.body.trim()
        if (!text.startsWith("{")) throw ApiException(AUTH_EXPIRED_MESSAGE, authExpired = true)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw ApiException("$operation（响应内容无法解析）")
        val code = root.opt("code").let { value ->
            when (value) {
                null, JSONObject.NULL -> null
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
        }
        if (code == 200) {
            return root.optJSONObject("data") ?: throw ApiException("$operation（响应缺少数据）")
        }
        if (code == 401) throw ApiException(AUTH_EXPIRED_MESSAGE, authExpired = true)
        val message = root.optString("message")
            .ifBlank { root.optString("msg") }
            .ifBlank { root.optString("error") }
            .ifBlank { if (code != null) "错误码 $code" else "未知错误" }
        throw ApiException("$operation（$message）")
    }

    private fun looksLikeCasLogin(body: String): Boolean {
        if (body.isEmpty()) return false
        return body.contains("统一身份认证") || body.contains("sso.buaa.edu.cn")
    }

    private fun hostOf(url: String?): String? = runCatching {
        URI.create(url.orEmpty()).host?.lowercase(Locale.US)
    }.getOrNull()

    companion object {

        const val SSO = "https://sso.buaa.edu.cn"
        const val ICW = "https://icw.buaa.edu.cn"
        const val CHAXUN = "https://chaxun.icw.buaa.edu.cn"

        private const val SSO_HOST = "sso.buaa.edu.cn"
        private const val ICW_HOST = "icw.buaa.edu.cn"
        private const val CHAXUN_HOST = "chaxun.icw.buaa.edu.cn"

        /** CAS 的 service：门户侧仍注册为 http，进入后全程强制 https。 */
        private const val SERVICE = "http://icw.buaa.edu.cn/checkbuaa.aspx"

        private const val ENTRANCE_PATH = "/gxzhcxzhb/login"
        private const val PAGE_SIZE = 100
        private const val MAX_LOGIN_HOPS = 15
        private const val MAX_TOTAL = 5000
        private const val MAX_PAGES = 100

        private const val CAS_EXPIRED_MESSAGE = "统一身份认证会话已失效，请重新登录"
        private const val AUTH_EXPIRED_MESSAGE = "财务系统会话已失效，请重新登录"

        private val ALLOWED_HOSTS = setOf(SSO_HOST, ICW_HOST, CHAXUN_HOST)

        /**
         * 把 Location 解析成绝对 https 地址（允许的 host 去掉端口，其它 host 直接拒绝）。
         *
         * 登录链路上首个 Location 常是 `http://icw…?ticket=…`，必须先改成 https，
         * 否则 CASTGC / 财务 Cookie 会以明文发出且 Cookie 的 secure 属性不匹配。
         */
        internal fun normalizeLocation(base: String, location: String): String {
            val resolved = HttpEngine.resolve(base, location)
            val uri = runCatching { URI.create(resolved) }.getOrNull()
                ?: throw ApiException("财务登录跳转到未知地址")
            val host = uri.host?.lowercase(Locale.US)
            if (host.isNullOrEmpty() || host !in ALLOWED_HOSTS) {
                throw ApiException("财务登录跳转到未知地址")
            }
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val query = uri.rawQuery
            return buildString {
                append("https://").append(host).append(path)
                if (!query.isNullOrEmpty()) append('?').append(query)
            }
        }
    }
}
