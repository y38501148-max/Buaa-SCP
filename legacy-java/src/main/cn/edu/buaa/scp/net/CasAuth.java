package cn.edu.buaa.scp.net;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.edu.buaa.scp.util.LogBus;

/**
 * 北航统一身份认证（CAS）→ 校园卡掌上服务大厅业务会话。
 *
 * <p>依据实测链路（见 docs/SSO_CHAIN.md）：
 * <ol>
 *   <li>回调路径里的 openid 位置必须填<b>哨兵值 {@value #SENTINEL_OPENID}</b>；</li>
 *   <li>CAS 票据一次性，业务接口只认 {@code JSESSIONID}；</li>
 *   <li>{@code CASTGC} 可复用，重启后免密换票；</li>
 *   <li>密码错误时 CAS 返回 200 登录页（含错误提示）或 401；</li>
 *   <li>业务接口切勿再附加 openid 参数，否则可能触发 401。</li>
 * </ol>
 */
public final class CasAuth {

    public static final String SSO_HOST = "sso.buaa.edu.cn";
    public static final String CARD_HOST = "m.icw.buaa.edu.cn";
    public static final String SENTINEL_OPENID = "112233";

    /** CAS 回调地址：openid 位置是哨兵值，usertype=6（学生）。 */
    public static final String SERVICE = "https://" + CARD_HOST
            + "/card/home/sisUstbMiniReturnPage/" + SENTINEL_OPENID + "/6/auth";

    private static final String SSO_LOGIN = "https://" + SSO_HOST + "/login";

    private final HttpEngine http;
    private final CookieJar jar;

    public CasAuth(HttpEngine http) {
        this.http = http;
        this.jar = http.jar();
    }

    /** 登录结果。失败时 message 可直接展示给用户。 */
    public static final class Result {
        public boolean ok;
        public boolean needPassword;
        public String message = "";
        public String openid = "";
        public String castgc = "";
        public String jsessionid = "";

        static Result fail(String message, boolean needPassword) {
            Result result = new Result();
            result.message = message == null || message.isEmpty() ? "登录失败" : message;
            result.needPassword = needPassword;
            return result;
        }

        static Result success(String openid, String castgc, String jsessionid) {
            Result result = new Result();
            result.ok = true;
            result.openid = openid == null ? "" : openid;
            result.castgc = castgc == null ? "" : castgc;
            result.jsessionid = jsessionid == null ? "" : jsessionid;
            return result;
        }
    }

    public String authUrl(boolean noAutoRedirect) {
        return SSO_LOGIN + "?service=" + Html.urlEncode(SERVICE)
                + (noAutoRedirect ? "&noAutoRedirect=1" : "");
    }

    /**
     * 用已有 CASTGC 直接换票据 → 新 JSESSIONID（免密续期）。
     * 返回 needPassword=true 表示 CASTGC 已失效。
     */
    public Result restore() throws IOException {
        LogBus.step("会话续期：携带 CASTGC 请求 CAS 授权");
        HttpEngine.Response response = http.get(authUrl(true));
        if (response.isRedirect()) {
            String location = response.location();
            if (location != null && location.contains("ticket=")) {
                return consumeTicket(location);
            }
            LogBus.step("CAS 未签发票据（" + HttpEngine.shortUrl(location) + "），需重新登录");
            return Result.fail("登录态已过期，请重新输入密码", true);
        }
        LogBus.step("CAS 返回登录页（HTTP " + response.status + "），CASTGC 已失效");
        return Result.fail("登录态已过期，请重新输入密码", true);
    }

