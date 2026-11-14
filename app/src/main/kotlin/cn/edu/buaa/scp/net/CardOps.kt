package cn.edu.buaa.scp.net

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * m.icw 卡务写操作的请求体构造与本地校验。
 *
 * 这些操作会**真实改变校园卡状态**，因此把「参数拼装」和「网络调用」拆开：
 * 纯函数集中在本类，便于单元测试逐字锁定字段名；[CardApi] 只负责发送。
 *
 * 字段名全部来自官方 uni-app bundle（2026-11 版本），不要凭感觉改名：
 * - 挂失 / 解挂使用 `bankpwd` 表示校园卡密码；
 * - 限额修改是 GET query，校园卡与校园码分别使用 `maxconstolamt` / `maxconstotalamt`；
 * - 绑定 / 解绑银行卡带 `wechatui=true`。
 */
object CardOps {

    // ---------------- 挂失 / 解挂 ----------------

    /** 校园卡挂失。对应官方 `/losscard/cardReportLoss`。 */
    fun reportLoss(
        openid: String,
        username: String,
        idserial: String,
        cardPassword: String,
    ): JSONObject = JSONObject().apply {
        put("openid", openid)
        put("username", username)
        put("idserial", idserial)
        put("bankpwd", cardPassword)
    }

    /** 校园卡解挂。对应官方 `/losscard/CardsolutionHang`（注意大写 C）。 */
    fun solutionHang(
        openid: String,
        username: String,
        idserial: String,
        cardPassword: String,
    ): JSONObject = reportLoss(openid, username, idserial, cardPassword)

    // ---------------- 密码修改 ----------------

    /**
     * 修改校园码（校园卡）支付密码。
     *
     * 官方前端把整个表单对象原样提交，并附加 `openid` 与 `flag=1`；
     * `txpassword` 是再次确认的新密码。
     */
    fun modifyPassword(
        idserial: String,
        openid: String,
        oldPassword: String,
        newPassword: String,
        confirmPassword: String,
    ): JSONObject = JSONObject().apply {
        put("idserial", idserial)
        put("oldpassword", oldPassword)
        put("newpwd", newPassword)
        put("txpassword", confirmPassword)
        put("openid", openid)
        put("flag", 1)
    }

    // ---------------- 限额修改 ----------------

    /** 校园卡限额（GET query）。金额单位为分。 */
    fun cardQuotaQuery(
        openid: String,
        idserial: String,
        cardId: String,
        maxConsAmt: String,
        maxConsTolAmt: String,
        cardPassword: String,
    ): Map<String, String> = linkedMapOf(
        "openid" to openid,
        "idserial" to idserial,
        "maxconsamt" to maxConsAmt,
        "maxconstolamt" to maxConsTolAmt,
        "cardid" to cardId,
        "txpasswd" to cardPassword,
    )

    /** 校园码限额（GET query）。注意官方字段是 `maxconstotalamt`，与校园卡不同。 */
    fun qrcodeQuotaQuery(
        openid: String,
        idserial: String,
        qrcodeId: String,
        maxConsAmt: String,
        maxConsTolAmt: String,
        cardPassword: String,
    ): Map<String, String> = linkedMapOf(
        "openid" to openid,
        "idserial" to idserial,
        "maxconsamt" to maxConsAmt,
        "maxconstotalamt" to maxConsTolAmt,
        "qrcodeid" to qrcodeId,
        "txpasswd" to cardPassword,
    )

    // ---------------- 绑定 / 解绑银行卡 ----------------

    /** 绑定或更新银行卡。官方接口不要求校园卡密码。 */
    fun bindBankCard(
        openid: String,
        idserial: String,
        bankCardNo: String,
    ): JSONObject = JSONObject().apply {
        put("openid", openid)
        put("idserial", idserial)
        put("bankcdno", bankCardNo)
        put("wechatui", true)
    }

    /** 解绑银行卡。`bankpwd` 为 6 位校园卡密码（官方文案写作“银行卡密码”）。 */
    fun unbindBankCard(
        openid: String,
        idserial: String,
        bankCardNo: String,
        cardPassword: String,
    ): JSONObject = JSONObject().apply {
        put("openid", openid)
        put("idserial", idserial)
        put("bankcdno", bankCardNo)
        put("bankpwd", cardPassword)
        put("wechatui", true)
    }

    // ---------------- 金额与输入校验 ----------------

    /** 元 → 分。官方 `yuanToFen`：整数补 00，一位小数补 0，最多两位。 */
    fun yuanToFen(yuan: String): String {
        val text = yuan.trim()
        if (text.isEmpty()) return "0"
        val value = text.toBigDecimalOrNull() ?: return "0"
        return value.multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .toPlainString()
    }

    /** 校园卡密码：6 位纯数字。 */
    fun isCardPassword(value: String): Boolean =
        value.length == 6 && value.all { it.isDigit() }

    /**
     * 银行卡号校验，规则与官方 `checkBankCard` 一致：
     * 16–19 位数字、开头两位在允许列表内、通过 Luhn 校验。
     */
    fun isBankCardNo(value: String): Boolean {
        if (value.length !in 16..19) return false
        if (!value.all { it.isDigit() }) return false
        if (value.substring(0, 2) !in BANK_PREFIX) return false
        return luhn(value)
    }

    private fun luhn(number: String): Boolean {
        var sum = 0
        var double = false
        for (index in number.length - 1 downTo 0) {
            var digit = number[index] - '0'
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return sum % 10 == 0
    }

    /** 银行卡号展示：前 6 后 4，中间用 * 填充。 */
    fun maskBankCardNo(value: String): String {
        if (value.length <= 10) return if (value.isEmpty()) "" else "****"
        return value.take(6) + " **** **** " + value.takeLast(4)
    }

    private val BANK_PREFIX = setOf(
        "10", "18", "30", "35", "37", "40", "41", "42", "43", "44",
        "45", "46", "47", "48", "49", "50", "51", "52", "53", "54",
        "55", "56", "58", "60", "62", "65", "68", "69", "84", "87",
        "88", "94", "95", "98", "99",
    )
}
