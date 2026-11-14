package cn.edu.buaa.scp.ui

import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json
import org.json.JSONArray

/** 账单页：月度分类消费、按月充值、近半年支出趋势。全部为只读统计。 */
class BillsFragment : ScpFragment() {

    private var month = Json.currentMonth()

    override suspend fun load() {
        if (app.demoMode) {
            render(Demo.bills(month))
            return
        }
        val current = month
        val start6 = Json.shiftMonth(current, -5)
        val openid = app.openid()

        val bill = resultOf { app.withRecovery { app.api.monthlyBill(current, openid) } }
        val recharge = resultOf { app.withRecovery { app.api.rechargeByMonth(start6, current) } }
        val months = JSONArray().apply { Json.monthList(start6, current).forEach { put(it) } }
        val half = resultOf { app.withRecovery { app.api.halfYear(start6, current, months) } }

        if (bill.ok || recharge.ok || half.ok) app.markSynced()
        val model = BillsModel.build(
            month = current,
            bill = bill.value,
            monthlyError = bill.error,
            recharge = recharge.value,
            rechargeError = recharge.error,
            half = half.value,
            trendError = half.error,
        )
        if (!isAlive) return
        render(model)
    }

    private fun render(model: BillsModel) {
        clearBody()
        val context = requireContext()
        addMonthHeader(context, model.month)
        addMonthlyCard(context, model)
        addRechargeCard(context, model)
        addTrendCard(context, model)
    }

    private fun addMonthHeader(context: Context, current: String) {
        val card = Ui.addCard(body)
        val row = Ui.row(context)
        row.addView(
            Ui.iconButton(context, R.drawable.ic_chevron_left, "上一个月") { shiftMonth(-1) },
        )
        row.addView(
            monthSelector(context, current),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        val canNext = current != Json.currentMonth()
        row.addView(
            Ui.iconButton(context, R.drawable.ic_chevron_right, "下一个月", enabled = canNext) {
                shiftMonth(1)
            },
        )
        card.addView(row)
    }

    private fun monthSelector(context: Context, current: String): LinearLayout {
        val selector = Ui.row(context).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            foreground = Ui.selectableForeground(context)
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 6f), Ui.dp(context, 10f), Ui.dp(context, 6f))
            setOnClickListener {
                MonthPicker.show(context, month) { picked ->
                    month = picked
                    refresh()
                }
            }
        }
        selector.addView(
            Ui.text(context, Json.monthLabel(current), 16f, R.color.text_primary, bold = true),
        )
        selector.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = Ui.tint(Ui.color(context, R.color.text_hint))
                contentDescription = "选择年月"
            },
            LinearLayout.LayoutParams(Ui.dp(context, 18f), Ui.dp(context, 18f)).apply {
                marginStart = Ui.dp(context, 2f)
            },
        )
        return selector
    }

    private fun shiftMonth(delta: Int) {
        val target = Json.shiftMonth(month, delta)
        if (target > Json.currentMonth()) return
        month = target
        refresh()
    }

    private fun addMonthlyCard(context: Context, model: BillsModel) {
        val card = Ui.addSection(body, "本月消费")
        if (model.monthlyError != null) {
            card.addView(
                Ui.text(context, "加载失败：${model.monthlyError}", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }

        val head = Ui.row(context)
        val total = Ui.column(context)
        total.addView(Ui.text(context, "合计", 12f, R.color.text_secondary))
        total.addView(
            Ui.text(context, "¥ ${model.monthlyTotal}", 29f, R.color.text_primary, bold = true),
            Ui.rowParams(context, 3f),
        )
        head.addView(total, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (model.categories.isNotEmpty()) {
            head.addView(
                Ui.pill(
                    context,
                    "${model.categories.size} 类",
                    R.drawable.bg_pill_soft,
                    R.color.brand_blue,
                ),
            )
        }
        card.addView(head)

        if (model.categories.isEmpty()) {
            addEmpty(context, card, "本月暂无消费记录")
            return
        }

        val palette = intArrayOf(
            R.color.brand_blue,
            R.color.brand_teal,
            R.color.brand_gold,
            R.color.brand_blue_bright,
            R.color.orange,
        )
        model.categories.forEachIndexed { index, category ->
            card.addView(
                Ui.barRow(
                    context,
                    category.label,
                    category.amount,
                    category.fraction,
                    palette[index % palette.size],
                ),
            )
        }
    }

    private fun addRechargeCard(context: Context, model: BillsModel) {
        val card = Ui.addSection(body, "按月汇总")
        if (model.rechargeError != null) {
            card.addView(
                Ui.text(context, "加载失败：${model.rechargeError}", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        if (model.rechargeRows.isEmpty()) {
            addEmpty(context, card, "近半年暂无数据")
            return
        }
        card.addView(Ui.text(context, "近半年", 12f, R.color.text_secondary))
        model.rechargeRows.forEach { row ->
            val label = row.month + if (row.count.isBlank()) "" else " · ${row.count} 笔"
            card.addView(
                Ui.barRow(context, label, row.amount, row.fraction, R.color.brand_gold_deep),
            )
        }
    }

    private fun addTrendCard(context: Context, model: BillsModel) {
        val card = Ui.addSection(body, "近半年支出趋势")
        if (model.trendError != null) {
            card.addView(
                Ui.text(context, "加载失败：${model.trendError}", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }

        val head = Ui.row(context)
        head.addView(
            Ui.text(context, "合计", 12f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(
            Ui.text(context, "¥ ${model.trendTotal}", 15f, R.color.text_primary, bold = true),
        )
        if (model.trendCount.isNotBlank()) {
            head.addView(
                Ui.pill(
                    context,
                    "${model.trendCount} 笔",
                    R.drawable.bg_pill_soft,
                    R.color.brand_blue,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = Ui.dp(context, 8f) },
            )
        }
        card.addView(head)

        if (model.trendRows.isEmpty()) {
            addEmpty(context, card, "暂无月度数据")
            return
        }

        val chart = Ui.row(context).apply {
            gravity = Gravity.BOTTOM
            layoutParams = Ui.rowParams(context, 18f)
        }
        model.trendRows.forEach { row ->
            chart.addView(
                Ui.columnBar(context, row.label, row.amount, row.fraction, barHeightDp = 96),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        card.addView(chart)
    }

    private fun addEmpty(context: Context, parent: LinearLayout, text: String) {
        parent.addView(
            Ui.text(context, text, 12f, R.color.text_hint),
            Ui.rowParams(context, 10f),
        )
    }
}
