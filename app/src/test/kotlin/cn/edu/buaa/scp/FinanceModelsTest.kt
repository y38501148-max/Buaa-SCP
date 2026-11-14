package cn.edu.buaa.scp

import cn.edu.buaa.scp.model.FinanceIncome
import cn.edu.buaa.scp.model.FinanceModels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 财务收入模型测试。
 *
 * 夹具按财务查询系统真实返回结构构造（envelope + data 两种分页形态），
 * 流水号、项目、金额均为虚构值。
 */
class FinanceModelsTest {

    private val page: JSONObject = TestFixtures.json("financePage.json").getJSONObject("data")
    private val pageAlt: JSONObject = TestFixtures.json("financePageAlt.json").getJSONObject("data")

    private fun entry(
        id: String,
        project: String = "",
        description: String = "",
        recordedDate: String = "",
        year: String = "",
        month: String = "",
    ): FinanceIncome = FinanceIncome(
        id = id,
        project = project,
        description = description,
        amountCents = null,
        taxCents = null,
        paidCents = null,
        taxRate = "",
        recordedDate = recordedDate,
        year = year,
        month = month,
    )

    // ---------------- 分页结构 ----------------

    @Test
    fun parsePage_readsMxAndTotal() {
        val parsed = FinanceModels.parsePage(page)!!
        assertEquals(3, parsed.entries.size)
        assertEquals(3, parsed.total)

        val first = parsed.entries[0]
        assertEquals("A1", first.id)
        assertEquals("助学金", first.project)
        assertEquals("2026年秋季助学金", first.description)
        assertEquals(100000L, first.amountCents)
        assertEquals(0L, first.taxCents)
        assertEquals(100000L, first.paidCents)
        assertEquals("0", first.taxRate)
        assertEquals("2026-09-10", first.recordedDate)
        assertEquals("2026", first.year)
        assertEquals("9", first.month)
        assertEquals("助学金", first.title)

        val second = parsed.entries[1]
        assertEquals("A2", second.id)
        assertEquals(80050L, second.amountCents)
        assertEquals(2005L, second.taxCents)
        assertEquals(78045L, second.paidCents)
        assertEquals("2.51", second.taxRate)

        val third = parsed.entries[2]
        assertEquals("A3", third.id)
        assertEquals("", third.project)
        assertEquals("", third.description)
        assertNull(third.amountCents)
        assertNull(third.taxCents)
        assertNull(third.paidCents)
        assertEquals("2025-12-01", third.recordedDate)
        assertEquals("收入记录", third.title)
    }

    @Test
    fun parsePage_readsDataAndCount() {
        val parsed = FinanceModels.parsePage(pageAlt)!!
        assertEquals(1, parsed.total)
        val row = parsed.entries.single()
        assertEquals("B1", row.id)
        assertEquals("奖学金", row.project)
        assertEquals("", row.description)
        assertEquals(50000L, row.amountCents)
        assertEquals(50000L, row.paidCents)
        assertNull(row.taxCents)
        assertEquals("2025", row.year)
        assertEquals("6", row.month)
        assertEquals("奖学金", row.title)
    }

    @Test
    fun parsePage_rejectsUnknownStructure() {
        assertNull(FinanceModels.parsePage(JSONObject()))
        // 有行数组但缺 total / count
        assertNull(FinanceModels.parsePage(JSONObject("""{"mx":[{"lsh":"A1"}]}""")))
        // data 不是数组
        assertNull(FinanceModels.parsePage(JSONObject("""{"data":"x","count":1}""")))
        // 负数总数
        assertNull(FinanceModels.parsePage(JSONObject("""{"mx":[],"total":-1}""")))
    }

    @Test
    fun parseIncome_requiresId() {
        assertNull(FinanceModels.parseIncome(JSONObject()))
        assertNull(FinanceModels.parseIncome(JSONObject().put("ffxmmc", "无流水号")))
        assertNull(FinanceModels.parseIncome(JSONObject().put("lsh", "   ")))

        val parsed = FinanceModels.parseIncome(JSONObject().put("lsh", " X1 "))!!
        assertEquals("X1", parsed.id)
        assertEquals("", parsed.project)
        assertNull(parsed.amountCents)
        assertEquals("收入记录", parsed.title)
    }

