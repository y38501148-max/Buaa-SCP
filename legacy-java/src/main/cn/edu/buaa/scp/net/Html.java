package cn.edu.buaa.scp.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CAS / 校园卡的纯文本解析工具。不依赖 Android，可直接在 JVM 单元测试中覆盖。
 */
public final class Html {

    private static final Pattern INPUT_TAG =
            Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    private Html() {
    }

    /**
     * 取 &lt;input&gt; 的 value。CAS 表单的 input 属性顺序不固定（name 可能在 value 之后），
     * 所以先抓整段标签，再逐个属性解析。
     */
    public static String hiddenInputValue(String html, String name) {
        if (html == null || name == null) {
            return null;
        }
        Matcher matcher = INPUT_TAG.matcher(html);
        while (matcher.find()) {
            String tag = matcher.group();
            if (name.equalsIgnoreCase(attr(tag, "name"))) {
                String value = attr(tag, "value");
                return value == null ? null : unescape(value);
            }
        }
        return null;
    }

    /** 取标签里某个属性的值（支持双引号 / 单引号）。 */
    public static String attr(String tag, String attribute) {
        if (tag == null || attribute == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(attribute)
                + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(tag);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
    }

    /**
     * 从 CAS 响应页提取可读错误。北航 CAS 的提示多为英文（Invalid credentials.），
     * 中文关键字一并覆盖；若页面出现验证码输入框，单独给出可操作提示。
     */
    public static String errorMessage(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        String text = stripTags(html);
        String lower = text.toLowerCase();

        boolean captcha = hiddenInputValue(html, "validateCode") != null
                || hiddenInputValue(html, "captcha") != null
                || hiddenInputValue(html, "checkcode") != null
                || lower.contains("kaptcha")
                || lower.contains("验证码");

        if (lower.contains("invalid credentials") || lower.contains("bad credentials")
                || text.contains("用户名或密码") || text.contains("账号或密码")
                || text.contains("账户或密码") || text.contains("密码错误")
                || text.contains("密码不正确") || text.contains("无效的凭据")) {
            return captcha
                    ? "账号或密码错误，且当前已要求输入验证码。请稍后重试，或先在浏览器登录一次 sso.buaa.edu.cn"
                    : "账号或密码错误，请检查学号与统一身份认证密码";
        }
        if (captcha) {
            return "统一身份认证要求输入验证码，本应用暂不支持，请先在浏览器完成一次登录";
        }
        if (lower.contains("account locked") || lower.contains("locked")
                || text.contains("已锁定") || text.contains("锁定")) {
            return "账号已被锁定，请联系学校信息化办公室";
        }
        if (lower.contains("disabled") || text.contains("已禁用")) {
            return "账号已被禁用";
        }
        if (lower.contains("expired") || text.contains("已过期")) {
            return "密码或登录态已过期，请重新登录";
        }
        if (lower.contains("too many") || text.contains("频繁")) {
            return "尝试过于频繁，请稍后再试";
        }
        return null;
    }

    public static String stripTags(String html) {
        if (html == null) {
            return "";
        }
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        return unescape(cleaned).replaceAll("\\s+", " ").trim();
    }

    public static String unescape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&amp;", "&");
    }

    /** 取 URL 查询串中的参数（已 URL 解码）；fragment 里的查询串同样适用。 */
    public static String queryParam(String url, String name) {
        if (url == null || name == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&#]+)")
                .matcher(url);
        if (!matcher.find()) {
            return null;
        }
        return urlDecode(matcher.group(1));
    }

    /** 失败页形如 /card/?isError=ERROR#/pages/common/fail/fail?data=%7B...%7D。 */
    public static String failPageMessage(String location) {
        String data = queryParam(location, "data");
        if (data == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(data);
            String message = json.optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
            String title = json.optString("title", "");
            return title.isEmpty() ? null : title;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 响应体若是 JSON，取其 message / msg。 */
    public static String jsonMessage(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(trimmed);
            String message = json.optString("message", json.optString("msg", ""));
            return message.isEmpty() ? null : message;
        } catch (JSONException e) {
            return null;
        }
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value == null ? "" : value;
        }
    }

    public static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value == null ? "" : value;
        }
    }
}
