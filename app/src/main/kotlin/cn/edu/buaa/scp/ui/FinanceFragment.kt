package cn.edu.buaa.scp.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.LinearLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.FinanceIncome
import cn.edu.buaa.scp.model.FinanceModels
import cn.edu.buaa.scp.model.FinanceMonth
import cn.edu.buaa.scp.model.FinanceSummary
import cn.edu.buaa.scp.net.resultOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 财务页：学校财务系统里的个人收入记录（酬金、助学金等），只读。
 *
 * 数据由 [cn.edu.buaa.scp.net.FinanceApi] 通过统一认证 → icw 财务门户 → 财务查询系统取得；
 * 页面只做展示：实发合计、按月分组、年度筛选与记录详情。
 */
class FinanceFragment : ScpFragment() {

    private var entries: List<FinanceIncome> = emptyList()
    private var error: String? = null

    /** 当前选择的年份；"" 表示全部，null 表示跟随最近一年。 */
    private var year: String? = null
    private var keyword = ""

    /** 搜索结果容器：输入关键字时只重建这一块，避免输入框失焦。 */
    private var results: LinearLayout? = null

    override suspend fun load() {
        if (app.demoMode) {
            entries = Demo.financeIncomes()
            error = null
            render()
            return
        }
        val result = resultOf { app.withRecovery { app.financeApi.incomes() } }
        entries = result.value?.entries.orEmpty()
        error = result.error
        if (result.ok) app.markSynced()
        if (!isAlive) return
        render()
    }

    private fun render() {
        clearBody()
        val context = requireContext()
        if (error != null && entries.isEmpty()) {
            addErrorCard(error) { refresh() }
            return
        }
        val years = FinanceModels.years(entries)
        addSearchField(context)
        if (years.isNotEmpty()) addYearChips(context, years)

        val container = Ui.column(context)
        body.addView(container)
        results = container
        renderResults(context, years)
    }

    // ---------------- 筛选 ----------------

