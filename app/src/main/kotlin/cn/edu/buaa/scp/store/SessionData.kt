package cn.edu.buaa.scp.store

import org.json.JSONObject

/** 持久化的会话快照。**不包含密码**，只保存 CAS TGC 与业务会话。 */
data class SessionData(
    val studentId: String = "",
    val openid: String = "",
    val castgc: String = "",
    val jsessionid: String = "",
    val savedAt: Long = 0L,
    val lastSyncAt: Long = 0L,
) {
    val hasAnything: Boolean get() = castgc.isNotEmpty() || jsessionid.isNotEmpty()
    val hasCastgc: Boolean get() = castgc.isNotEmpty()
    val hasJsessionid: Boolean get() = jsessionid.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("studentId", studentId)
        put("openid", openid)
        put("castgc", castgc)
        put("jsessionid", jsessionid)
        put("savedAt", savedAt)
        put("lastSyncAt", lastSyncAt)
    }

    companion object {
        fun fromJson(json: JSONObject?): SessionData {
            if (json == null) return SessionData()
            return SessionData(
                studentId = json.optString("studentId", ""),
                openid = json.optString("openid", ""),
                castgc = json.optString("castgc", ""),
                jsessionid = json.optString("jsessionid", ""),
                savedAt = json.optLong("savedAt", 0L),
                lastSyncAt = json.optLong("lastSyncAt", 0L),
            )
        }
    }
}