    @Test
    fun parseIncome_providesTitleFallback() {
        val summaryOnly = FinanceModels.parseIncome(
            JSONObject().put("lsh", "S1").put("zy", "项目摘要"),
        )
        assertEquals("项目摘要", summaryOnly?.title)

        val both = FinanceModels.parseIncome(
            JSONObject().put("lsh", "S2").put("ffxmmc", "项目名").put("zy", "项目摘要"),
        )
        assertEquals("项目名", both?.title)
    }

    // ---------------- 金额换算 ----------------

    @Test
    fun cents_handlesNumbersAndStrings() {
        assertEquals(100000L, FinanceModels.cents(1000))
        assertEquals(80050L, FinanceModels.cents(800.5))
        assertEquals(880L, FinanceModels.cents(8.8))
        assertEquals(50000L, FinanceModels.cents("500.00"))
        assertEquals(12345L, FinanceModels.cents("123.45"))
        assertEquals(0L, FinanceModels.cents(0))
        assertEquals(0L, FinanceModels.cents("0.00"))
    }

    @Test
    fun cents_roundsExtraDecimalsHalfUp() {
        assertEquals(12346L, FinanceModels.cents("123.456"))
        assertEquals(12345L, FinanceModels.cents("123.454"))
        assertEquals(101L, FinanceModels.cents(1.005))
        assertEquals(1L, FinanceModels.cents("0.005"))
    }

    @Test
    fun cents_handlesNegative() {
        assertEquals(-5L, FinanceModels.cents("-0.05"))
        assertEquals(-12345L, FinanceModels.cents("-123.45"))
        assertEquals(-2005L, FinanceModels.cents(-20.05))
    }

    @Test
    fun cents_rejectsMissingAndInvalid() {
        assertNull(FinanceModels.cents(null))
        assertNull(FinanceModels.cents(JSONObject.NULL))
        assertNull(FinanceModels.cents(""))
        assertNull(FinanceModels.cents("   "))
        assertNull(FinanceModels.cents("abc"))
        assertNull(FinanceModels.cents("12.3.4"))
    }

    // ---------------- 年月分组 ----------------

    @Test
    fun groups_sortsByYearMonthDescendingAndFallsBackToDate() {
        val entries = FinanceModels.parsePage(page)!!.entries
        // A1: nian/yue → 2026-09；A2: → 2026-10；A3: 无 nian/yue，退回 lrrq → 2025-12
        val groups = FinanceModels.groups(entries)
        assertEquals(3, groups.size)

        assertEquals("2026", groups[0].year)
        assertEquals(10, groups[0].month)
        assertEquals(listOf("A2"), groups[0].entries.map { it.id })
        assertTrue(groups[0].known)

        assertEquals("2026", groups[1].year)
        assertEquals(9, groups[1].month)
        assertEquals(listOf("A1"), groups[1].entries.map { it.id })

        assertEquals("2025", groups[2].year)
        assertEquals(12, groups[2].month)
        assertEquals(listOf("A3"), groups[2].entries.map { it.id })
        assertTrue(groups[2].known)
    }

    @Test
    fun groups_putsUnknownMonthLast() {
        val known = entry("K1", recordedDate = "2026-09-10", year = "2026", month = "9")
        val unknown = entry("U1")
        val groups = FinanceModels.groups(listOf(unknown, known))
        assertEquals(2, groups.size)
        assertEquals("2026", groups[0].year)
        assertEquals("", groups[1].year)
        assertEquals(0, groups[1].month)
        assertFalse(groups[1].known)
        assertEquals("U1", groups[1].entries.single().id)
    }

    @Test
    fun groups_ordersEntriesByRecordedDateThenId() {
        val older = entry("A", recordedDate = "2026-09-01", year = "2026", month = "9")
        val newerB = entry("B", recordedDate = "2026-09-20", year = "2026", month = "9")
        val newerC = entry("C", recordedDate = "2026-09-20", year = "2026", month = "9")
        val group = FinanceModels.groups(listOf(older, newerC, newerB)).single()
        assertEquals(listOf("B", "C", "A"), group.entries.map { it.id })
    }

