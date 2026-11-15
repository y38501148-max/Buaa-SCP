package cn.edu.buaa.scp.net

import org.json.JSONObject

/** 官方扫码结果有直接完成和继续输入金额/密码两种分支，失败响应也可能携带后续页面。 */
class ScanOutcome(val success: Boolean, val message: String, val followUp: String?) {
    companion object {
        fun parse(root: JSONObject, openid: String): ScanOutcome {
            val data = root.optJSONObject("resultData")
            val route = data?.optString("url").orEmpty()
            val followUp = if (route.isNotBlank()) pageUrl(route, data!!, openid) else null
            val success = root.optBoolean("success")
            val message = if (!success) root.optString("message").ifBlank { "扫码未完成" } else {
                when (data?.optString("type")) {
                    "scj" -> "签到成功"
                    "opendevice" -> "设备开阀成功"
                    "bindTray" -> "绑盘成功"
                    else -> "支付成功"
                }
            }
            return ScanOutcome(success, message, followUp)
        }

        fun pageUrl(route: String, data: JSONObject, openid: String): String {
            // 仅接受官方 uni-app 内部路由，绝不把会话注入扫码内容提供的任意网址。
            require(Regex("^/pages(?:_other)?/[A-Za-z0-9_/-]+$").matches(route)) {
                "暂不支持此扫码后续页面"
            }
            return CardApi.BASE + "/#" + route + "?data=" + Html.urlEncode(data.toString()) +
                "&openid=" + Html.urlEncode(openid) + "&usertype=6"
        }
    }
}
