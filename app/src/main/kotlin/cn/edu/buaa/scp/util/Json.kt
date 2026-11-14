package cn.edu.buaa.scp.util

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 校园卡响应的容错取值与格式化工具。
 *
 * 接口字段可能随发版变化，因此所有取值都按「路径逐级查找 + 缺失给空串」处理，
 * 不假设字段一定存在。
 */
object Json {

    /** 逐级取字符串：{@code root.pathString("data", "cardinfo", "cardbal")}。缺失返回 ""。 */
    fun numberOrNull(text: String?): Double? = text?.trim()?.toDoubleOrNull()

    /** 分 → 元。接口金额单位多为「分」，前端 fenToYuan 除以 100；已带小数点的值原样返回。 */
    fun fenToYuan(fenText: String?): String {
        val text = fenText?.trim().orEmpty()
        if (text.isEmpty()) return "0.00"
        val negative = text.startsWith("-")
        val digits = if (negative) text.substring(1) else text
        if (digits.contains(".")) return text
        val value = digits.toBigDecimalOrNull() ?: return fenText.orEmpty()
        val yuan = value.divide(BigDecimal("100"), 2, RoundingMode.HALF_UP).toPlainString()
        return if (negative) "-$yuan" else yuan
    }

    /** 元字符串统一保留两位小数。 */
    fun yuan(yuanText: String?): String {
        val text = yuanText?.trim().orEmpty()
        if (text.isEmpty()) return "0.00"
        return text.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: text
    }

    /** 金额展示：带 ¥ 前缀。 */
    fun money(yuanText: String?): String = "¥" + yuan(yuanText)

    /** 学号 / 卡号等脱敏：保留前 2 后 2。 */
    fun mask(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        if (value.length <= 5) return "${value.first()}***"
        return value.take(2) + "****" + value.takeLast(2)
    }

    fun now(pattern: String): String = SimpleDateFormat(pattern, Locale.CHINA).format(Date())

    /** 当前月份 "yyyy-MM"。 */
    fun currentMonth(): String = now("yyyy-MM")

    /** 月份加偏移："2026-11" + (-1) → "2026-10"。 */
    fun shiftMonth(yyyyMM: String, delta: Int): String {
        val parts = yyyyMM.split("-")
        if (parts.size != 2) return yyyyMM
        var year = parts[0].toIntOrNull() ?: return yyyyMM
        var month = parts[1].toIntOrNull() ?: return yyyyMM
        month += delta
        while (month <= 0) {
            month += 12
            year -= 1
        }
        while (month > 12) {
            month -= 12
            year += 1
        }
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    /** "2026-11" → "2026年11月"。 */
    fun monthLabel(yyyyMM: String): String {
        val parts = yyyyMM.split("-")
        if (parts.size != 2) return yyyyMM
        val month = parts[1].toIntOrNull() ?: return yyyyMM
        return "${parts[0]}年${month}月"
    }

    /** 当月天数对应的时间范围："2026-11" → ["2026-11-01", "2026-11-30"]。 */
    fun monthRange(yyyyMM: String): Pair<String, String> {
        val parts = yyyyMM.split("-")
        if (parts.size != 2) return "$yyyyMM-01" to "$yyyyMM-31"
        val year = parts[0].toIntOrNull() ?: return "$yyyyMM-01" to "$yyyyMM-31"
        val month = parts[1].toIntOrNull() ?: return "$yyyyMM-01" to "$yyyyMM-31"
        val calendar = Calendar.getInstance().apply {
            set(year, month - 1, 1)
        }
        val days = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        return String.format(Locale.US, "%04d-%02d-01", year, month) to
                String.format(Locale.US, "%04d-%02d-%02d", year, month, days)
    }

    /** 生成 startMonth..endMonth 的月份列表（最多 12 个）。 */
    fun monthList(startMonth: String, endMonth: String): List<String> {
        val result = ArrayList<String>(6)
        var cursor = startMonth
        repeat(12) {
            result.add(cursor)
            if (cursor == endMonth) return result
            cursor = shiftMonth(cursor, 1)
        }
        return result
    }

    /** 卡类型编码（官方 pcodeCard）→ 中文名。 */
    fun cardType(pcode: String?): String = when (pcode) {
        null, "" -> "未获取"
        "YG" -> "员工卡"
        "BKS" -> "学生卡"
        "FK" -> "访客卡"
        "LS" -> "一般消费卡"
        else -> pcode
    }

    /** 时间戳 → "MM-dd HH:mm:ss"；0 值返回空串。 */
    fun time(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        return SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochMillis))
    }

    fun isBlank(value: String?): Boolean = value.isNullOrBlank()

    fun or(value: String?, fallback: String): String = if (value.isNullOrBlank()) fallback else value

    fun Int.toPercentString(): String = "$this%"
}

/** 逐级取字符串；路径上任一层缺失、类型不符或值为 null 时返回 ""。 */
fun JSONObject.pathString(vararg path: String): String {
    val value = pathValue(*path) ?: return ""
    if (value === JSONObject.NULL) return ""
    return when (value) {
        is BigDecimal -> value.toPlainString()
        is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }
}

fun JSONObject.pathObject(vararg path: String): JSONObject? = pathValue(*path) as? JSONObject

fun JSONObject.pathArray(vararg path: String): JSONArray? = pathValue(*path) as? JSONArray

private fun JSONObject.pathValue(vararg path: String): Any? {
    var current: Any? = this
    for (key in path) {
        val obj = current as? JSONObject ?: return null
        if (!obj.has(key)) return null
        current = obj.opt(key)
    }
    return current
}

/** JSONArray 逐项转 JSONObject，忽略非对象元素。 */
fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
