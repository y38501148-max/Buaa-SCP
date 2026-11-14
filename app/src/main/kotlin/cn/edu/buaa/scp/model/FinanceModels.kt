package cn.edu.buaa.scp.model

import cn.edu.buaa.scp.util.objects
import cn.edu.buaa.scp.util.pathString
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale

/**
 * 一条财务收入记录（财务查询系统 `getStudentSRCX` 返回的一行）。
 *
 * 金额统一换算成「分」保存（接口原值单位为元，可能是数字或字符串），
 * 无法解析时保留 null，避免把「未知」显示成 0.00。
 */
data class FinanceIncome(
    val id: String,
    val project: String,
    val description: String,
    val amountCents: Long?,
    val taxCents: Long?,
    val paidCents: Long?,
    val taxRate: String,
    val recordedDate: String,
    val year: String,
    val month: String,
) {
    /** 列表主标题：优先发放项目，其次摘要，最后兜底文案。 */
    val title: String
        get() = project.ifBlank { description }.ifBlank { "收入记录" }
}

/** 分页读取完成后的收入列表；[total] 为服务端声明的记录总数。 */
data class FinanceIncomes(
    val entries: List<FinanceIncome>,
    val total: Int,
)

/** 收入汇总（金额单位：分；null 金额按 0 计入合计，另统计缺失实发金额的条数）。 */
data class FinanceSummary(
    val count: Int,
    val paidCents: Long,
    val amountCents: Long,
    val taxCents: Long,
    val missingPaid: Int,
)

/** 按年月分组的收入记录；year 为空表示无法归入具体月份的「其他」组。 */
data class FinanceMonth(
    val year: String,
    val month: Int,
    val entries: List<FinanceIncome>,
) {
    val known: Boolean get() = year.isNotEmpty()
}

/**
 * 财务收入的纯数据模型与格式化工具（不依赖网络、可单测）。
 *
 * 字段映射（接口 → 模型）：
 * lsh→id、ffxmmc→project、zy→description、je→amountCents、se→taxCents、
 * sfje→paidCents、slbfb→taxRate、lrrq→recordedDate、nian→year、yue→month。
 */
object FinanceModels {

    private val DATE_PREFIX = Regex("""^(\d{4})[-/.](\d{1,2})""")

    private val OTHER = YearMonth("", 0)

    /** 一页收入数据。 */
    data class Page(
        val entries: List<FinanceIncome>,
        val total: Int,
    )

    /**
     * 解析已去掉 envelope 的 `data` 对象。
     *
     * 实测存在两种结构：`{"mx":[…],"total":N}` 与 `{"data":[…],"count":N}`；
     * 两者都没有时视为结构异常返回 null。
     */
    fun parsePage(data: JSONObject): Page? {
        val rows = data.optJSONArray("mx") ?: data.optJSONArray("data") ?: return null
        val total = intOf(data.opt("total")) ?: intOf(data.opt("count")) ?: return null
        if (total < 0) return null
        return Page(rows.objects().mapNotNull { parseIncome(it) }, total)
    }

    /** 解析一行；`lsh` 为空的行没有稳定标识，直接丢弃。 */
    fun parseIncome(row: JSONObject): FinanceIncome? {
        val id = row.pathString("lsh").trim()
        if (id.isEmpty()) return null
        return FinanceIncome(
            id = id,
            project = row.pathString("ffxmmc").trim(),
            description = row.pathString("zy").trim(),
            amountCents = cents(row.opt("je")),
            taxCents = cents(row.opt("se")),
            paidCents = cents(row.opt("sfje")),
            taxRate = row.pathString("slbfb").trim(),
            recordedDate = row.pathString("lrrq").trim(),
            year = row.pathString("nian").trim(),
            month = row.pathString("yue").trim(),
        )
    }

