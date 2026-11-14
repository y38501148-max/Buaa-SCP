package cn.edu.buaa.scp.net;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.edu.buaa.scp.util.LogBus;

/**
 * 校园卡掌上服务大厅（m.icw.buaa.edu.cn/card）只读业务接口。
 *
 * <p>约定：请求固定带 5 个头部；只认 JSESSIONID 会话；响应可能是明文 JSON，
 * 也可能是 {@code {"datajson": ...}} 的 AES 密文。
 *
 * <p>本类<b>只做读操作</b>，不包含任何充值/支付/挂失提交接口。
 */
public final class CardApi {

    public static final String BASE = "https://m.icw.buaa.edu.cn/card";

    private final HttpEngine http;

    public CardApi(HttpEngine http) {
        this.http = http;
    }

    // ---------------- 会话 / 首页 ----------------

    /** 充值页初始化：同时带回卡号、余额、支付通道状态。最稳定的会话校验接口。 */
    public JSONObject openCardPay() throws IOException, ApiException {
        return call("/cardpay/openCardPay", new JSONObject());
    }

    /**
     * 首页聚合数据（GET）。返回 cardinfo（含 cardbal / accstatusStr）、pcodeCard、通知等。
     * 前端固定会带 openid；失败时上层可回退到 openCardPay。
     */
    public JSONObject openHomePageApp(String openid) throws IOException, ApiException {
        if (openid == null || openid.isEmpty()) {
            throw new ApiException("缺少 openid，无法加载首页聚合数据");
        }
        String url = BASE + "/home/openHomePageApp?openid=" + Html.urlEncode(openid)
                + "&usertype=6&ua=" + Html.urlEncode(HttpEngine.UA);
        HttpEngine.Response response = http.get(url, baseHeaders());
        return unwrap(response, "/home/openHomePageApp");
    }

    // ---------------- 账单 / 流水 ----------------

    /** 月度账单（饼图数据）：startdate=enddate="YYYY-MM"。 */
    public JSONObject monthlyBill(String month, String openid) throws IOException, ApiException {
        JSONObject payload = new JSONObject();
        put(payload, "startdate", month);
        put(payload, "enddate", month);
        if (openid != null && !openid.isEmpty()) {
            put(payload, "openid", openid);
        }
        return call("/bill/queryUserMonthlybill", payload);
    }

    /** 按月充值汇总：startdate/enddate="YYYY-MM"，金额单位分。 */
    public JSONObject rechargeByMonth(String startMonth, String endMonth)
            throws IOException, ApiException {
        JSONObject payload = new JSONObject();
        put(payload, "startdate", startMonth);
        put(payload, "enddate", endMonth);
        return call("/bill/queryRechargeGroupByMonth", payload);
    }

    /** 近半年月度柱状数据，同时带 totalamt / totalcount 汇总。 */
    public JSONObject halfYear(String startMonth, String endMonth, JSONArray dateArray)
            throws IOException, ApiException {
        JSONObject payload = new JSONObject();
        put(payload, "startdate", startMonth);
        put(payload, "enddate", endMonth);
        if (dateArray != null) {
            put(payload, "dateArray", dateArray);
        }
        return call("/bill/queryUserMonthlybillHalfOfYear", payload);
    }

    /** 消费流水（分页）：pageNumber 从 0 开始，tradetype=-1 表示全部。 */
    public JSONObject trades(String startDate, String endDate, int tradetype,
                             int pageNumber, int pageSize) throws IOException, ApiException {
        JSONObject payload = new JSONObject();
        put(payload, "starttime", startDate);
        put(payload, "endtime", endDate);
        put(payload, "tradetype", String.valueOf(tradetype));
        put(payload, "pageNumber", pageNumber);
        put(payload, "pageSize", pageSize);
        return call("/selftrade/queryCardSelfTradeList", payload);
    }

    // ---------------- 卡务（只读） ----------------

    /** 挂失页数据：只读取卡号、姓名、卡片状态，不执行挂失。 */
    public JSONObject reportLossInfo() throws IOException, ApiException {
        return call("/losscard/openCardReportLoss", new JSONObject());
    }

    // ---------------- 通用调用与解包 ----------------

    public JSONObject call(String path, JSONObject payload) throws IOException, ApiException {
        Map<String, String> headers = baseHeaders();
        headers.put("Content-Type", "application/json;charset=UTF-8");
        String body = payload == null ? "{}" : payload.toString();
        HttpEngine.Response response = http.post(BASE + path, headers, body);
        return unwrap(response, path);
    }

    private static Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-requested-with", "XMLHttpRequest");
        headers.put("session-type", "uniapp");
        headers.put("isWechatApp", "true");
        headers.put("orgid", "2");
        return headers;
    }

    /** 统包处理：HTTP 错误、非 JSON、datajson 解密、success=false。 */
    private JSONObject unwrap(HttpEngine.Response response, String path) throws ApiException {
        if (response.status == 401 || response.status == 403) {
            throw new ApiException("会话无效（HTTP " + response.status + "），请重新登录", true);
        }
        if (response.status != 200) {
            throw new ApiException("接口异常 HTTP " + response.status + " " + snippet(response.body),
                    false);
        }

        String text = response.body == null ? "" : response.body.trim();
        JSONObject root;
        if (text.startsWith("[")) {
            try {
                JSONObject wrapped = new JSONObject();
                wrapped.put("resultData", new JSONArray(text));
                return wrapped;
            } catch (JSONException e) {
                throw new ApiException("响应解析失败（" + path + "）", false);
            }
        }
        try {
            root = new JSONObject(text);
        } catch (JSONException e) {
            boolean expired = containsAny(text, "资源受限", "未认证", "会话失效", "登录过期");
            throw new ApiException(expired ? "会话已失效：" + snippet(text)
                    : "响应不是 JSON（" + path + "）：" + snippet(text), expired);
        }

        if (root.has("datajson")) {
            try {
                root = Crypto.decrypt(root.optString("datajson"));
            } catch (JSONException e) {
                throw new ApiException("响应解密失败：" + e.getMessage(), false);
            }
        }

        if (root.has("success") && !root.optBoolean("success", true)) {
            String message = root.optString("message",
                    root.optString("msg", "请求失败"));
            boolean expired = containsAny(message, "未认证", "会话失效", "资源受限",
                    "登录过期", "请重新登录");
            LogBus.warn(path + " 返回失败：" + message);
            throw new ApiException(message.isEmpty() ? "请求失败" : message, expired);
        }
        return root;
    }

    private static void put(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException ignored) {
            // org.json 仅在 key 为 null 时抛异常
        }
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null) {
            return false;
        }
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 180) {
            cleaned = cleaned.substring(0, 180) + "…";
        }
        return cleaned;
    }
}
