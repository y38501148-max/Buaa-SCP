package cn.edu.buaa.scp.net

/** 充值入口失败的原因分类。 */
enum class RechargeFailureKind {
    /** 登录态失效，需要重新登录。 */
    SESSION_EXPIRED,

    /** 会话正常，只是当前不在校园卡缴费时段。 */
    NOT_IN_WINDOW,

    /** 其它业务/未知失败。 */
    OTHER,
}

/** 分类结果：kind 决定提示语与后续动作，message 可直接展示。 */
data class RechargeFailure(
    val kind: RechargeFailureKind,
    val message: String,
)

/**
 * 校园卡充值失败原因的分类器（纯 JVM，便于单元测试）。
 *
 * 官方充值页（uni-app）失败时会跳到
 * `/pages/common/fail/fail?data={"title":"页面错误","message":"不在缴费期间"}`，
 * HTTP 401/403 则跳到 `/pages/common/error/error?message=未认证的请求，请认证后访问`。
 * 两条链路都不是「会话失效」，必须按 message 文本区分，避免把「不在缴费时段」
 * 误报成登录失效。
 */
object RechargeFailureParser {

    const val NOT_IN_WINDOW_MESSAGE = "当前不在校园卡缴费时段，充值入口暂未开放"
    const val SESSION_EXPIRED_MESSAGE = "登录已失效，请返回重新登录后再试"
    const val OTHER_MESSAGE = "充值入口暂时不可用"

    private val NOT_IN_WINDOW_KEYS = listOf(
        "不在缴费",
        "缴费期间",
        "缴费时段",
        "不在充值",
        "非缴费时段",
    )

    private val SESSION_EXPIRED_KEYS = listOf(
        "会话失效",
        "会话已失效",
        "会话过期",
        "登录过期",
        "登录已过期",
        "登录已失效",
        "未认证",
        "认证失败",
        "请重新登录",
        "重新访问",
        "资源受限",
        "session expired",
        "login expired",
        "unauthorized",
        "not logged in",
    )

    private val FAIL_PAGE_MARKERS = listOf(
        "/pages/common/fail/fail",
        "/pages/common/error/error",
    )

    /** 按官方返回的 message 文本判定失败原因。 */
    fun classify(raw: String?): RechargeFailure {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return RechargeFailure(RechargeFailureKind.OTHER, OTHER_MESSAGE)
        val lower = text.lowercase()
        if (NOT_IN_WINDOW_KEYS.any { lower.contains(it.lowercase()) }) {
            return RechargeFailure(RechargeFailureKind.NOT_IN_WINDOW, NOT_IN_WINDOW_MESSAGE)
        }
        if (SESSION_EXPIRED_KEYS.any { lower.contains(it.lowercase()) }) {
            return RechargeFailure(RechargeFailureKind.SESSION_EXPIRED, SESSION_EXPIRED_MESSAGE)
        }
        return RechargeFailure(RechargeFailureKind.OTHER, "$OTHER_MESSAGE：$text")
    }

    /**
     * 从 WebView 的页面 URL 解析失败原因；不是官方失败页时返回 null。
     *
     * 失败页的 message 可能放在 fragment 里的 `?data=<urlencoded JSON>`（fail 页），
     * 也可能直接是 fragment 里的 `?message=<urlencoded 文本>`（error 页），
     * 两者都交给 [Html] 解码后再分类。
     */
    fun fromPageUrl(url: String?): RechargeFailure? {
        val location = url?.trim().orEmpty()
        if (location.isEmpty()) return null
        val lower = location.lowercase()
        if (FAIL_PAGE_MARKERS.none { lower.contains(it) }) return null
        val message = Html.failPageMessage(location) ?: Html.queryParam(location, "message")
        return classify(message)
    }

    /** openCardPay 预检异常分类；成功（error == null）返回 null。 */
    fun fromOpenCardPay(error: ApiException?): RechargeFailure? {
        if (error == null) return null
        if (error.authExpired) {
            return RechargeFailure(RechargeFailureKind.SESSION_EXPIRED, SESSION_EXPIRED_MESSAGE)
        }
        return classify(error.message)
    }
}
