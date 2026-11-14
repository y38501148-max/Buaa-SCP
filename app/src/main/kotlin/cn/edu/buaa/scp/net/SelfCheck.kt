package cn.edu.buaa.scp.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络环境自检：不依赖账号密码，验证「设备 → 学校服务」这条链路是否通。
 *
 * 三项检查：
 * 1. 统一身份认证（sso.buaa.edu.cn）可达，登录页可解析出 execution；
 * 2. 校园卡掌上服务大厅入口可达，且按预期跳转；
 * 3. 业务接口可达，未认证请求被正确拒绝（403 / 资源受限）。
 *
 * 自检使用独立 CookieJar，不污染当前登录会话。
 */
object SelfCheck {

    const val ENTRY = "https://m.icw.buaa.edu.cn/card/home/openHomePage?usertype=6&code=DSF"

    data class Item(
        val name: String,
        val ok: Boolean,
        val detail: String,
        val costMs: Long,
    ) {
        fun line(): String = "${if (ok) "✅" else "❌"} $name（${costMs}ms）\n    $detail"
    }

    suspend fun run(): List<Item> = withContext(Dispatchers.IO) {
        val http = HttpEngine(CookieJar())
        listOf(checkCas(http), checkEntry(http), checkBusiness(http))
    }

    private fun checkCas(http: HttpEngine): Item {
        val started = System.currentTimeMillis()
        val url = "https://${CasAuth.SSO_HOST}/login?service=" +
                "${Html.urlEncode(CasAuth.SERVICE)}&noAutoRedirect=1"
        return try {
            val response = http.get(url)
            val cost = System.currentTimeMillis() - started
            val location = response.location
            if (response.isRedirect && location != null && location.contains("ticket=")) {
                Item("统一身份认证", true, "已有 SSO 登录态，CAS 直接签发票据", cost)
            } else {
                val execution = Html.hiddenInputValue(response.body, "execution")
                when {
                    !execution.isNullOrEmpty() -> Item(
                        "统一身份认证", true,
                        "登录页正常，execution 令牌 ${execution.length} 字符", cost,
                    )

                    else -> Item(
                        "统一身份认证", false,
                        Html.errorMessage(response.body)
                            ?: "登录页返回 ${response.status}，但未解析到 execution",
                        cost,
                    )
                }
            }
        } catch (e: Exception) {
            Item("统一身份认证", false, describe(e), System.currentTimeMillis() - started)
        }
    }

    private fun checkEntry(http: HttpEngine): Item {
        val started = System.currentTimeMillis()
        return try {
            val response = http.get(ENTRY)
            val cost = System.currentTimeMillis() - started
            val location = response.location
            if (response.isRedirect && location != null && location.contains("/card")) {
                Item(
                    "掌上服务大厅入口", true,
                    "入口可用，HTTP ${response.status} → ${HttpEngine.shortUrl(location)}", cost,
                )
            } else {
                Item(
                    "掌上服务大厅入口", false,
                    "预期 302 到 /card，实际 HTTP ${response.status}" +
                            (location?.let { " → ${HttpEngine.shortUrl(it)}" } ?: ""),
                    cost,
                )
            }
        } catch (e: Exception) {
            Item("掌上服务大厅入口", false, describe(e), System.currentTimeMillis() - started)
        }
    }

    private fun checkBusiness(http: HttpEngine): Item {
        val started = System.currentTimeMillis()
        return try {
            val headers = mapOf(
                "x-requested-with" to "XMLHttpRequest",
                "session-type" to "uniapp",
                "isWechatApp" to "true",
                "orgid" to "2",
                "Content-Type" to "application/json;charset=UTF-8",
            )
            val response = http.post(CardApi.BASE + "/cardpay/openCardPay", headers, "{}")
            val cost = System.currentTimeMillis() - started
            val body = response.body
            when {
                response.status == 403 || response.status == 401 ||
                        body.contains("资源受限") || body.contains("未认证") ->
                    Item("业务接口", true, "接口可达，未认证请求被正确拒绝（HTTP ${response.status}）", cost)

                response.status == 200 ->
                    Item("业务接口", true, "接口可达，且当前会话已被识别（HTTP 200）", cost)

                else -> Item(
                    "业务接口", false,
                    "预期 200/403，实际 HTTP ${response.status}：${snippet(body)}", cost,
                )
            }
        } catch (e: Exception) {
            Item("业务接口", false, describe(e), System.currentTimeMillis() - started)
        }
    }

    fun describe(e: Throwable): String {
        val message = e.message
        val type = e.javaClass.simpleName
        return if (message.isNullOrEmpty()) type else "$type：$message"
    }

    private fun snippet(text: String): String {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length > 120) cleaned.take(120) + "…" else cleaned
    }
}
