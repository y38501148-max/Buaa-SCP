package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.model.ZfwHome
import cn.edu.buaa.scp.store.ZfwSession
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 校园网自助服务（zfw.buaa.edu.cn）只读接口。
 *
 * zfw 没有 JSON API，首页是 Yii2 服务端渲染的 HTML；客户端只做两件事：
 * 1. 用 WebView 登录后保存的 Cookie 拉取 `/home`；
 * 2. 把 HTML 交给 [ZfwHtml] 解析出流量数据。
 *
 * 充值不在这里实现：打开官方 `/pay/card` 页面，由页面完成验证码与扫码支付。
 */
class ZfwApi(private val http: HttpEngine) {

    /** 拉取首页并解析；登录失效时抛 [ApiException]（authExpired=true）。 */
    suspend fun fetchHome(): ZfwHome = withContext(Dispatchers.IO) {
        val response = http.get(HOME_URL, browserHeaders())
        if (response.status in REDIRECT_CODES) {
            throw ApiException("校园网登录已失效，请重新登录", authExpired = true)
        }
        if (response.status == 401 || response.status == 403) {
            throw ApiException("校园网登录已失效（HTTP ${response.status}）", authExpired = true)
        }
        if (response.status != 200) {
            throw ApiException("网费服务异常（HTTP ${response.status}）")
        }
        val home = ZfwHtml.parseHome(response.body)
        if (home == null) {
            throw ApiException("校园网登录已失效，请重新登录", authExpired = true)
        }
        LogBus.step("网费数据已更新（在线 ${home.onlineCount} 台设备）")
        home
    }

    /** 当前 Cookie 头（可能因服务端续期而变化），用于回写本地会话。 */
    fun cookieHeader(): String = http.jar.header(HOME_URL).orEmpty()

    /** 用本地保存的 Cookie 头覆盖 zfw 域 Cookie。 */
    fun applyCookieHeader(raw: String) {
        http.jar.clearDomain(HOST)
        if (raw.isBlank()) return
        ZfwSession.parseCookieHeader(raw).forEach { (name, value) ->
            http.jar.put(HOST, name, value)
        }
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    companion object {

        const val HOST = "zfw.buaa.edu.cn"
        const val HOME_URL = "https://zfw.buaa.edu.cn/home"
        const val LOGIN_URL = "https://zfw.buaa.edu.cn/"
        const val PAY_URL = "https://zfw.buaa.edu.cn/pay/card"

        /**
         * zfw 是桌面版页面，按桌面 Chrome UA 请求可以得到与浏览器一致的 HTML；
         * 用微信 UA 反而可能触发移动端布局差异。
         */
        const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