    /** 账号密码登录：GET 登录页 → POST 凭据 → 跟随回调换业务会话。 */
    public Result login(String studentId, String password) throws IOException {
        if (studentId == null || studentId.isEmpty() || password == null || password.isEmpty()) {
            return Result.fail("请输入学号与密码", true);
        }

        LogBus.step("① 获取 CAS 登录页");
        HttpEngine.Response page = http.get(authUrl(true));
        if (page.isRedirect()) {
            String location = page.location();
            if (location != null && location.contains("ticket=")) {
                LogBus.step("检测到已有登录态，直接换取票据");
                return consumeTicket(location);
            }
            page = http.follow(page, 4);
        }

        String execution = Html.hiddenInputValue(page.body, "execution");
        if (execution == null || execution.isEmpty()) {
            String hint = Html.errorMessage(page.body);
            return Result.fail(hint != null ? hint
                    : "未解析到 CAS 登录表单（execution 缺失），页面可能已改版", false);
        }
        LogBus.step("② CAS 表单已解析（execution " + execution.length() + " 字符），提交凭据");

        StringBuilder form = new StringBuilder();
        appendForm(form, "username", studentId);
        appendForm(form, "password", password);
        appendForm(form, "type", "username_password");
        appendForm(form, "execution", execution);
        appendForm(form, "_eventId", "submit");

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("Referer", authUrl(true));
        headers.put("Origin", "https://" + SSO_HOST);

        HttpEngine.Response post = http.post(authUrl(false), headers,
                form.substring(0, form.length() - 1));

        if (post.isRedirect()) {
            String location = post.location();
            if (location != null && location.contains("ticket=")) {
                LogBus.step("③ 已取得 CAS 票据（ST），开始回调业务系统");
                return consumeTicket(location);
            }
            LogBus.warn("CAS 未签发票据，跳转到 " + HttpEngine.shortUrl(location));
            return Result.fail("认证未通过，未取得票据。请检查账号状态或稍后重试", true);
        }

        String error = Html.errorMessage(post.body);
        if (error != null) {
            LogBus.warn("CAS 返回错误提示：" + error);
            return Result.fail(error, true);
        }
        if (post.status == 401 || post.status == 403) {
            return Result.fail("学号或密码错误（HTTP " + post.status + "）", true);
        }
        if (post.status >= 500) {
            return Result.fail("统一身份认证服务异常（HTTP " + post.status + "），请稍后再试", true);
        }
        return Result.fail("登录失败（HTTP " + post.status + "），请检查学号与密码", true);
    }

    /** GET 回调地址（带 ticket）→ 302 goIndex?openid=… → 记录 JSESSIONID。 */
    private Result consumeTicket(String ticketLocation) throws IOException {
        LogBus.step("④ 回调校园卡服务，换取业务会话");
        HttpEngine.Response response = http.get(ticketLocation);
        String openid = Html.queryParam(ticketLocation, "openid");
        String failure = null;

        for (int hop = 0; hop < 8; hop++) {
            String location = response.location();
            if (location != null && isFailLocation(location)) {
                failure = Html.failPageMessage(location);
                LogBus.warn("回调被导向失败页：" + (failure == null ? "未知原因" : failure));
                break;
            }
            if (location != null) {
                String fromLocation = Html.queryParam(location, "openid");
                if (fromLocation != null && !fromLocation.isEmpty()) {
                    openid = fromLocation;
                }
            }
            String fromUrl = Html.queryParam(response.url, "openid");
            if (fromUrl != null && !fromUrl.isEmpty()) {
                openid = fromUrl;
            }
            if (!response.isRedirect() || location == null || location.isEmpty()
                    || location.contains("#")) {
                break;
            }
            response = http.get(HttpEngine.resolve(response.url, location));
        }

        String jsessionid = jar.valueOf(CARD_HOST, "JSESSIONID");
        boolean hasOpenid = openid != null && !openid.isEmpty();

        if (failure == null && !hasOpenid) {
            failure = Html.jsonMessage(response.body);
            if (failure == null && response.body.contains("用户信息不匹配")) {
                failure = "用户信息不匹配（回调 openid 位置必须为哨兵值）";
            }
        }
        if (failure != null) {
            return Result.fail(failure, true);
        }
        if (!hasOpenid) {
            return Result.fail("回调未返回 openid（票据可能已过期或已被使用）", true);
        }
        if (jsessionid == null || jsessionid.isEmpty()) {
            return Result.fail("未取得业务会话（JSESSIONID 缺失）", true);
        }

        LogBus.step("会话已建立：openid=" + mask(openid)
                + "，JSESSIONID " + jsessionid.length() + " 字符");
        return Result.success(openid, jar.valueOf(SSO_HOST, "CASTGC"), jsessionid);
    }

    private static boolean isFailLocation(String location) {
        return location.contains("isError=ERROR")
                || location.contains("pages/common/fail")
                || location.contains("/card/errorPage");
    }

    private static void appendForm(StringBuilder sb, String key, String value) {
        sb.append(Html.urlEncode(key)).append('=')
                .append(Html.urlEncode(value)).append('&');
    }

    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            return value.charAt(0) + "***" + value.charAt(value.length() - 1);
        }
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }
}
