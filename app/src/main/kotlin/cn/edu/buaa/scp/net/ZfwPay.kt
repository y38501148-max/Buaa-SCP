package cn.edu.buaa.scp.net

import java.net.URLDecoder

/**
 * 网费充值二维码解析。
 *
 * zfw 提交 `/pay/card` 后返回的是「北航手机支付」页面，二维码图片本身是：
 * `/pay/qrcode?url=<urlencode(https://cashier.cc-pay.cn/cashier?id=...)>`。
 *
 * 因此不需要引入二维码解码库：直接从图片 src 里取 `url` 参数即可得到航财通收银台地址。
 * 只接受 `cashier.cc-pay.cn`，避免被页面里的其他图片诱导跳转。
 */
object ZfwPay {

    private val URL_PARAM = Regex("""[?&]url=([^&]+)""")
    private const val CASHIER_PREFIX = "https://cashier.cc-pay.cn/"

    /** 从 `/pay/qrcode?url=...` 的 src 提取收银台 URL；不是收银台时返回 null。 */
    fun cashierUrlFromQrSrc(src: String?): String? {
        val raw = src?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val encoded = URL_PARAM.find(raw)?.groupValues?.get(1) ?: return null
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith(CASHIER_PREFIX) }
    }

    /** 从收银台 URL 取订单 id；缺失返回空串。 */
    fun cashierId(cashierUrl: String?): String = runCatching {
        val query = cashierUrl.orEmpty().substringAfter('?', "")
        query.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "id" }
            ?.get(1)
            .orEmpty()
    }.getOrDefault("")
}
