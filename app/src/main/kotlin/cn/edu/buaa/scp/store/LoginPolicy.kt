package cn.edu.buaa.scp.store

/**
 * 「记住密码 / 自动登录」的纯判定逻辑，便于单元测试。
 *
 * 规则：
 * 1. 只有同时开启「记住密码」「自动登录」，且本地确实有密码时才自动提交；
 * 2. 服务端明确返回账号/密码错误时，自动登录必须停止并清除保存的密码，
 *    避免每次启动都带着错误密码重试，累计失败次数触发账号锁定；
 * 3. 验证码、短信、账号锁定等需要用户本人介入的场景，只关闭自动登录，保留密码。
 */
object LoginPolicy {

    fun canAutoLogin(
        rememberPassword: Boolean,
        autoLogin: Boolean,
        account: String,
        password: String?,
    ): Boolean = rememberPassword && autoLogin &&
            account.isNotBlank() && !password.isNullOrBlank()

    /** 服务端明确判定账号或密码错误（含「用户名或密码错误」等文案）。 */
    fun passwordRejected(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return REJECTED_MARKERS.any { text.contains(it) }
    }

    /** 需要用户本人完成验证（图形/短信验证码）或账号被临时锁定。 */
    fun requiresInteraction(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        return INTERACTION_MARKERS.any { text.contains(it) }
    }

    /** 仅在「确认是密码错误」且没有验证码/锁定等干扰时清除保存的密码。 */
    fun shouldForgetPassword(message: String, needsInteraction: Boolean): Boolean =
        !needsInteraction && passwordRejected(message)

    private val REJECTED_MARKERS = listOf(
        "密码错误",
        "密码不正确",
        "用户名或密码",
        "账号或密码",
        "学号或密码",
        "账号、密码",
    )

    private val INTERACTION_MARKERS = listOf(
        "验证码",
        "短信",
        "锁定",
        "尝试过多",
    )
}