    private fun addSearchField(context: Context) {
        val layout = layoutInflater.inflate(R.layout.form_field, body, false) as TextInputLayout
        layout.hint = "搜索项目或摘要"
        layout.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = Ui.dp(context, 12f) }
        val input = layout.findViewById<TextInputEditText>(R.id.input)
        input.setText(keyword)
        input.setSelection(input.text?.length ?: 0)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty()
                val container = results ?: return
                renderResults(container.context, FinanceModels.years(entries))
            }
        })
        body.addView(layout)
    }

    private fun addYearChips(context: Context, years: List<String>) {
        val selectedYear = selectedYear(years)
        val scroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val row = Ui.row(context)
        scroll.addView(row)
        val options = ArrayList<String>(years.size + 1)
        options.addAll(years)
        options.add(ALL_YEARS)
        options.forEachIndexed { index, option ->
            val label = if (option == ALL_YEARS) "全部" else "${option}年"
            val chip = Ui.chip(context, label, selected = option == selectedYear) {
                year = option
                render()
            }
            chip.contentDescription = "筛选：$label"
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) marginStart = Ui.dp(context, 8f) },
            )
        }
        body.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(context, 14f) },
        )
    }

    // ---------------- 结果 ----------------

    private fun renderResults(context: Context, years: List<String>) {
        val container = results ?: return
        container.removeAllViews()
        val selectedYear = selectedYear(years)
        val matched = FinanceModels.search(entries, keyword)
        val groups = FinanceModels.groups(matched).filter {
            selectedYear == ALL_YEARS || it.year == selectedYear
        }
        val visible = groups.flatMap { it.entries }

        if (error != null) {
            val card = Ui.addCard(container)
            card.addView(Ui.text(context, "刷新失败：$error", 12f, R.color.text_hint))
        }
        addSummary(container, selectedYear, visible)

        if (groups.isEmpty()) {
            addEmpty(container, context, if (keyword.isBlank()) "暂无收入记录" else "没有匹配的记录")
            return
        }
        groups.forEach { group ->
            addMonthGroup(container, group, showYear = selectedYear == ALL_YEARS)
        }
    }

    private fun selectedYear(years: List<String>): String {
        val current = year
        return when {
            current == null -> years.firstOrNull() ?: ALL_YEARS
            current == ALL_YEARS || years.contains(current) -> current
            else -> years.firstOrNull() ?: ALL_YEARS
        }
    }

    private fun addSummary(parent: LinearLayout, selectedYear: String, visible: List<FinanceIncome>) {
        val context = parent.context
        val summary = FinanceModels.summary(visible)
        val card = Ui.addCard(parent)
        val label = if (selectedYear == ALL_YEARS) "全部实发合计" else "${selectedYear}年实发合计"
        card.addView(Ui.text(context, label, 12f, R.color.text_secondary))
        card.addView(
            Ui.text(context, FinanceModels.yuan(summary.paidCents), 30f, R.color.text_primary, bold = true),
            Ui.rowParams(context, 6f),
        )
        card.addView(
            Ui.text(context, summaryLine(summary), 12f, R.color.text_hint),
            Ui.rowParams(context, 8f),
        )
    }

    private fun summaryLine(summary: FinanceSummary): String {
        val parts = ArrayList<String>(3)
        parts.add("${summary.count} 笔")
        if (summary.amountCents != 0L) parts.add("应发 ${FinanceModels.money(summary.amountCents)}")
        if (summary.taxCents != 0L) parts.add("扣税 ${FinanceModels.money(summary.taxCents)}")
        return parts.joinToString(" · ")
    }

    // ---------------- 按月分组 ----------------

    private fun addMonthGroup(parent: LinearLayout, group: FinanceMonth, showYear: Boolean) {
        val context = parent.context
        val card = Ui.addCard(parent)
        val header = Ui.row(context).apply {
            setPadding(0, 0, 0, Ui.dp(context, 2f))
        }
        header.addView(
            Ui.text(context, monthTitle(group, showYear), 13f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            Ui.text(
                context,
                "实发 ${FinanceModels.yuan(FinanceModels.summary(group.entries).paidCents)}",
                11.5f,
                R.color.text_hint,
            ),
        )
        card.addView(header)
        Ui.divider(card)
        group.entries.forEachIndexed { index, income ->
            addIncomeRow(card, income)
            if (index < group.entries.lastIndex) Ui.divider(card, 46f)
        }
    }

    private fun monthTitle(group: FinanceMonth, showYear: Boolean): String = when {
        !group.known -> "其他"
        showYear -> "${group.year}年${group.month}月"
        else -> "${group.month}月"
    }

    private fun addIncomeRow(parent: LinearLayout, income: FinanceIncome) {
        val context = parent.context
        val line = Ui.row(context).apply {
            isClickable = true
            isFocusable = true
            foreground = Ui.selectableForeground(context)
            setOnClickListener { showDetail(context, income) }
        }
        val left = Ui.column(context)
        left.addView(Ui.text(context, income.title, 13.5f, R.color.text_primary, bold = true))
        val subtitle = income.description.takeIf { it.isNotBlank() && it != income.project }
        if (subtitle != null) {
            left.addView(Ui.text(context, subtitle, 11.5f, R.color.text_hint), Ui.rowParams(context, 4f))
        }
        if (income.recordedDate.isNotBlank()) {
            left.addView(
                Ui.text(context, "${income.recordedDate} 录入", 10.5f, R.color.text_hint),
                Ui.rowParams(context, 4f),
            )
        }
        line.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val right = Ui.column(context).apply { gravity = Gravity.END }
        right.addView(
            Ui.text(context, FinanceModels.yuan(income.paidCents), 14.5f, R.color.green, bold = true),
        )
        val tax = income.taxCents ?: 0L
        if (tax != 0L) {
            right.addView(
                Ui.text(context, "扣税 ${FinanceModels.money(tax)}", 10.5f, R.color.text_hint),
                Ui.rowParams(context, 4f),
            )
        }
        line.addView(right)
        parent.addView(line, Ui.rowParams(context, 4f))
    }

    private fun addEmpty(parent: LinearLayout, context: Context, text: String) {
        val card = Ui.addCard(parent)
        card.addView(Ui.text(context, text, 12.5f, R.color.text_hint))
    }

    // ---------------- 详情 ----------------

    private fun showDetail(context: Context, income: FinanceIncome) {
        val card = Ui.column(context).apply {
            setPadding(Ui.dp(context, 22f), Ui.dp(context, 8f), Ui.dp(context, 22f), 0)
        }
        Ui.addKv(card, "应发金额", FinanceModels.yuan(income.amountCents))
        Ui.addKv(card, "扣税", FinanceModels.yuan(income.taxCents))
        Ui.addKv(card, "实发金额", FinanceModels.yuan(income.paidCents))
        if (income.taxRate.isNotBlank()) {
            val rate = if (income.taxRate.contains('%')) income.taxRate else income.taxRate + "%"
            Ui.addKv(card, "税率", rate)
        }
        val period = FinanceModels.periodOf(income)
        if (period != null) Ui.addKv(card, "所属年月", "${period.first}年${period.second}月")
        if (income.recordedDate.isNotBlank()) Ui.addKv(card, "录入日期", income.recordedDate)
        Ui.addKv(card, "流水号", income.id)
        MaterialAlertDialogBuilder(context)
            .setTitle(income.title)
            .setView(card)
            .setPositiveButton("关闭", null)
            .show()
    }

    private companion object {
        const val ALL_YEARS = ""
    }
}
