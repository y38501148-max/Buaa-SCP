package cn.edu.buaa.scp.ui

import android.content.Context
import android.view.Gravity
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
            Ui.chip(context, "‹ 上月", selected = false) { shiftMonth(-1) },
        )
        val label = Ui.text(context, Json.monthLabel(current), 17f, R.color.text_primary, bold = true)
        label.gravity = Gravity.CENTER
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val canNext = current != Json.currentMonth()
        val next = Ui.chip(context, "下月 ›", selected = false) { shiftMonth(1) }
        next.isEnabled = canNext
        next.alpha = if (canNext) 1f else 0.35f
        row.addView(next)
        card.addView(row)
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
        head.addView(
            Ui.text(context, "合计", 13f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Ui.text(context, Json.money(model.monthlyTotal), 18f, R.color.buaa_blue, bold = true))
        card.addView(head, Ui.rowParams(context, 8f))

        if (model.categories.isEmpty()) {
            card.addView(
                Ui.text(context, "本月暂无消费记录", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        model.categories.forEach { category ->
            card.addView(
                Ui.barRow(context, category.label, category.amount, category.fraction, R.color.buaa_blue),
            )
        }
    }

    private fun addRechargeCard(context: Context, model: BillsModel) {
        val card = Ui.addSection(body, "按月汇总（近半年）")
        if (model.rechargeError != null) {
            card.addView(
                Ui.text(context, "加载失败：${model.rechargeError}", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        if (model.rechargeRows.isEmpty()) {
            card.addView(
                Ui.text(context, "近半年暂无数据", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        model.rechargeRows.forEach { row ->
            val label = row.month + if (row.count.isBlank()) "" else "（${row.count} 笔）"
            card.addView(Ui.barRow(context, label, row.amount, row.fraction, R.color.green))
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
        card.addView(
            Ui.text(
                context,
                "合计 ${Json.money(model.trendTotal)}" +
                        if (model.trendCount.isBlank()) "" else "，共 ${model.trendCount} 笔",
                13f,
                R.color.text_secondary,
            ),
            Ui.rowParams(context, 6f),
        )
        if (model.trendRows.isEmpty()) {
            card.addView(
                Ui.text(context, "暂无月度数据", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        val chart = Ui.row(context).apply {
            gravity = Gravity.BOTTOM
            layoutParams = Ui.rowParams(context, 14f)
        }
        model.trendRows.forEach { row ->
            chart.addView(
                Ui.columnBar(context, row.label, row.amount, row.fraction, barHeightDp = 96),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        card.addView(chart)
    }
}
