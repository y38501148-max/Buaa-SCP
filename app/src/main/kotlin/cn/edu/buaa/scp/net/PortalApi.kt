package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * i北航融合门户（it.buaa.edu.cn）网费余额。
 *
 * 门户首页的「网费」磁贴来自两项接口：
 * 1. `GET /personal/frontend/data/items?type=personal_data` 找到 `net.netFee` 的隐藏 id；
 * 2. `GET /personal/frontend/data/detail?id=<id>` 返回网费余额。
 *
 * 鉴权复用统一身份认证的 CASTGC：先访问门户的 CAS 登录入口，CAS 直接签票，
 * 门户回调后落下 `SESS_ID` 等 Cookie，再调用接口。CASTGC 失效时抛 authExpired。
 */
class PortalApi(private val http: HttpEngine) {

    /** 返回网费余额（元，两位小数字符串）；门户未配置该指标时返回 null。 */
    suspend fun netFeeBalance(): String? = withContext(Dispatchers.IO) {
        val items = requestJson(ITEMS_URL)
        val data = items.optJSONObject("d")?.optJSONArray("data")
            ?: throw ApiException("门户数据格式异常")
        var feeId: String? = null
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (item.optString("key") == "net.netFee") {
                feeId = item.optString("id")
                break
            }
        }
        if (feeId.isNullOrEmpty()) {
            LogBus.warn("门户未返回网费指标")
            return@withContext null
        }
        val detail = requestJson(DETAIL_URL + Html.urlEncode(feeId))
        val value = detail.optJSONObject("d")?.optJSONObject("data")?.opt("value")
            ?: return@withContext null
        formatAmount(value)
    }

    private fun requestJson(url: String): JSONObject {
        var response = http.get(url)
        if (response.isRedirect) {
            LogBus.step("门户会话失效，尝试用 CASTGC 重新登录")
            loginPortal()
            response = http.get(url)
        }
        if (response.isRedirect) {
            throw ApiException("统一身份认证已失效，请重新登录", authExpired = true)
        }
        if (response.status == 401 || response.status == 403) {
            throw ApiException("统一身份认证已失效，请重新登录", authExpired = true)
        }
        if (response.status != 200) {
            throw ApiException("门户接口异常（HTTP ${response.status}）")
        }
        val text = response.body.trim()
        if (!text.startsWith("{")) {
            throw ApiException("门户返回了登录页，请重新登录", authExpired = true)
        }
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw ApiException("门户返回内容无法解析") }
        if (root.optInt("e", 0) != 0) {
            val message = root.optString("m").ifBlank { "门户接口返回失败" }
            val expired = message.contains("登录") || message.contains("认证")
            throw ApiException(message, authExpired = expired)
        }
        return root
    }

    /**
     * 门户 CAS 登录入口：`cas_login/30001` 会跳转到统一身份认证，
     * 携带 CASTGC 时 CAS 直接签票并回跳，完成门户会话建立。
     */
    private fun loginPortal() {
        val target = ITEMS_URL
        val entry = "$PORTAL/manage/common/cas_login/30001?to_bind=0&redirect=" +
                Html.urlEncode(target)
        val start = http.get(entry)
        val final = http.follow(start, 8)
        LogBus.step("门户登录回调结束（HTTP ${final.status}）")
    }

    companion object {
        private const val PORTAL = "https://it.buaa.edu.cn"
        const val ITEMS_URL = "$PORTAL/personal/frontend/data/items?type=personal_data"
        const val DETAIL_URL = "$PORTAL/personal/frontend/data/detail?id="

        /** 门户指标值可能是 Double（103.79000000000001）或字符串；统一保留两位小数。 */
        fun formatAmount(raw: Any?): String? {
            if (raw == null || raw == JSONObject.NULL) return null
            val number = when (raw) {
                is Number -> BigDecimal.valueOf(raw.toDouble())
                else -> raw.toString().toBigDecimalOrNull() ?: return null
            }
            return number.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }
}
