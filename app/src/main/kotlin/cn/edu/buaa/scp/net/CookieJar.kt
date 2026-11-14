package cn.edu.buaa.scp.net

import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 手写 Cookie 存储。
 *
 * CAS 链路必须跨多个域（sso.buaa.edu.cn / m.icw.buaa.edu.cn）逐跳保存会话，
 * 且需要把 CASTGC、JSESSIONID 持久化到本机，因此不用 WebView 的 CookieManager，
 * 而是自行维护一张极简 Cookie 表（RFC 6265 的核心子集）。
 */
class CookieJar {

    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long, // epoch millis；Long.MAX_VALUE 表示会话级
        val secure: Boolean,
    ) {
        fun expired(now: Long): Boolean = expiresAt != Long.MAX_VALUE && expiresAt <= now
    }

    private val cookies = ArrayList<StoredCookie>()

    /** 吸收一次响应里的全部 Set-Cookie 头。 */
    @Synchronized
    fun absorb(url: String?, setCookieHeaders: List<String>?) {
        if (url == null || setCookieHeaders.isNullOrEmpty()) return
        val host = hostOf(url) ?: return
        setCookieHeaders.filter { it.isNotBlank() }.forEach { raw -> parseOne(raw, host) }
        purgeExpired()
    }

    private fun parseOne(raw: String, defaultDomain: String) {
        val parts = raw.split(";")
        val pair = parts.firstOrNull().orEmpty()
        val eq = pair.indexOf('=')
        if (eq <= 0) return
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty()) return

        var domain: String? = null
        var path: String? = null
        var expiresAt = Long.MAX_VALUE
        var secure = false
        var delete = false

        for (index in 1 until parts.size) {
            val attr = parts[index].trim()
            if (attr.isEmpty()) continue
            val separator = attr.indexOf('=')
            val key = (if (separator < 0) attr else attr.substring(0, separator))
                .trim().lowercase(Locale.US)
            val rawValue = if (separator < 0) "" else attr.substring(separator + 1).trim()
            when (key) {
                "domain" -> if (rawValue.isNotEmpty()) {
                    domain = rawValue.removePrefix(".")
                }

                "path" -> if (rawValue.startsWith("/")) {
                    path = rawValue
                }

                "max-age" -> {
                    val seconds = rawValue.toLongOrNull()
                    if (seconds != null) {
                        if (seconds <= 0) {
                            delete = true
                        } else {
                            expiresAt = System.currentTimeMillis() + seconds * 1000L
                        }
                    }
                }

                "expires" -> {
                    val time = parseDate(rawValue)
                    if (time != null) {
                        expiresAt = time
                        if (time <= System.currentTimeMillis()) delete = true
                    }
                }

                "secure" -> secure = true
            }
        }

        val cookieDomain = (domain ?: defaultDomain).lowercase(Locale.US)
        val cookiePath = path ?: "/"
        removeSame(cookieDomain, name, cookiePath)
        if (delete) return
        cookies.add(StoredCookie(name, value, cookieDomain, cookiePath, expiresAt, secure))
    }

    /** 生成请求要带的 Cookie 头；无匹配时返回 null。 */
    @Synchronized
    fun header(url: String?): String? {
        if (url == null) return null
        val host = hostOf(url) ?: return null
        val path = pathOf(url)
        val https = url.startsWith("https")
        val now = System.currentTimeMillis()
        val builder = StringBuilder()
        for (cookie in cookies) {
            if (cookie.expired(now)) continue
            if (cookie.secure && !https) continue
            if (!domainMatches(host, cookie.domain)) continue
            if (!pathMatches(path, cookie.path)) continue
            if (builder.isNotEmpty()) builder.append("; ")
            builder.append(cookie.name).append('=').append(cookie.value)
        }
        return if (builder.isEmpty()) null else builder.toString()
    }

    /** 取某域名下的某个 Cookie 值（域名按后缀匹配）。 */
    @Synchronized
    fun valueOf(domain: String?, name: String?): String? {
        if (domain == null || name == null) return null
        val target = domain.lowercase(Locale.US)
        return cookies.firstOrNull {
            it.name == name && domainMatches(target, it.domain)
        }?.value
    }

    /** 手工塞入 Cookie（用于从本地存储恢复会话）。 */
    @Synchronized
    fun put(domain: String?, name: String?, value: String?) {
        if (domain == null || name == null || value.isNullOrEmpty()) return
        val target = domain.lowercase(Locale.US)
        removeSame(target, name, "/")
        cookies.add(StoredCookie(name, value, target, "/", Long.MAX_VALUE, secure = true))
    }

    @Synchronized
    fun clearDomain(domain: String?) {
        if (domain == null) return
        val target = domain.lowercase(Locale.US)
        cookies.removeAll { domainMatches(target, it.domain) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
    }

    @Synchronized
    fun isEmpty(): Boolean = cookies.isEmpty()

    @Synchronized
    fun size(): Int = cookies.size

    private fun removeSame(domain: String, name: String, path: String) {
        cookies.removeAll { it.name == name && it.domain == domain && it.path == path }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expired(now) }
    }

    companion object {

        private val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd-MMM-yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
        )

        internal fun domainMatches(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        internal fun pathMatches(requestPath: String?, cookiePath: String?): Boolean {
            val request = if (requestPath.isNullOrEmpty()) "/" else requestPath
            val cookie = if (cookiePath.isNullOrEmpty()) "/" else cookiePath
            if (request == cookie) return true
            val prefix = if (cookie.endsWith("/")) cookie else "$cookie/"
            return request.startsWith(prefix)
        }

        internal fun hostOf(url: String?): String? = runCatching {
            URI.create(url).host?.lowercase(Locale.US)
        }.getOrNull()

        internal fun pathOf(url: String?): String = runCatching {
            val path = URI.create(url).path
            if (path.isNullOrEmpty()) "/" else path
        }.getOrDefault("/")

        internal fun parseDate(value: String): Long? {
            for (pattern in DATE_PATTERNS) {
                val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val position = ParsePosition(0)
                val date = format.parse(value, position) ?: continue
                return date.time
            }
            return null
        }
    }
}
