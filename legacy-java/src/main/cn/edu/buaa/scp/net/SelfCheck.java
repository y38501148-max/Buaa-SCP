package cn.edu.buaa.scp.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络环境自检：不依赖账号密码，验证「设备 → 学校服务」这条链路是否通。
 *
 * <p>三项检查：
 * <ol>
 *   <li>统一身份认证（sso.buaa.edu.cn）可达，登录页可解析出 execution；</li>
 *   <li>校园卡掌上服务大厅入口可达，且按预期跳转；</li>
 *   <li>业务接口可达，未认证请求被正确拒绝（403 / 资源受限）。</li>
 * </ol>
 *
 * <p>自检使用独立 CookieJar，不污染当前登录会话。
 */
public final class SelfCheck {

    public static final String ENTRY =
            "https://m.icw.buaa.edu.cn/card/home/openHomePage?usertype=6&code=DSF";

    public static final class Item {
        public final String name;
        public final boolean ok;
        public final String detail;
        public final long costMs;

        Item(String name, boolean ok, String detail, long costMs) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
            this.costMs = costMs;
        }

        public String line() {
            return (ok ? "✅ " : "❌ ") + name + "（" + costMs + "ms）\n    " + detail;
        }
    }

    private SelfCheck() {
    }

    public static List<Item> run() {
        List<Item> items = new ArrayList<>();
        CookieJar jar = new CookieJar();
        HttpEngine http = new HttpEngine(jar);

        items.add(checkCas(http));
        items.add(checkEntry(http));
        items.add(checkBusiness(http));
        return items;
    }

    private static Item checkCas(HttpEngine http) {
        long started = System.currentTimeMillis();
        String url = "https://" + CasAuth.SSO_HOST + "/login?service="
                + Html.urlEncode(CasAuth.SERVICE) + "&noAutoRedirect=1";
        try {
            HttpEngine.Response response = http.get(url);
            long cost = System.currentTimeMillis() - started;
            if (response.isRedirect() && response.location() != null
                    && response.location().contains("ticket=")) {
                return new Item("统一身份认证", true, "已有 SSO 登录态，CAS 直接签发票据", cost);
            }
            String execution = Html.hiddenInputValue(response.body, "execution");
            if (execution != null && !execution.isEmpty()) {
                return new Item("统一身份认证", true,
                        "登录页正常，execution 令牌 " + execution.length() + " 字符", cost);
            }
            String hint = Html.errorMessage(response.body);
            return new Item("统一身份认证", false,
                    hint != null ? hint : "登录页返回 " + response.status + "，但未解析到 execution", cost);
        } catch (Exception e) {
            return new Item("统一身份认证", false, describe(e),
                    System.currentTimeMillis() - started);
        }
    }

    private static Item checkEntry(HttpEngine http) {
        long started = System.currentTimeMillis();
        try {
            HttpEngine.Response response = http.get(ENTRY);
            long cost = System.currentTimeMillis() - started;
            String location = response.location();
            if (response.isRedirect() && location != null && location.contains("/card")) {
                return new Item("掌上服务大厅入口", true,
                        "入口可用，HTTP " + response.status + " → " + HttpEngine.shortUrl(location), cost);
            }
            return new Item("掌上服务大厅入口", false,
                    "预期 302 到 /card，实际 HTTP " + response.status
                            + (location == null ? "" : " → " + HttpEngine.shortUrl(location)), cost);
        } catch (Exception e) {
            return new Item("掌上服务大厅入口", false, describe(e),
                    System.currentTimeMillis() - started);
        }
    }

    private static Item checkBusiness(HttpEngine http) {
        long started = System.currentTimeMillis();
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-requested-with", "XMLHttpRequest");
            headers.put("session-type", "uniapp");
            headers.put("isWechatApp", "true");
            headers.put("orgid", "2");
            headers.put("Content-Type", "application/json;charset=UTF-8");
            HttpEngine.Response response = http.post(
                    CardApi.BASE + "/cardpay/openCardPay", headers, "{}");
            long cost = System.currentTimeMillis() - started;
            String body = response.body == null ? "" : response.body;
            if (response.status == 403 || response.status == 401
                    || body.contains("资源受限") || body.contains("未认证")) {
                return new Item("业务接口", true,
                        "接口可达，未认证请求被正确拒绝（HTTP " + response.status + "）", cost);
            }
            if (response.status == 200) {
                return new Item("业务接口", true,
                        "接口可达，且当前会话已被识别（HTTP 200）", cost);
            }
            return new Item("业务接口", false,
                    "预期 200/403，实际 HTTP " + response.status + "：" + snippet(body), cost);
        } catch (Exception e) {
            return new Item("业务接口", false, describe(e),
                    System.currentTimeMillis() - started);
        }
    }

    public static String describe(Exception e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        if (message == null || message.isEmpty()) {
            return type;
        }
        return type + "：" + message;
    }

    private static String snippet(String text) {
        String cleaned = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 120 ? cleaned.substring(0, 120) + "…" : cleaned;
    }
}
