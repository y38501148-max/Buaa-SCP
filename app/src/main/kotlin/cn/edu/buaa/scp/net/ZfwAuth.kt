package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * 校园网自助服务（深澜 SRun）原生登录。
 *
 * 官方登录页的密码用「本次页面随机下发的 1024-bit RSA 公钥」加密，并且所有 POST
 * 都带 CSRF token；部分账号还会触发短信二次验证。本类只负责协议，不保存密码。
 *
 * 流程：
 * 1. `GET /login` 取 `_csrf-8800`、`#public`（RSA 公钥）；
 * 2. `POST /site/validate-user`（密码 RSA 加密）→ 返回 `inputSms` / `captchaRequired`；
 * 3. 如需短信：`POST /site/validate-smscode`；
 * 4. `POST /login` 提交表单（密码仍需用同一公钥加密）→ 302 `/home`。
 *
 * 登录页每次渲染公钥都不同，所以必须「用哪张页面提交就用哪张页面的公钥」。
 * 任何一步失败都不自动重试，避免累计风控；由用户决定是否改用网页登录兜底。
 */
class ZfwAuth(private val http: HttpEngine) {

    data class LoginPage(
        val csrfToken: String,
        val publicKeyPem: String,
    )

    data class ValidateResult(
        val success: Boolean,
        val inputSms: Boolean,
        val captchaRequired: Boolean,
        val message: String,
        val remainSeconds: Int,
    )

    /** 登录页数据；返回 null 表示当前 Cookie 已经是登录态。 */
    suspend fun loadLoginPage(): LoginPage? = withContext(Dispatchers.IO) {
        val response = http.get(LOGIN_URL)
        if (response.status in REDIRECT_CODES) {
            val location = response.location.orEmpty()
            if (location.contains("/home")) {
                LogBus.step("校园网会话仍有效，跳过原生登录")
                return@withContext null
            }
            throw ApiException("登录页跳转异常（${HttpEngine.shortUrl(location)}）")
        }
        if (response.status != 200) {
            throw ApiException("校园网登录页加载失败（HTTP ${response.status}）")
        }
        if (!response.body.contains("login-form")) {
            // 已登录访问 /login 通常 302；这里兜底处理 200 首页。
            if (response.body.contains("产品信息")) {
                LogBus.step("校园网会话仍有效，跳过原生登录")
                return@withContext null
            }
            throw ApiException("校园网登录页结构异常，请改用网页登录")
        }
        val parsed = parseLoginPageHtml(response.body)
            ?: throw ApiException("登录页缺少 CSRF token 或 RSA 公钥，请改用网页登录")
        LogBus.step("校园网登录页已加载")
        parsed
    }

    /** 图形验证码（需要时）。 */
    suspend fun captchaImage(): ByteArray = withContext(Dispatchers.IO) {
        val response = http.getBytes(
            "$BASE/site/captcha?t=" + System.currentTimeMillis(),
            mapOf("Referer" to LOGIN_URL, "Accept" to "image/png,image/*;q=0.9"),
        )
        if (response.status != 200) throw ApiException("验证码加载失败（HTTP ${response.status}）")
        if (!response.contentType.orEmpty().startsWith("image/", ignoreCase = true)) {
            throw ApiException("验证码响应不是图片")
        }
        if (response.bytes.isEmpty()) throw ApiException("验证码图片为空")
        response.bytes
    }

    /** 校验账号密码；成功后服务端可能要求短信验证。 */
    suspend fun validateUser(
        page: LoginPage,
        username: String,
        password: String,
        verifyCode: String,
    ): ValidateResult = withContext(Dispatchers.IO) {
        val encrypted = encryptPassword(page.publicKeyPem, password)
        val form = formBody(
            "_csrf-8800" to page.csrfToken,
            "LoginForm[username]" to username,
            "LoginForm[password]" to encrypted,
            "LoginForm[verifyCode]" to verifyCode,
        )
        val response = http.post(VALIDATE_USER_URL, ajaxHeaders(page.csrfToken), form)
        val json = parseJson(response.body)
            ?: throw ApiException("账号校验接口异常，请改用网页登录")
        ValidateResult(
            success = json.optBoolean("success", false),
            inputSms = json.optBoolean("inputSms", false),
            captchaRequired = json.optBoolean("captchaRequired", false),
            message = json.optString("message"),
            remainSeconds = json.optInt("remain", 60),
        )
    }