    @Test
    fun groups_fallsBackToDateWhenFieldsInvalid() {
        val badMonth = entry("M1", year = "2026", month = "13", recordedDate = "2026-10-02")
        val good = entry("M2", year = "2026", month = "9", recordedDate = "2026-09-15")
        val groups = FinanceModels.groups(listOf(badMonth, good))
        assertEquals(2, groups.size)
        // M1 的 yue 非法 → 退回 lrrq 的 2026-10
        assertEquals(10, groups[0].month)
        assertEquals(listOf("M1"), groups[0].entries.map { it.id })
        assertEquals(9, groups[1].month)
        assertEquals(listOf("M2"), groups[1].entries.map { it.id })
    }

    @Test
    fun groups_handlesEmptyList() {
        assertTrue(FinanceModels.groups(emptyList()).isEmpty())
    }

    // ---------------- 年份 ----------------

    @Test
    fun years_dedupesAndSortsDescending() {
        val entries = FinanceModels.parsePage(page)!!.entries
        assertEquals(listOf("2026", "2025"), FinanceModels.years(entries))
        assertEquals(listOf("2026", "2025"), FinanceModels.years(entries + entry("Z1")))
        assertTrue(FinanceModels.years(listOf(entry("Z2"))).isEmpty())
    }

    // ---------------- 汇总 ----------------

    @Test
    fun summary_sumsAndCountsMissingPaid() {
        val entries = FinanceModels.parsePage(page)!!.entries
        val summary = FinanceModels.summary(entries)
        assertEquals(3, summary.count)
        assertEquals(178045L, summary.paidCents) // 1000.00 + 780.45，A3 缺失按 0
        assertEquals(180050L, summary.amountCents) // 1000 + 800.50
        assertEquals(2005L, summary.taxCents) // 0 + 20.05
        assertEquals(1, summary.missingPaid)
    }

    @Test
    fun summary_handlesEmptyList() {
        val summary = FinanceModels.summary(emptyList())
        assertEquals(0, summary.count)
        assertEquals(0L, summary.paidCents)
        assertEquals(0L, summary.amountCents)
        assertEquals(0L, summary.taxCents)
        assertEquals(0, summary.missingPaid)
    }

    // ---------------- 搜索 ----------------

    @Test
    fun search_matchesProjectDescriptionAndId() {
        val entries = FinanceModels.parsePage(page)!!.entries
        assertEquals(listOf("A1"), FinanceModels.search(entries, "助学").map { it.id })
        assertEquals(listOf("A2"), FinanceModels.search(entries, "酬金").map { it.id })
        assertEquals(listOf("A2"), FinanceModels.search(entries, "助教").map { it.id })
        assertEquals(listOf("A2"), FinanceModels.search(entries, "a2").map { it.id })
    }

    @Test
    fun search_blankKeywordReturnsOriginalList() {
        val entries = FinanceModels.parsePage(page)!!.entries
        assertEquals(entries, FinanceModels.search(entries, ""))
        assertEquals(entries, FinanceModels.search(entries, "   "))
        assertTrue(FinanceModels.search(entries, "不存在").isEmpty())
    }

    // ---------------- 金额展示 ----------------

    @Test
    fun yuan_formatsWithThousandsSeparator() {
        assertEquals("¥1,234.56", FinanceModels.yuan(123456))
        assertEquals("¥0.00", FinanceModels.yuan(0))
        assertEquals("¥0.05", FinanceModels.yuan(5))
        assertEquals("¥1,000,000.00", FinanceModels.yuan(100_000_000L))
        assertEquals("-¥0.05", FinanceModels.yuan(-5))
        assertEquals("-¥1,234.56", FinanceModels.yuan(-123456))
        assertEquals("—", FinanceModels.yuan(null))
    }

    @Test
    fun money_formatsWithoutCurrencySymbol() {
        assertEquals("1,234.56", FinanceModels.money(123456))
        assertEquals("0.00", FinanceModels.money(0))
        assertEquals("-0.05", FinanceModels.money(-5))
        assertEquals("—", FinanceModels.money(null))
    }

    // ---------------- 所属年月 ----------------

    @Test
    fun periodOf_usesFieldsThenDate() {
        val entries = FinanceModels.parsePage(page)!!.entries
        assertEquals("2026" to 9, FinanceModels.periodOf(entries[0]))
        assertEquals("2025" to 12, FinanceModels.periodOf(entries[2]))
        assertNull(FinanceModels.periodOf(entry("Z3")))
    }
}
