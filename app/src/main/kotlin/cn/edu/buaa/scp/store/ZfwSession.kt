package cn.edu.buaa.scp.store

import org.json.JSONObject

/**
 * 校园网自助服务（zfw.buaa.edu.cn）的会话快照。
 *
 * 校园网账号密码与统一身份认证是两套凭据；客户端不保存密码，只在首次使用时
 * 由用户在 WebView 里登录一次，然后保存登录后的 Cookie。
 *
 * `cookie` 是 `name=value; name=value` 形式的原始 Cookie 头，整体加密落盘。
 */
data class ZfwSession(
    val username: String = "",
    val name: String = "",
    val cookie: String = "",
    val savedAt: Long = 0L,
    val lastSyncAt: Long = 0L,
) {
    val hasCookie: Boolean get() = cookie.isNotBlank()

    fun toJson(): JSONObject = JSONObject().apply {
        put("username", username)
        put("name", name)
        put("cookie", cookie)
        put("savedAt", savedAt)
        put("lastSyncAt", lastSyncAt)
    }

    companion object {
        fun fromJson(json: JSONObject?): ZfwSession? {
            if (json == null) return null
            return ZfwSession(
                username = json.optString("username", ""),
                name = json.optString("name", ""),
                cookie = json.optString("cookie", ""),
                savedAt = json.optLong("savedAt", 0L),
                lastSyncAt = json.optLong("lastSyncAt", 0L),
            ).takeIf { it.hasCookie }
        }

        /** 解析 WebView / 响应里的 Cookie 头。 */
        fun parseCookieHeader(raw: String): List<Pair<String, String>> =
            raw.split(';').mapNotNull { part ->
                val item = part.trim()
                if (item.isEmpty()) return@mapNotNull null
                val index = item.indexOf('=')
                if (index <= 0) return@mapNotNull null
                item.substring(0, index).trim() to item.substring(index + 1).trim()
            }.filter { it.first.isNotEmpty() }

        /** 从 Cookie 列表里提取会话身份，供界面展示（不参与鉴权）。 */
        fun usernameFromCookie(raw: String): String =
            parseCookieHeader(raw).firstOrNull { it.first == "__services_identity" }?.second.orEmpty()
    }
}
