package cn.edu.buaa.scp.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import cn.edu.buaa.scp.util.LogBus;

/**
 * 轻量 HTTP 客户端：<b>不自动跟随 302</b>。CAS 链路必须逐跳观察 Set-Cookie 与 Location，
 * 所以跟随逻辑放在 {@link #follow}，由调用方显式控制跳数与判定。
 *
 * <p>只依赖 java.net，无第三方库，便于在 JVM 单元测试里直接复用。
 */
public final class HttpEngine {

    /** 与掌上服务大厅的前端 UA 保持一致，避免被风控策略区别对待。 */
    public static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 "
            + "MicroMessenger/8.0.49(0x18003129) NetType/WIFI Language/zh_CN";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    private final CookieJar jar;

    public HttpEngine(CookieJar jar) {
        this.jar = jar == null ? new CookieJar() : jar;
    }

    public CookieJar jar() {
        return jar;
    }

    /** 一次 HTTP 响应快照。 */
    public static final class Response {
        public final int status;
        public final String url;
        public final String body;
        private final Map<String, List<String>> headers;

        Response(int status, String url, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.url = url;
            this.body = body == null ? "" : body;
            this.headers = headers == null ? Collections.emptyMap() : headers;
        }

        public String header(String name) {
            List<String> values = headers(name);
            return values.isEmpty() ? null : values.get(0);
        }

        public List<String> headers(String name) {
            List<String> out = new ArrayList<>();
            if (name == null) {
                return out;
            }
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && entry.getValue() != null) {
                    out.addAll(entry.getValue());
                }
            }
            return out;
        }

        public String location() {
            return header("Location");
        }

        public boolean isRedirect() {
            return status == 301 || status == 302 || status == 303
                    || status == 307 || status == 308;
        }

        @Override
        public String toString() {
            return status + " " + (url == null ? "" : url);
        }
    }

    public Response get(String url) throws IOException {
        return request("GET", url, null, null);
    }

    public Response get(String url, Map<String, String> headers) throws IOException {
        return request("GET", url, headers, null);
    }

    public Response post(String url, Map<String, String> headers, String body) throws IOException {
        return request("POST", url, headers, body);
    }

    /**
     * 执行一次请求。不会跟随重定向；会把响应里的 Set-Cookie 全部吸收进 CookieJar。
     */
    public Response request(String method, String url, Map<String, String> extraHeaders, String body)
            throws IOException {
        HttpURLConnection conn = null;
        long started = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,"
                            + "application/json;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "identity");

            String cookieHeader = jar.header(url);
            if (cookieHeader != null) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }

            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = conn.getResponseCode();
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                headers.put(entry.getKey(), entry.getValue() == null
                        ? new ArrayList<>() : new ArrayList<>(entry.getValue()));
            }

            jar.absorb(url, collect(headers, "Set-Cookie"));
            String text = readBody(conn, status, headers);

            long cost = System.currentTimeMillis() - started;
            StringBuilder log = new StringBuilder()
                    .append(method).append(' ').append(shortUrl(url))
                    .append(" -> ").append(status).append(" (").append(cost).append("ms, ")
                    .append(text.length()).append("B)");
            String location = headerOf(headers, "Location");
            if (location != null && status >= 300 && status < 400) {
                log.append(" => ").append(shortUrl(location));
            }
            LogBus.net(log.toString());
            return new Response(status, url, text, headers);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 逐跳跟随 3xx，返回最后一个非 3xx 响应。 */
    public Response follow(Response start, int maxHops) throws IOException {
        Response current = start;
        for (int hop = 0; hop < maxHops && current.isRedirect(); hop++) {
            String location = current.location();
            if (location == null || location.isEmpty()) {
                break;
            }
            current = get(resolve(current.url, location));
        }
        return current;
    }

    public static String resolve(String base, String location) {
        try {
            return new URL(new URL(base), location).toString();
        } catch (IOException e) {
            return location;
        }
    }

    private static String readBody(HttpURLConnection conn, int status,
                                   Map<String, List<String>> headers) throws IOException {
        InputStream stream;
        if (status >= 400) {
            stream = conn.getErrorStream();
        } else {
            stream = conn.getInputStream();
        }
        if (stream == null) {
            return "";
        }
        String encoding = headerOf(headers, "Content-Encoding");
        if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
            stream = new GZIPInputStream(stream);
        }
        Charset charset = charsetOf(headerOf(headers, "Content-Type"));
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), charset);
        }
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.US);
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String name = lower.substring(idx + 8).trim();
                int semi = name.indexOf(';');
                if (semi >= 0) {
                    name = name.substring(0, semi);
                }
                name = name.replace("\"", "").trim();
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // 落到 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static List<String> collect(Map<String, List<String>> headers, String name) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null) {
                out.addAll(entry.getValue());
            }
        }
        return out;
    }

    private static String headerOf(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    /** 日志里只保留 host + path，去掉可能含 openid / ticket / 学号 的查询串。 */
    public static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        int query = url.indexOf('?');
        String clean = query >= 0 ? url.substring(0, query) : url;
        int scheme = clean.indexOf("://");
        if (scheme >= 0) {
            clean = clean.substring(scheme + 3);
        }
        return clean;
    }

    public static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
