package cn.edu.buaa.scp.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 支付码与扫码使用官方 AES 信封。响应包含支付凭据，禁止转储或记录响应正文。 */
class CardPaymentApi(private val http: HttpEngine) {
    suspend fun paymentCode(openid: String): PaymentCode =
        PaymentCode.parse(post(CODE_PATH, JSONObject(), openid))

    suspend fun orderStatus(openid: String, code: String): JSONObject =
        post(STATUS_PATH, JSONObject().put("paycode", code), openid)

    // 扫码接口可能直接扣款。调用者须确认；禁止 withRecovery 或自动重试。
    suspend fun consume(openid: String, code: String, paytime: Long): JSONObject =
        post(SCAN_PATH, scanPayload(code, paytime), openid)

    private suspend fun post(path: String, payload: JSONObject, openid: String): JSONObject =
        withContext(Dispatchers.IO) {
            if (openid.isBlank()) throw ApiException("请重新登录后使用校园码", authExpired = true)
            payload.put("openid", openid).put("orgid", "2")
            val response = http.request(
                "POST", CardApi.BASE + path + "?openid=" + Html.urlEncode(openid),
                mapOf(
                    "x-requested-with" to "XMLHttpRequest", "session-type" to "uniapp",
                    "isWechatApp" to "true", "orgid" to "2",
                    "Content-Type" to "application/json;charset=UTF-8",
                ),
                JSONObject().put("datajson", Crypto.wrapRequest(payload)).toString(),
                sensitive = true,
            )
            decode(response.status, response.body)
        }

    companion object {
        const val CODE_PATH = "/offlineCode/openVirtualcard"
        const val STATUS_PATH = "/virtualcard/queryOrderStatus"
        const val SCAN_PATH = "/scan/scanningConsume"

        fun scanPayload(code: String, paytime: Long): JSONObject {
            require(code.isNotBlank() && code.length <= 8192) { "二维码内容无效，请重新扫描" }
            require(paytime > 0)
            return JSONObject().put("qrcode", code).put("paytime", paytime)
        }

        internal fun decode(status: Int, body: String): JSONObject {
            if (status == 401 || status == 403) {
                throw ApiException("校园卡会话已失效，请重新登录", authExpired = true)
            }
            if (status != 200) throw ApiException("校园码服务异常（HTTP $status）")
            val root = try {
                val json = JSONObject(body)
                if (json.has("datajson")) Crypto.decrypt(json.getString("datajson")) else json
            } catch (_: Exception) {
                throw ApiException("校园码响应无法解析，请稍后重试")
            }
            if (root.opt("success") !is Boolean) throw ApiException("校园码响应缺少结果状态")
            if (!root.optBoolean("success") && listOf("未认证", "会话失效", "会话已失效", "资源受限", "登录过期", "请重新登录")
                    .any { root.optString("message").contains(it) }) {
                throw ApiException("校园卡会话已失效，请重新登录", authExpired = true)
            }
            // 保留失败响应的 resultData，官方可能要求进入金额/密码确认页。
            return root
        }
    }
}

class PaymentCode(val value: String, val balance: String = "") {
    /** 官方以 5638 开头的码是十六进制字节串，不能把十六进制文本直接画成 QR。 */
    val binary: Boolean get() = value.startsWith("5638")

    fun qrContent(): String = if (binary) {
        require(value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" }) {
            "支付码数据格式异常"
        }
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.ISO_8859_1)
    } else value

    companion object {
        // 官方页面固定的刷新周期，不将其宣称为服务端有效期。
        const val REFRESH_MS = 15_000L

        fun parse(root: JSONObject): PaymentCode {
            if (!root.optBoolean("success")) {
                throw ApiException(root.optString("message").ifBlank { "暂时无法获取支付二维码" })
            }
            val value = root.optJSONObject("data")?.optString("code").orEmpty()
            if (value.isBlank() || value == "null" || value.length > 8192) {
                throw ApiException("服务端未返回有效支付二维码")
            }
            val balance = root.optJSONObject("data")?.optString("cardbal").orEmpty()
                .takeUnless { it == "null" }.orEmpty()
            return PaymentCode(value, balance).also { it.qrContent() }
        }
    }
}
