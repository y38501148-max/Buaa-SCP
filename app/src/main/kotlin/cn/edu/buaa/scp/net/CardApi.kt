package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 校园卡掌上服务大厅（m.icw.buaa.edu.cn/card）业务接口。
 *
 * 约定：请求固定带 5 个头部；只认 JSESSIONID 会话；响应可能是明文 JSON，
 * 也可能是 `{"datajson": ...}` 的 AES 密文。
 *
 * 读接口（余额 / 账单 / 流水 / 卡务信息）可安全调用；
 * 挂失 / 解挂 / 改密 / 改限额 / 绑解银行卡属于**写操作**，只在用户明确确认后调用，
 * 且不做自动重试，避免重复提交。请求体拼装见 [CardOps]。
 */
class CardApi(private val http: HttpEngine) {

    // ---------------- 会话 / 首页 ----------------

    /** 充值页初始化：同时带回卡号、余额、支付通道状态。最稳定的会话校验接口。 */
    suspend fun openCardPay(): JSONObject = call("/cardpay/openCardPay", JSONObject())

    /**
     * 首页聚合数据（GET）。返回 cardinfo（含 cardbal / accstatusStr）、pcodeCard、通知等。
     * 前端固定会带 openid；失败时上层可回退到 openCardPay。
     */
    suspend fun openHomePageApp(openid: String): JSONObject = withContext(Dispatchers.IO) {
        if (openid.isEmpty()) throw ApiException("缺少 openid，无法加载首页聚合数据")
        val url = "$BASE/home/openHomePageApp?openid=${Html.urlEncode(openid)}" +
                "&usertype=6&ua=${Html.urlEncode(HttpEngine.UA)}"
        unwrap(http.get(url, baseHeaders()), "/home/openHomePageApp")
    }

    // ---------------- 账单 / 流水 ----------------

    /** 月度账单（饼图数据）：month 形如 "2026-11"。 */
    suspend fun monthlyBill(month: String, openid: String = ""): JSONObject {
        val payload = JSONObject().apply {
            put("startdate", month)
            put("enddate", month)
        }
        identity(payload, openid)
        return call("/bill/queryUserMonthlybill", payload)
    }

    /** 近半年月度柱状数据，同时带 totalamt / totalcount 汇总。 */
    suspend fun halfYear(
        startMonth: String,
        endMonth: String,
        dateArray: JSONArray? = null,
        openid: String = "",
    ): JSONObject {
        val payload = JSONObject().apply {
            put("startdate", startMonth)
            put("enddate", endMonth)
            if (dateArray != null) put("dateArray", dateArray)
        }
        identity(payload, openid)
        return call("/bill/queryUserMonthlybillHalfOfYear", payload)
    }

    /**
     * 消费流水（分页）：pageNumber 从 0 开始，tradetype=-1 表示全部。
     *
     * 官方前端会在 query string 自动追加 openid；实测该接口不带 openid 时返回空列表，
     * 因此这里优先带 openid 请求，若因 openid 触发鉴权拒绝则回退为不带 openid。
     */
    suspend fun trades(
        startDate: String,
        endDate: String,
        tradetype: Int,
        pageNumber: Int,
        pageSize: Int,
        openid: String = "",
    ): JSONObject {
        val payload = JSONObject().apply {
            put("starttime", startDate)
            put("endtime", endDate)
            put("tradetype", tradetype.toString())
            put("pageNumber", pageNumber)
            put("pageSize", pageSize)
        }
        val path = "/selftrade/queryCardSelfTradeList"
        if (openid.isEmpty()) return call(path, payload)
        return try {
            call(path + "?openid=" + Html.urlEncode(openid), payload)
        } catch (e: ApiException) {
            LogBus.warn("流水接口带 openid 请求失败（${e.message}），回退为不带 openid")
            call(path, payload)
        }
    }

    // ---------------- 卡务（只读初始化） ----------------

    /** 挂失页数据：卡号、姓名、卡片状态。 */
    suspend fun reportLossInfo(): JSONObject = call("/losscard/openCardReportLoss", JSONObject())

    /** 解挂页数据：卡号、姓名、卡片状态。 */
    suspend fun solutionHangInfo(): JSONObject = call("/losscard/openCardsolutionHang", JSONObject())

