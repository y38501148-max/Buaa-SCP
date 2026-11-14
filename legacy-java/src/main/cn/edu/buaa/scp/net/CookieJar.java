package cn.edu.buaa.scp.net;

import java.net.URI;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 手写 Cookie 存储。
 *
 * <p>CAS 链路必须跨多个域（sso.buaa.edu.cn / m.icw.buaa.edu.cn）逐跳保存会话，
 * 且需要把 CASTGC、JSESSIONID 持久化到本机，因此不用 WebView 的 CookieManager，
 * 而是自行维护一张极简 Cookie 表（RFC 6265 的核心子集）。
 */
public final class CookieJar {

    private static final String[] DATE_PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
    };

    private static final class Cookie {
        final String name;
        final String value;
        final String domain;
        final String path;
        final long expiresAt;   // epoch millis；Long.MAX_VALUE 表示会话级
        final boolean secure;

        Cookie(String name, String value, String domain, String path, long expiresAt, boolean secure) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.expiresAt = expiresAt;
            this.secure = secure;
        }

        boolean expired(long now) {
            return expiresAt != Long.MAX_VALUE && expiresAt <= now;
        }
    }

    private final List<Cookie> cookies = new ArrayList<>();

    /** 吸收一次响应里的全部 Set-Cookie 头。 */
    public synchronized void absorb(String url, List<String> setCookieHeaders) {
        if (setCookieHeaders == null || setCookieHeaders.isEmpty()) {
            return;
        }
        String host = hostOf(url);
        boolean https = url != null && url.startsWith("https");
        if (host == null) {
            return;
        }
        for (String raw : setCookieHeaders) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            parseOne(raw, host, https);
        }
        purgeExpired();
    }

    private void parseOne(String raw, String defaultDomain, boolean https) {
        String[] parts = raw.split(";");
        if (parts.length == 0) {
            return;
        }
        String pair = parts[0];
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty()) {
            return;
        }

        String domain = null;
        String path = null;
        long expiresAt = Long.MAX_VALUE;
        boolean secure = false;
        boolean delete = false;

        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            if (attr.isEmpty()) {
                continue;
            }
            int idx = attr.indexOf('=');
            String key = (idx < 0 ? attr : attr.substring(0, idx)).trim().toLowerCase(Locale.US);
            String val = idx < 0 ? "" : attr.substring(idx + 1).trim();
            switch (key) {
                case "domain":
                    if (!val.isEmpty()) {
                        domain = val.startsWith(".") ? val.substring(1) : val;
                    }
                    break;
                case "path":
                    if (val.startsWith("/")) {
                        path = val;
                    }
                    break;
                case "max-age":
                    try {
                        long seconds = Long.parseLong(val);
                        if (seconds <= 0) {
                            delete = true;
                        } else {
                            expiresAt = System.currentTimeMillis() + seconds * 1000L;
                        }
                    } catch (NumberFormatException ignored) {
                        // 忽略非法 Max-Age
                    }
                    break;
                case "expires":
                    Long t = parseDate(val);
                    if (t != null) {
                        expiresAt = t;
                        if (t <= System.currentTimeMillis()) {
                            delete = true;
                        }
                    }
                    break;
                case "secure":
                    secure = true;
                    break;
                default:
                    break;
            }
        }

        String d = (domain == null ? defaultDomain : domain).toLowerCase(Locale.US);
        String p = path == null ? defaultPath(defaultDomain) : path;
        removeSame(d, name, p);
        if (delete) {
            return;
        }
        cookies.add(new Cookie(name, value, d, p, expiresAt, secure));
    }

    private static String defaultPath(String host) {
        return "/";
    }

    /** 生成请求要带的 Cookie 头；无匹配时返回 null。 */
    public synchronized String header(String url) {
        String host = hostOf(url);
        String path = pathOf(url);
        boolean https = url != null && url.startsWith("https");
        if (host == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Cookie c : cookies) {
            if (c.expired(now) || (c.secure && !https)) {
                continue;
            }
            if (!domainMatches(host, c.domain) || !pathMatches(path, c.path)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(c.name).append('=').append(c.value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 取某域名下的某个 Cookie 值（域名按后缀匹配）。 */
    public synchronized String valueOf(String domain, String name) {
        if (domain == null || name == null) {
            return null;
        }
        String d = domain.toLowerCase(Locale.US);
        for (Cookie c : cookies) {
            if (c.name.equals(name) && domainMatches(d, c.domain)) {
                return c.value;
            }
        }
        return null;
    }

    /** 手工塞入 Cookie（用于从本地存储恢复会话）。 */
    public synchronized void put(String domain, String name, String value) {
        if (domain == null || name == null || value == null || value.isEmpty()) {
            return;
        }
        String d = domain.toLowerCase(Locale.US);
        removeSame(d, name, "/");
        cookies.add(new Cookie(name, value, d, "/", Long.MAX_VALUE, true));
    }

    public synchronized void clearDomain(String domain) {
        if (domain == null) {
            return;
        }
        String d = domain.toLowerCase(Locale.US);
        cookies.removeIf(c -> domainMatches(d, c.domain));
    }

    public synchronized void clear() {
        cookies.clear();
    }

    public synchronized boolean isEmpty() {
        return cookies.isEmpty();
    }

    public synchronized int size() {
        return cookies.size();
    }

    private void removeSame(String domain, String name, String path) {
        cookies.removeIf(c -> c.name.equals(name) && c.domain.equals(domain) && c.path.equals(path));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cookies.removeIf(c -> c.expired(now));
    }

    static boolean domainMatches(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    static boolean pathMatches(String requestPath, String cookiePath) {
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }
        if (cookiePath == null || cookiePath.isEmpty()) {
            cookiePath = "/";
        }
        if (requestPath.equals(cookiePath)) {
            return true;
        }
        String prefix = cookiePath.endsWith("/") ? cookiePath : cookiePath + "/";
        return requestPath.startsWith(prefix);
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.US);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (RuntimeException e) {
            return "/";
        }
    }

    static Long parseDate(String value) {
        for (String pattern : DATE_PATTERNS) {
            SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.US);
            fmt.setLenient(false);
            ParsePosition pos = new ParsePosition(0);
            Date date = fmt.parse(value, pos);
            if (date != null) {
                return date.getTime();
            }
        }
        return null;
    }
}
