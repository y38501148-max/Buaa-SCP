package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 北航统一身份认证（CAS）→ 校园卡掌上服务大厅业务会话。
 *
 * 依据实测链路：
 * 1. 回调路径里的 openid 位置必须填**哨兵值 [SENTINEL_OPENID]**；
 * 2. CAS 票据一次性，业务接口只认 `JSESSIONID`；
 * 3. `CASTGC` 可复用，重启后免密换票；
 * 4. 密码错误时 CAS 返回 200 登录页（含错误提示）或 401；
 * 5. 登录失败次数过多时 CAS 会下发图形验证码（`config.captcha`），
 *    必须带同一个 execution 与验证码重新提交；
 * 6. 业务接口切勿再附加 openid 参数，否则可能触发 401。
 */
class CasAuth(private val http: HttpEngine) {

    /**
     * 一次待提交的 CAS 登录页快照。
     *
     * CAS 的 execution 一次性；失败页会带上新的 execution 与新的验证码 id，
     * 因此必须把失败页原样交回 [login]，否则用户输入的验证码会作废。
     */
    data class LoginPage(
        val execution: String,
        val captcha: Html.Captcha? = null,
    ) {
        val hasCaptcha: Boolean get() = captcha != null
    }

    /** 登录结果。失败时 message 可直接展示给用户。 */
    sealed interface Result {
        data class Success(
            val openid: String,
            val castgc: String,
            val jsessionid: String,
        ) : Result

        data class Failure(
            val message: String,
            val needPassword: Boolean = true,
            /** 非空表示 CAS 返回了可继续提交的登录页（可能带验证码）。 */
            val page: LoginPage? = null,
        ) : Result
    }

    /**
     * 用已有 CASTGC 直接换票据 → 新 JSESSIONID（免密续期）。
     * 返回 [Result.Failure] 且 needPassword=true 表示 CASTGC 已失效。
     */
    suspend fun restore(): Result = withContext(Dispatchers.IO) {
        LogBus.step("会话续期：携带 CASTGC 请求 CAS 授权")
        val response = http.get(authUrl(true))
        if (response.isRedirect) {
            val location = response.location
            if (location != null && location.contains("ticket=")) {
                return@withContext consumeTicket(location)
            }
            LogBus.step("CAS 未签发票据（${HttpEngine.shortUrl(location)}），需重新登录")
            return@withContext Result.Failure("登录态已过期，请重新输入密码")
        }
        LogBus.step("CAS 返回登录页（HTTP ${response.status}），CASTGC 已失效")
        Result.Failure("登录态已过期，请重新输入密码")
    }

    /**
     * 账号密码登录：GET 登录页 → POST 凭据 → 跟随回调换业务会话。
     *
     * @param page 上一次失败返回的登录页（带 execution 与验证码 id）；为 null 时重新获取。
     * @param captcha 用户输入的图形验证码；页面未要求验证码时忽略。
     */
    suspend fun login(
        studentId: String,
        password: String,
        page: LoginPage? = null,
        captcha: String = "",
    ): Result = withContext(Dispatchers.IO) {
        if (studentId.isBlank() || password.isEmpty()) {
            return@withContext Result.Failure("请输入学号与密码")
        }

        var current = page
        if (current == null) {
            LogBus.step("获取 CAS 登录页")
            var response = http.get(authUrl(true))
            if (response.isRedirect) {
                val location = response.location
                if (location != null && location.contains("ticket=")) {
                    LogBus.step("检测到已有登录态，直接换取票据")
                    return@withContext consumeTicket(location)
                }
                response = http.follow(response, 4)
            }
            if (response.status == 423) {
                return@withContext Result.Failure("登录尝试过多，账号已被临时锁定，请稍后再试")
            }
            current = parseLoginPage(response)
                ?: return@withContext Result.Failure(
                    Html.errorMessage(response.body)
                        ?: "未解析到 CAS 登录表单（execution 缺失），页面可能已改版",
                    needPassword = false,
                )
            if (current.hasCaptcha) {
                LogBus.step("CAS 要求图形验证码，等待用户输入")
            }
        }

        current.captcha?.let { captchaInfo ->
            if (captchaInfo.type != "image") {
                return@withContext Result.Failure(
                    "统一身份认证要求「${captchaInfo.type}」验证，当前版本暂不支持",
                    page = current,
                )
            }
            if (captcha.isBlank()) {
                return@withContext Result.Failure("请输入验证码", page = current)
            }
        }

        val form = buildString {
            appendForm(this, "username", studentId)
            appendForm(this, "password", password)
            appendForm(this, "type", "username_password")
            appendForm(this, "execution", current.execution)
            appendForm(this, "_eventId", "submit")
            if (current.hasCaptcha) appendForm(this, "captcha", captcha.trim().lowercase())
        }.removeSuffix("&")

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Referer" to authUrl(true),
            "Origin" to "https://$SSO_HOST",
        )
        LogBus.step("提交 CAS 凭据")
        val post = http.post(authUrl(false), headers, form)

        if (post.isRedirect) {
            val location = post.location
            if (location != null && location.contains("ticket=")) {
                LogBus.step("已取得 CAS 票据（ST），开始回调业务系统")
                return@withContext consumeTicket(location)
            }
            LogBus.warn("CAS 未签发票据，跳转到 ${HttpEngine.shortUrl(location)}")
            return@withContext Result.Failure("认证未通过，未取得票据。请检查账号状态或稍后重试")
        }

