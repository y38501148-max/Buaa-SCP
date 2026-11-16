package cn.edu.buaa.scp.net

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** 扫码结果与付款码轮询使用不同的响应结构，共用后续页校验。 */
class ScanOutcome(val success: Boolean, val message: String, val followUp: String?) {
    companion object {
        fun parse(root: JSONObject, openid: String): ScanOutcome {
            val data = root.optJSONObject("resultData")
            val success = root.optBoolean("success")
            val message = if (!success) root.optString("message").ifBlank { "扫码未完成" } else {
                when (data?.optString("type")) {
                    "scj" -> "签到成功"
                    "opendevice" -> "设备开阀成功"
                    "bindTray" -> "绑盘成功"
                    else -> "支付成功"
                }
            }
            return continuation(success, message, data, openid)
        }

        /** 官方 queryOrderStatus 的 url 在响应顶层；成功且无 url 表示继续等待。 */
        fun order(root: JSONObject, openid: String): ScanOutcome? {
            if (!root.optBoolean("success")) {
                return ScanOutcome(false, root.optString("message").ifBlank { "支付未完成，请查看账单确认结果" }, null)
            }
            if (root.optString("url").isBlank()) return null
            return continuation(true, "请继续处理支付", root, openid)
        }

        private fun continuation(success: Boolean, message: String, data: JSONObject?, openid: String): ScanOutcome {
            val route = data?.optString("url").orEmpty()
            if (route.isBlank()) return ScanOutcome(success, message, null)
            val url = try { pageUrl(route, data!!, openid) } catch (_: IllegalArgumentException) {
                return ScanOutcome(success, "服务端要求继续处理，但页面地址不受支持，请查看账单确认结果", null)
            }
            return ScanOutcome(success, message, url)
        }

        fun pageUrl(route: String, data: JSONObject, openid: String): String {
            // 只接受内部路径；查询参数逐项解码再编码，保留业务参数并覆盖身份/数据参数。
            val uri = try { URI(route) } catch (_: Exception) { throw IllegalArgumentException("后续页面地址无效") }
            require(uri.scheme == null && uri.rawAuthority == null && uri.rawFragment == null && Regex("^/pages(?:_other)?/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$").matches(uri.rawPath.orEmpty())) {
                "暂不支持此扫码后续页面"
            }
            val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.mapNotNull { part ->
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                require((key + value).none { it.code < 32 || it.code == 127 })
                if (key in setOf("data", "openid", "usertype")) null else Html.urlEncode(key) + "=" + Html.urlEncode(value)
            }.toMutableList()
            val payload = JSONObject(data.toString()).put("openid", openid)
            params += "data=" + Html.urlEncode(payload.toString())
            params += "openid=" + Html.urlEncode(openid)
            params += "usertype=6"
            return CardApi.BASE + "/#" + uri.rawPath + "?" + params.joinToString("&")
        }

        fun isTrustedPage(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "m.icw.buaa.edu.cn" && uri.port in listOf(-1, 443) &&
                uri.rawUserInfo == null && uri.rawPath in listOf("/card", "/card/", "/card/index.html")
        } catch (_: Exception) { false }

        fun terminalMessage(url: String): String? {
            if (!isTrustedPage(url)) return null
            val route = url.substringAfter('#', "").substringBefore('?')
            return when {
                route.startsWith("/pages/common/success/") -> "操作成功"
                route.startsWith("/pages/common/fail/") || route.startsWith("/pages/common/error/") ->
                    Html.failPageMessage(url) ?: "操作未完成"
                else -> null
            }
        }
    }
}