    /** 密码修改页数据：卡号、姓名、是否已绑卡。 */
    suspend fun passwordModifyInfo(): JSONObject = call("/virtualcard/openQrcodePwdModify", JSONObject())

    /** 限额修改页数据：校园卡 / 校园码当前限额。 */
    suspend fun quotaModifyInfo(): JSONObject = call("/virtualcard/openQrcodeQuotaModify", JSONObject())

    /** 已绑定银行卡信息：卡号、姓名。 */
    suspend fun bankCardInfo(): JSONObject = call("/bankcard/getBankCard", JSONObject())

    // ---------------- 卡务（写操作） ----------------

    /** 挂失校园卡。 */
    suspend fun reportLoss(
        openid: String,
        username: String,
        idserial: String,
        cardPassword: String,
    ): JSONObject = writePost(
        "/losscard/cardReportLoss",
        CardOps.reportLoss(openid, username, idserial, cardPassword),
        openid,
    )

    /** 解挂校园卡。 */
    suspend fun solutionHang(
        openid: String,
        username: String,
        idserial: String,
        cardPassword: String,
    ): JSONObject = writePost(
        "/losscard/CardsolutionHang",
        CardOps.solutionHang(openid, username, idserial, cardPassword),
        openid,
    )

    /** 修改校园码支付密码。 */
    suspend fun modifyPassword(
        idserial: String,
        openid: String,
        oldPassword: String,
        newPassword: String,
        confirmPassword: String,
    ): JSONObject = writePost(
        "/virtualcard/qrcodePwdModify",
        CardOps.modifyPassword(idserial, openid, oldPassword, newPassword, confirmPassword),
        openid,
    )

    /** 修改校园卡消费限额（GET，金额单位分）。 */
    suspend fun modifyCardQuota(
        openid: String,
        idserial: String,
        cardId: String,
        maxConsAmtFen: String,
        maxConsTolAmtFen: String,
        cardPassword: String,
    ): JSONObject = writeGet(
        "/card/cardQuotaModify",
        jsonOf(
            CardOps.cardQuotaQuery(
                openid, idserial, cardId, maxConsAmtFen, maxConsTolAmtFen, cardPassword,
            ),
        ),
    )

    /** 修改校园码消费限额（GET，金额单位分）。 */
    suspend fun modifyQrcodeQuota(
        openid: String,
        idserial: String,
        qrcodeId: String,
        maxConsAmtFen: String,
        maxConsTolAmtFen: String,
        cardPassword: String,
    ): JSONObject = writeGet(
        "/virtualcard/qrcodeQuotaModify",
        jsonOf(
            CardOps.qrcodeQuotaQuery(
                openid, idserial, qrcodeId, maxConsAmtFen, maxConsTolAmtFen, cardPassword,
            ),
        ),
    )

    /** 绑定 / 更新银行卡。 */
    suspend fun bindBankCard(openid: String, idserial: String, bankCardNo: String): JSONObject =
        writePost("/bankcard/addbankcard", CardOps.bindBankCard(openid, idserial, bankCardNo), openid)

    /** 解绑银行卡。 */
    suspend fun unbindBankCard(
        openid: String,
        idserial: String,
        bankCardNo: String,
        cardPassword: String,
    ): JSONObject = writePost(
        "/bankcard/unbindBankCard",
        CardOps.unbindBankCard(openid, idserial, bankCardNo, cardPassword),
        openid,
    )

    // ---------------- 通用调用与解包 ----------------

    private suspend fun call(path: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            val headers = baseHeaders().toMutableMap().apply {
                put("Content-Type", "application/json;charset=UTF-8")
            }
            unwrap(http.post(BASE + path, headers, payload.toString()), path)
        }

    /** GET 调用（初始化读）：参数平铺在 query string。 */
    private suspend fun get(path: String, query: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val suffix = if (query.isEmpty()) {
                ""
            } else {
                query.entries.joinToString("&") { (key, value) ->
                    Html.urlEncode(key) + "=" + Html.urlEncode(value)
                }.let { "?$it" }
            }
            unwrap(http.get(BASE + path + suffix, baseHeaders()), path)
        }

