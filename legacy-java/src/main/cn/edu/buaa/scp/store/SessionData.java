package cn.edu.buaa.scp.store;

import org.json.JSONException;
import org.json.JSONObject;

/** 持久化的会话快照。<b>不包含密码</b>，只保存 CAS TGC 与业务会话。 */
public final class SessionData {

    public String studentId = "";
    public String openid = "";
    public String castgc = "";
    public String jsessionid = "";
    public long savedAt = 0L;
    public long lastSyncAt = 0L;

    public boolean hasAnything() {
        return !castgc.isEmpty() || !jsessionid.isEmpty();
    }

    public boolean hasCastgc() {
        return !castgc.isEmpty();
    }

    public boolean hasJsessionid() {
        return !jsessionid.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("studentId", studentId);
            json.put("openid", openid);
            json.put("castgc", castgc);
            json.put("jsessionid", jsessionid);
            json.put("savedAt", savedAt);
            json.put("lastSyncAt", lastSyncAt);
        } catch (JSONException ignored) {
            // 字段均为字符串/数字，不会失败
        }
        return json;
    }

    public static SessionData fromJson(JSONObject json) {
        SessionData data = new SessionData();
        if (json == null) {
            return data;
        }
        data.studentId = json.optString("studentId", "");
        data.openid = json.optString("openid", "");
        data.castgc = json.optString("castgc", "");
        data.jsessionid = json.optString("jsessionid", "");
        data.savedAt = json.optLong("savedAt", 0L);
        data.lastSyncAt = json.optLong("lastSyncAt", 0L);
        return data;
    }
}