    /**
     * 金额（元）→ 分。
     *
     * 支持 Number 与字符串（"123.45"）；超过两位小数按 HALF_UP 先保留两位再换算；
     * null / JSONObject.NULL / 非法内容返回 null。
     */
    fun cents(raw: Any?): Long? {
        if (raw == null || raw === JSONObject.NULL) return null
        val value = decimalOf(raw) ?: return null
        return runCatching {
            value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /**
     * 按年月分组：优先 `nian` + `yue`，缺失/非法时退回 `lrrq` 的 yyyy-MM。
     *
     * 组按年月倒序，无法归组的（year="" month=0）放最后；组内按录入日期倒序、再按 id。
     */
    fun groups(entries: List<FinanceIncome>): List<FinanceMonth> {
        val buckets = LinkedHashMap<YearMonth, MutableList<FinanceIncome>>()
        for (entry in entries) {
            val key = yearMonthOf(entry) ?: OTHER
            buckets.getOrPut(key) { ArrayList() }.add(entry)
        }
        val comparator = Comparator<Map.Entry<YearMonth, MutableList<FinanceIncome>>> { a, b ->
            compareGroups(a.key, b.key)
        }
        return buckets.entries.sortedWith(comparator).map { (key, list) ->
            FinanceMonth(
                year = key.year,
                month = key.month,
                entries = list.sortedWith(
                    compareByDescending<FinanceIncome> { it.recordedDate }.thenBy { it.id },
                ),
            )
        }
    }

    /** 已知年份（含由 lrrq 推导出的年份）倒序去重。 */
    fun years(entries: List<FinanceIncome>): List<String> {
        val years = LinkedHashSet<String>()
        for (entry in entries) {
            val year = yearMonthOf(entry)?.year ?: continue
            years.add(year)
        }
        return years.sortedWith(compareByDescending<String> { yearValue(it) }.thenByDescending { it })
    }

    /** 某条记录所属年月（year to month）；无法判定时返回 null。 */
    fun periodOf(entry: FinanceIncome): Pair<String, Int>? {
        val period = yearMonthOf(entry) ?: return null
        return period.year to period.month
    }

    /** 金额合计；null 金额按 0 处理，并统计实发金额缺失的条数。 */
    fun summary(entries: List<FinanceIncome>): FinanceSummary {
        var paid = 0L
        var amount = 0L
        var tax = 0L
        var missingPaid = 0
        for (entry in entries) {
            val paidCents = entry.paidCents
            if (paidCents == null) missingPaid++ else paid += paidCents
            entry.amountCents?.let { amount += it }
            entry.taxCents?.let { tax += it }
        }
        return FinanceSummary(entries.size, paid, amount, tax, missingPaid)
    }

    /** 关键字搜索：项目 / 摘要 / 流水号包含即命中（忽略大小写）；空关键字返回原列表。 */
    fun search(entries: List<FinanceIncome>, keyword: String): List<FinanceIncome> {
        val text = keyword.trim()
        if (text.isEmpty()) return entries
        return entries.filter { entry ->
            entry.project.contains(text, ignoreCase = true) ||
                    entry.description.contains(text, ignoreCase = true) ||
                    entry.id.contains(text, ignoreCase = true)
        }
    }

    /** "¥1,234.56" / "-¥0.05"；null → "—"。 */
    fun yuan(cents: Long?): String {
        if (cents == null) return "—"
        val text = money(cents)
        return if (text.startsWith("-")) "-¥" + text.removePrefix("-") else "¥$text"
    }

    /** "1,234.56" / "-0.05"；null → "—"。 */
    fun money(cents: Long?): String {
        if (cents == null) return "—"
        val value = BigDecimal.valueOf(cents, 2)
        val negative = value.signum() < 0
        val plain = value.abs().toPlainString()
        val dot = plain.indexOf('.')
        val integer = if (dot >= 0) plain.substring(0, dot) else plain
        val fraction = if (dot >= 0) plain.substring(dot + 1) else "00"
        val grouped = integer.reversed().chunked(3).joinToString(",").reversed()
        return (if (negative) "-" else "") + grouped + "." + fraction
    }

    private data class YearMonth(val year: String, val month: Int)

    private fun yearMonthOf(entry: FinanceIncome): YearMonth? {
        val year = entry.year.trim()
        val month = entry.month.trim().toIntOrNull()
        if (year.isNotEmpty() && month != null && month in 1..12) {
            return YearMonth(year, month)
        }
        return yearMonthFromDate(entry.recordedDate)
    }

    private fun yearMonthFromDate(date: String): YearMonth? {
        val match = DATE_PREFIX.find(date.trim()) ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return YearMonth(match.groupValues[1], month)
    }

    private fun compareGroups(a: YearMonth, b: YearMonth): Int {
        val aOther = a.year.isEmpty()
        val bOther = b.year.isEmpty()
        if (aOther != bOther) return if (aOther) 1 else -1
        val byYear = yearValue(b.year).compareTo(yearValue(a.year))
        if (byYear != 0) return byYear
        return b.month.compareTo(a.month)
    }

    private fun yearValue(year: String): Int =
        year.toIntOrNull() ?: (year.lowercase(Locale.US).hashCode() and Int.MAX_VALUE)

    private fun intOf(value: Any?): Int? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> value.toString().trim().toIntOrNull()
    }

    private fun decimalOf(raw: Any?): BigDecimal? = when (raw) {
        null, JSONObject.NULL -> null
        is BigDecimal -> raw
        is BigInteger -> BigDecimal(raw)
        is Long, is Int, is Short, is Byte -> BigDecimal.valueOf(raw.toLong())
        is Number -> runCatching { BigDecimal.valueOf(raw.toDouble()) }.getOrNull()
        is String -> raw.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        else -> raw.toString().trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    }
}
