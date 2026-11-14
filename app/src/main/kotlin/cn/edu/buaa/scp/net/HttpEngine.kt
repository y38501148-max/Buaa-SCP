package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.util.DebugDump
import cn.edu.buaa.scp.util.LogBus
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * 轻量 HTTP 客户端：**不自动跟随 302**。
 *
 * CAS 链路必须逐跳观察 Set-Cookie 与 Location，所以跟随逻辑放在 [follow]，
 * 由调用方显式控制跳数与判定。只依赖 java.net，无第三方库。
 */
class HttpEngine(val jar: CookieJar = CookieJar()) {

    /** 一次文本响应快照。 */
    class Response(
        val status: Int,
        val url: String,
        val body: String,
        private val responseHeaders: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = responseHeaders.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()

        fun headers(name: String): List<String> = responseHeaders.entries
            .filter { it.key.equals(name, ignoreCase = true) }
            .flatMap { it.value }

        val location: String? get() = header("Location")

        val isRedirect: Boolean get() = status in REDIRECT_CODES

        override fun toString(): String = "$status $url"
    }

    /** 二进制响应（验证码图片等）。 */
    class BytesResponse(
        val status: Int,
        val url: String,
        val bytes: ByteArray,
        private val responseHeaders: Map<String, List<String>>,
    ) {
        fun header(name: String): String? = responseHeaders.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()

        val contentType: String? get() = header("Content-Type")

        override fun toString(): String = "$status $url (${bytes.size}B)"
    }

    fun get(url: String, extraHeaders: Map<String, String>? = null): Response =
        request("GET", url, extraHeaders, null)

    fun post(url: String, headers: Map<String, String>? = null, body: String? = null): Response =
        request("POST", url, headers, body)

    fun getBytes(url: String, extraHeaders: Map<String, String>? = null): BytesResponse {
        val raw = execute("GET", url, extraHeaders, null)
        log("GET", url, raw)
        return BytesResponse(raw.status, url, raw.bytes, raw.headers)
    }

    /**
     * 执行一次请求。不会跟随重定向；会把响应里的 Set-Cookie 全部吸收进 CookieJar。
     */
    fun request(
        method: String,
        url: String,
        extraHeaders: Map<String, String>? = null,
        body: String? = null,
    ): Response {
        val raw = execute(method, url, extraHeaders, body)
        val text = String(raw.bytes, charsetOf(headerOf(raw.headers, "Content-Type")))
        if (raw.status == 200 && method == "POST") {
            DebugDump.maybeDump("post_" + url.substringAfterLast('/').substringBefore('?'), text)
        }
        log(method, url, raw, text.length)
        return Response(raw.status, url, text, raw.headers)
    }

    /** 逐跳跟随 3xx，返回最后一个非 3xx 响应。 */
    fun follow(start: Response, maxHops: Int): Response {
        var current = start
        var hop = 0
        while (hop < maxHops && current.isRedirect) {
            val location = current.location ?: break
            if (location.isEmpty()) break
            current = get(resolve(current.url, location))
            hop++
        }
        return current
    }

    private class Raw(
        val status: Int,
        val bytes: ByteArray,
        val headers: Map<String, List<String>>,
        val costMs: Long,
    )

    private fun execute(
        method: String,
        url: String,
        extraHeaders: Map<String, String>?,
        body: String?,
    ): Raw {
        val started = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            connection.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9," +
                        "image/png,image/*;q=0.8,*/*;q=0.7",
            )
            connection.setRequestProperty("Accept-Encoding", "identity")

            jar.header(url)?.let { connection.setRequestProperty("Cookie", it) }
            extraHeaders?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            if (body != null) {
                val payload = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }

            val status = connection.responseCode
            val headers = LinkedHashMap<String, List<String>>()
            connection.headerFields.forEach { (key, values) ->
                if (key != null) {
                    headers[key] = values?.toList().orEmpty()
                }
            }

            jar.absorb(url, headers.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value })

            val bytes = readBody(connection, status, headers)
            return Raw(status, bytes, headers, System.currentTimeMillis() - started)
        } finally {
            connection.disconnect()
        }
    }

    private fun log(method: String, url: String, raw: Raw, textLength: Int? = null) {
        val size = textLength ?: raw.bytes.size
        val builder = StringBuilder()
            .append(method).append(' ').append(shortUrl(url))
            .append(" -> ").append(raw.status)
            .append(" (").append(raw.costMs).append("ms, ").append(size).append("B)")
        val location = headerOf(raw.headers, "Location")
        if (location != null && raw.status in REDIRECT_CODES) {
            builder.append(" => ").append(shortUrl(location))
        }
        LogBus.net(builder.toString())
    }

    private fun readBody(
        connection: HttpURLConnection,
        status: Int,
        headers: Map<String, List<String>>,
    ): ByteArray {
        val raw: InputStream = if (status >= 400) {
            connection.errorStream ?: return ByteArray(0)
        } else {
            connection.inputStream ?: return ByteArray(0)
        }
        val encoding = headerOf(headers, "Content-Encoding")
        val stream: InputStream = if (encoding != null &&
            encoding.lowercase(Locale.US).contains("gzip")
        ) {
            GZIPInputStream(raw)
        } else {
            raw
        }
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BODY_BYTES) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    companion object {

        /** 与掌上服务大厅的前端 UA 保持一致，避免被风控策略区别对待。 */
        const val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
                "MicroMessenger/8.0.49(0x18003129) NetType/WIFI Language/zh_CN"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun resolve(base: String, location: String): String = runCatching {
            URL(URL(base), location).toString()
        }.getOrDefault(location)

        private fun charsetOf(contentType: String?): Charset {
            if (contentType != null) {
                val lower = contentType.lowercase(Locale.US)
                val index = lower.indexOf("charset=")
                if (index >= 0) {
                    var name = lower.substring(index + 8).trim()
                    val semi = name.indexOf(';')
                    if (semi >= 0) name = name.substring(0, semi)
                    name = name.replace("\"", "").trim()
                    runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
                }
            }
            return Charsets.UTF_8
        }

        private fun headerOf(headers: Map<String, List<String>>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()

        /** 日志里只保留 host + path，去掉可能含 openid / ticket / 学号 的查询串。 */
        fun shortUrl(url: String?): String {
            if (url == null) return "null"
            val query = url.indexOf('?')
            var clean = if (query >= 0) url.substring(0, query) else url
            val scheme = clean.indexOf("://")
            if (scheme >= 0) clean = clean.substring(scheme + 3)
            return clean
        }

        fun headers(vararg keyValues: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            var index = 0
            while (index + 1 < keyValues.size) {
                map[keyValues[index]] = keyValues[index + 1]
                index += 2
            }
            return map
        }
    }
}