    /** 短信验证码校验；成功后才允许提交登录表单。 */
    suspend fun validateSmsCode(
        page: LoginPage?,
        username: String,
        smsCode: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val form = formBody(
            "uname" to username,
            "code" to smsCode,
        )
        val headers = mutableMapOf(
            "Content-Type" to FORM_CONTENT_TYPE,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to LOGIN_URL,
            "Origin" to BASE,
        )
        page?.let { headers["X-CSRF-Token"] = it.csrfToken }
        val response = http.post(VALIDATE_SMS_URL, headers, form)
        val json = parseJson(response.body)
        val success = json?.optBoolean("success", false) == true
        if (!success) {
            val message = json?.optString("message").orEmpty()
            if (message.isNotEmpty()) LogBus.warn("短信验证码校验失败：$message")
        }
        success
    }

    /** 提交登录表单；成功后 zfw 会话 Cookie 已在 [HttpEngine.jar] 中。 */
    suspend fun submitLogin(
        page: LoginPage,
        username: String,
        password: String,
        verifyCode: String,
        smsCode: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val encrypted = encryptPassword(page.publicKeyPem, password)
        val form = formBody(
            "_csrf-8800" to page.csrfToken,
            "LoginForm[username]" to username,
            "LoginForm[password]" to encrypted,
            "LoginForm[smsCode]" to smsCode,
            "LoginForm[verifyCode]" to verifyCode,
        )
        val headers = mapOf(
            "Content-Type" to FORM_CONTENT_TYPE,
            "Referer" to LOGIN_URL,
            "Origin" to BASE,
        )
        val response = http.post(LOGIN_SUBMIT_URL, headers, form)
        val final = if (response.isRedirect) http.follow(response, 4) else response
        // 登录页本身也会下发 PHPSESSID_8800，不能只看 Cookie 是否存在；
        // 必须再访问 /home 确认真的是已登录态。
        val home = http.get(HOME_URL)
        val failed = home.status in REDIRECT_CODES || ZfwHtml.isLoginPage(home.body)
        if (failed) {
            val message = Html.errorMessage(final.body)
                ?: Html.jsonMessage(final.body)
                ?: if (final.body.contains("login-form")) "登录失败，请检查账号密码或短信验证码" else null
            throw ApiException(message ?: "登录失败，未取得校园网会话")
        }
        LogBus.step("校园网登录成功，会话已建立")
        true
    }

    /** 当前 zfw 域的 Cookie 头，用于持久化。 */
    fun cookieHeader(): String = http.jar.header(HOME_URL).orEmpty()

    // ---------------- 内部 ----------------

    private fun ajaxHeaders(csrf: String): Map<String, String> = mapOf(
        "Content-Type" to FORM_CONTENT_TYPE,
        "X-Requested-With" to "XMLHttpRequest",
        "X-CSRF-Token" to csrf,
        "Referer" to LOGIN_URL,
        "Origin" to BASE,
    )

    private fun formBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            Html.urlEncode(key) + "=" + Html.urlEncode(value)
        }

    private fun parseJson(body: String): JSONObject? = runCatching {
        val text = body.trim()
        if (!text.startsWith("{")) null else JSONObject(text)
    }.getOrNull()

    companion object {
    /** 从官方登录页 HTML 提取 CSRF 与 RSA 公钥；解析失败返回 null。 */
    internal fun parseLoginPageHtml(html: String): LoginPage? {
        val csrf = Regex("""name="_csrf-8800"\s+value="([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta name="csrf-token" content="([^"]+)"""")
                .find(html)?.groupValues?.get(1)
            ?: return null
        val publicKey = Regex("""id="public"\s+value="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.contains("BEGIN PUBLIC KEY") }
            ?: return null
        return LoginPage(csrfToken = csrf, publicKeyPem = publicKey)
    }

        const val BASE = "https://zfw.buaa.edu.cn"
        const val HOST = "zfw.buaa.edu.cn"
        const val LOGIN_URL = "$BASE/login"
        const val HOME_URL = "$BASE/home"
        const val VALIDATE_USER_URL = "$BASE/site/validate-user"
        const val VALIDATE_SMS_URL = "$BASE/site/validate-smscode"
        const val LOGIN_SUBMIT_URL = "$BASE/login"
        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** RSA/ECB/PKCS1Padding，与前端 JSEncrypt 一致。 */
        fun encryptPassword(publicKeyPem: String, password: String): String {
            val base64 = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("""\s+"""), "")
            val keyBytes = Base64.getDecoder().decode(base64)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64.getEncoder().encodeToString(
                cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
            )
        }
    }
}