        val next = parseLoginPage(post)
        val explicitError = Html.errorMessage(post.body)
        val message = when {
            post.status == 423 -> "登录尝试过多，账号已被临时锁定，请稍后再试"
            post.status == 401 -> explicitError
                ?: "学号或密码错误（HTTP 401）"
            // 403 通常是 WAF / 权限拦截，不等于密码错误；不要据此删除保存的密码。
            post.status == 403 -> explicitError
                ?: "统一身份认证拒绝了请求（HTTP 403），请稍后再试"
            post.status >= 500 -> "统一身份认证服务异常（HTTP ${post.status}），请稍后再试"
            explicitError != null -> explicitError
            next?.hasCaptcha == true -> "验证码错误，请重新输入"
            else -> "登录失败（HTTP ${post.status}），请检查学号与密码"
        }
        LogBus.warn("CAS 登录未通过：$message" + if (next?.hasCaptcha == true) "（已下发新验证码）" else "")
        Result.Failure(message, page = next)
    }

    /** 获取图形验证码图片。必须复用当前 CAS 会话的 Cookie。 */
    suspend fun captchaImage(captchaId: String): ByteArray = withContext(Dispatchers.IO) {
        if (captchaId.isBlank()) throw ApiException("验证码标识为空")
        val url = "https://$SSO_HOST/captcha?captchaId=" + Html.urlEncode(captchaId) +
                "&t=" + System.currentTimeMillis()
        val response = http.getBytes(
            url,
            mapOf(
                "Accept" to "image/png,image/*;q=0.9",
                "Referer" to authUrl(true),
            ),
        )
        if (response.status != 200) {
            throw ApiException("验证码加载失败（HTTP ${response.status}）")
        }
        if (!response.contentType.orEmpty().startsWith("image/", ignoreCase = true)) {
            throw ApiException("验证码响应不是图片")
        }
        if (response.bytes.isEmpty()) throw ApiException("验证码图片为空")
        LogBus.step("已获取验证码图片（${response.bytes.size} 字节）")
        response.bytes
    }

    private fun parseLoginPage(response: HttpEngine.Response): LoginPage? {
        val execution = Html.hiddenInputValue(response.body, "execution")
        if (execution.isNullOrEmpty()) return null
        return LoginPage(execution = execution, captcha = Html.captcha(response.body))
    }

    /** GET 回调地址（带 ticket）→ 302 goIndex?openid=… → 记录 JSESSIONID。 */
    private fun consumeTicket(ticketLocation: String): Result {
        LogBus.step("回调校园卡服务，换取业务会话")
        var response = http.get(ticketLocation)
        var openid = Html.queryParam(ticketLocation, "openid")
        var failure: String? = null

        var hop = 0
        while (hop < 8) {
            val location = response.location
            if (location != null && isFailLocation(location)) {
                failure = Html.failPageMessage(location)
                LogBus.warn("回调被导向失败页：${failure ?: "未知原因"}")
                break
            }
            location?.let {
                Html.queryParam(it, "openid")?.takeIf { value -> value.isNotEmpty() }?.let { value ->
                    openid = value
                }
            }
            Html.queryParam(response.url, "openid")
                ?.takeIf { value -> value.isNotEmpty() }
                ?.let { value -> openid = value }

            if (!response.isRedirect || location.isNullOrEmpty() || location.contains("#")) {
                break
            }
            response = http.get(HttpEngine.resolve(response.url, location))
            hop++
        }

        val jsessionid = http.jar.valueOf(CARD_HOST, "JSESSIONID")
        val hasOpenid = !openid.isNullOrEmpty()

        if (failure == null && !hasOpenid) {
            failure = Html.jsonMessage(response.body)
                ?: if (response.body.contains("用户信息不匹配")) {
                    "用户信息不匹配（回调 openid 位置必须为哨兵值）"
                } else {
                    null
                }
        }
        failure?.let { return Result.Failure(it) }
        if (!hasOpenid) {
            return Result.Failure("回调未返回 openid（票据可能已过期或已被使用）")
        }
        if (jsessionid.isNullOrEmpty()) {
            return Result.Failure("未取得业务会话（JSESSIONID 缺失）")
        }

        LogBus.step(
            "会话已建立：openid=${mask(openid)}，JSESSIONID ${jsessionid.length} 字符",
        )
        return Result.Success(
            openid = openid.orEmpty(),
            castgc = http.jar.valueOf(SSO_HOST, "CASTGC").orEmpty(),
            jsessionid = jsessionid,
        )
    }

    companion object {

        const val SSO_HOST = "sso.buaa.edu.cn"
        const val CARD_HOST = "m.icw.buaa.edu.cn"
        const val SENTINEL_OPENID = "112233"

        /** CAS 回调地址：openid 位置是哨兵值，usertype=6（学生）。 */
        const val SERVICE = "https://$CARD_HOST/card/home/sisUstbMiniReturnPage/$SENTINEL_OPENID/6/auth"

        private const val SSO_LOGIN = "https://$SSO_HOST/login"

        private fun isFailLocation(location: String): Boolean =
            location.contains("isError=ERROR") ||
                    location.contains("pages/common/fail") ||
                    location.contains("/card/errorPage")

        private fun appendForm(builder: StringBuilder, key: String, value: String) {
            builder.append(Html.urlEncode(key)).append('=').append(Html.urlEncode(value)).append('&')
        }

        fun authUrl(noAutoRedirect: Boolean): String =
            "$SSO_LOGIN?service=${Html.urlEncode(SERVICE)}" +
                    if (noAutoRedirect) "&noAutoRedirect=1" else ""

        fun mask(value: String?): String {
            if (value.isNullOrEmpty()) return ""
            if (value.length <= 8) return "${value.first()}***${value.last()}"
            return value.take(4) + "…" + value.takeLast(4)
        }
    }
}
