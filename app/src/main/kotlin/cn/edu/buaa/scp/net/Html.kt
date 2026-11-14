package cn.edu.buaa.scp.net

import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * CAS / 校园卡的纯文本解析工具。不依赖 Android，可直接在 JVM 单元测试中覆盖。
 */
object Html {

    private val INPUT_TAG = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val CAPTCHA_CONFIG = Regex("""config\.captcha\s*=\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_JSON = Regex("""["']captcha["']\s*:\s*\{([^}]*)\}""", RegexOption.IGNORE_CASE)
    private val CAPTCHA_URL = Regex("""captchaId=([A-Za-z0-9_\-]{1,4096})""")

    /** CAS 登录页下发的图形验证码配置：`config.captcha = { type: 'image', id: '…' }`。 */
    data class Captcha(val type: String, val id: String)

    /**
     * 从 CAS 登录页提取验证码。仅在服务端确实下发验证码时返回非空。
     *
     * 已证实的形状是页面里的内联脚本：
     * `<script>config.captcha = { type: 'image', id: '…' };</script>`。
     * 另外兼容 JSON 形式与直接出现 `captchaId=…` 的图片地址。
     */
    fun captcha(html: String?): Captcha? {
        if (html.isNullOrEmpty()) return null
        CAPTCHA_CONFIG.find(html)?.let { return parseCaptchaObject(it.groupValues[1]) }
        CAPTCHA_JSON.find(html)?.let { return parseCaptchaObject(it.groupValues[1]) }
        CAPTCHA_URL.find(html)?.let {
            return Captcha("image", it.groupValues[1])
        }
        return null
    }

    private fun parseCaptchaObject(body: String): Captcha? {
        val id = scriptValue(body, "id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        val type = scriptValue(body, "type")?.trim().orEmpty().ifEmpty { "image" }
        return Captcha(type.lowercase(), id)
    }

    private fun scriptValue(body: String, name: String): String? {
        val pattern = Regex(
            """\b${Regex.escape(name)}['"]?\s*:\s*(['"])([^'"]*)\1""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(body)?.groupValues?.get(2)
    }

    /**
     * 取 &lt;input&gt; 的 value。CAS 表单的 input 属性顺序不固定（name 可能在 value 之后），
     * 所以先抓整段标签，再逐个属性解析。
     */
    fun hiddenInputValue(html: String?, name: String?): String? {
        if (html == null || name == null) return null
        return INPUT_TAG.findAll(html)
            .map { it.value }
            .firstOrNull { attr(it, "name")?.equals(name, ignoreCase = true) == true }
            ?.let { attr(it, "value") }
            ?.let { unescape(it) }
    }

    /** 取标签里某个属性的值（支持双引号 / 单引号）。 */
    fun attr(tag: String?, attribute: String?): String? {
        if (tag == null || attribute == null) return null
        val pattern = Regex(
            "\\b${Regex.escape(attribute)}\\s*=\\s*(\"([^\"]*)\"|'([^']*)')",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(tag) ?: return null
        return match.groupValues[2].ifEmpty { match.groupValues[3] }
    }

    /**
     * 从 CAS 响应页提取可读错误。北航 CAS 的提示多为英文（Invalid credentials.），
     * 中文关键字一并覆盖。验证码是否出现由 [captcha] 单独判断，这里只返回错误文案。
     */
    fun errorMessage(html: String?): String? {
        if (html.isNullOrEmpty()) return null
        val text = stripTags(html)
        val lower = text.lowercase()

        val credentialsWrong = lower.contains("invalid credentials") ||
                lower.contains("bad credentials") ||
                text.contains("用户名或密码") ||
                text.contains("账号或密码") ||
                text.contains("账户或密码") ||
                text.contains("密码错误") ||
                text.contains("密码不正确") ||
                text.contains("无效的凭据") ||
                text.contains("认证信息无效")
        if (credentialsWrong) {
            return "账号或密码错误，请检查学号与统一身份认证密码"
        }
        if (lower.contains("account locked") || text.contains("已锁定") || text.contains("锁定")) {
            return "账号已被临时锁定，请稍后再试"
        }
        if (lower.contains("disabled") || text.contains("已禁用")) return "账号已被禁用"
        if (lower.contains("expired") || text.contains("已过期")) {
            return "密码或登录态已过期，请重新登录"
        }
        if (lower.contains("too many") || text.contains("频繁")) return "尝试过于频繁，请稍后再试"
        return null
    }

    fun stripTags(html: String?): String {
        if (html == null) return ""
        val cleaned = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
        return unescape(cleaned).replace(Regex("\\s+"), " ").trim()
    }

    fun unescape(text: String?): String {
        if (text == null) return ""
        return text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&amp;", "&")
    }

    /** 取 URL 查询串中的参数（已 URL 解码）；fragment 里的查询串同样适用。 */
    fun queryParam(url: String?, name: String?): String? {
        if (url == null || name == null) return null
        val match = Regex("[?&]${Regex.escape(name)}=([^&#]+)").find(url) ?: return null
        return urlDecode(match.groupValues[1])
    }

    /** 失败页形如 /card/?isError=ERROR#/pages/common/fail/fail?data=%7B...%7D。 */
    fun failPageMessage(location: String?): String? {
        val data = queryParam(location, "data") ?: return null
        return runCatching {
            val json = JSONObject(data)
            val message = json.optString("message").ifBlank { json.optString("title") }
            message.ifBlank { null }
        }.getOrNull()
    }

    /** 响应体若是 JSON，取其 message / msg。 */
    fun jsonMessage(body: String?): String? {
        val trimmed = body?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            val message = json.optString("message").ifBlank { json.optString("msg") }
            message.ifBlank { null }
        } catch (e: JSONException) {
            null
        }
    }

    fun urlEncode(value: String?): String = runCatching {
        URLEncoder.encode(value.orEmpty(), "UTF-8")
    }.getOrDefault(value.orEmpty())

    fun urlDecode(value: String?): String = runCatching {
        URLDecoder.decode(value.orEmpty(), "UTF-8")
    }.getOrDefault(value.orEmpty())
}