    /**
     * 写 POST：与官方前端一致——
     * URL 追加 `?openid=`，payload 自动补 `openid` / `orgid:"2"`，整体 AES 信封。
     *
     * 写操作**不重试**：网络异常或响应不确定时交给用户确认后再手动提交。
     */
    private suspend fun writePost(
        path: String,
        payload: JSONObject,
        openid: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (openid.isBlank()) throw ApiException("缺少 openid，请重新登录", authExpired = true)
        if (!payload.has("openid")) payload.put("openid", openid)
        if (!payload.has("orgid")) payload.put("orgid", "2")
        val headers = baseHeaders().toMutableMap().apply {
            put("Content-Type", "application/json;charset=UTF-8")
        }
        val url = BASE + path + "?openid=" + Html.urlEncode(openid)
        val body = JSONObject().put("datajson", Crypto.wrapRequest(payload)).toString()
        unwrap(http.post(url, headers, body), path)
    }

    /** 写 GET：与官方前端一致，整个 payload 加密后作为唯一的 `datajson` query。 */
    private suspend fun writeGet(path: String, payload: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            if (!payload.has("orgid")) payload.put("orgid", "2")
            val datajson = Crypto.wrapRequest(payload)
            val url = BASE + path + "?datajson=" + Html.urlEncode(datajson)
            unwrap(http.get(url, baseHeaders()), path)
        }

    /** 账单类请求与官方前端一致：payload 带 openid（有值时）与 orgid。 */
    private fun identity(payload: JSONObject, openid: String) {
        if (openid.isNotEmpty() && !payload.has("openid")) payload.put("openid", openid)
        if (!payload.has("orgid")) payload.put("orgid", "2")
    }

    private fun jsonOf(map: Map<String, String>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key, value) }
    }

    /** 统包处理：HTTP 错误、非 JSON、datajson 解密、success=false。 */
    private fun unwrap(response: HttpEngine.Response, path: String): JSONObject {
        if (response.status == 401 || response.status == 403) {
            throw ApiException("会话无效（HTTP ${response.status}），请重新登录", authExpired = true)
        }
        if (response.status != 200) {
            throw ApiException("接口异常 HTTP ${response.status} ${snippet(response.body)}")
        }

        val text = response.body.trim()
        if (text.startsWith("[")) {
            return runCatching {
                JSONObject().put("resultData", JSONArray(text))
            }.getOrElse {
                throw ApiException("响应解析失败（$path）")
            }
        }

        var root: JSONObject = try {
            JSONObject(text)
        } catch (e: JSONException) {
            val expired = containsAny(text, "资源受限", "未认证", "会话失效", "登录过期")
            throw ApiException(
                if (expired) "会话已失效：${snippet(text)}" else "响应不是 JSON（$path）：${snippet(text)}",
                authExpired = expired,
            )
        }

        if (root.has("datajson")) {
            try {
                root = Crypto.decrypt(root.optString("datajson"))
            } catch (e: JSONException) {
                throw ApiException("响应解密失败：${e.message}")
            }
        }

        if (root.has("success") && !root.optBoolean("success", true)) {
            val message = root.optString("message").ifBlank { root.optString("msg", "请求失败") }
            val expired = containsAny(message, "未认证", "会话失效", "资源受限", "登录过期", "请重新登录")
            LogBus.warn("$path 返回失败：$message")
            throw ApiException(message.ifBlank { "请求失败" }, authExpired = expired)
        }
        return root
    }

    private fun baseHeaders(): Map<String, String> = mapOf(
        "x-requested-with" to "XMLHttpRequest",
        "session-type" to "uniapp",
        "isWechatApp" to "true",
        "orgid" to "2",
    )

    companion object {

        const val BASE = "https://m.icw.buaa.edu.cn/card"

        private fun containsAny(text: String?, vararg keys: String): Boolean =
            keys.any { key -> text?.contains(key) == true }

        internal fun snippet(text: String?): String {
            val cleaned = text.orEmpty().replace(Regex("\\s+"), " ").trim()
            return if (cleaned.length > 180) cleaned.take(180) + "…" else cleaned
        }
    }
}
