package cn.edu.buaa.scp.ui

import android.content.Context
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.BillRecharge
import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.model.TradeItem
import cn.edu.buaa.scp.model.TradeType
import cn.edu.buaa.scp.model.TradesModel
import cn.edu.buaa.scp.net.ApiResult
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 账单页：把原「账单」统计与原「流水」明细合并为一页。
 *
 * - 概览：所选月份的消费 / 充值合计；
 * - 统计：消费分类、近半年充值、近半年支出趋势；
 * - 明细：按交易类型筛选并分页加载 `/selftrade/queryCardSelfTradeList`。
 *
 * 充值金额不用官方 `/bill/queryRechargeGroupByMonth`：缺少有效图表配置时它会返回消费口径，
 * 把消费金额当成充值；这里改为按 `tradetype=2` 的充值流水自行按月汇总。
 */
class BillsFragment : ScpFragment() {

    private var month = Json.currentMonth()
    private var type = TradeType.ALL
    private var page = 0
    private var totalPages = -1
    private var lastPageCount = 0
    private val items = ArrayList<TradeItem>()
    private var model: BillsModel? = null
    private var tradesError: String? = null
    private var loadingMore = false
    private var moreJob: Job? = null

    override suspend fun load() {
        val current = month
        val currentType = type
        if (app.demoMode) {
            model = Demo.bills(current)
            items.clear()
            items.addAll(
                Demo.trades().filter { item ->
                    when (currentType) {
                        TradeType.ALL -> true
                        TradeType.CONSUME -> item.negative
                        TradeType.RECHARGE -> item.merchant.contains("充值")
                        TradeType.SUBSIDY -> item.merchant.contains("补助")
                        TradeType.TRANSFER -> item.merchant.contains("转账")
                    }
                },
            )
            page = 0
            totalPages = 1
            lastPageCount = items.size
            tradesError = null
            render(currentType)
            return
        }

        val start6 = Json.shiftMonth(current, -5)
        val openid = app.openid()
        val (start, end) = Json.monthRange(current)
        val months = Json.monthList(start6, current)
        val monthArray = JSONArray().apply { months.forEach { put(it) } }
        val rechargeStart = Json.monthRange(start6).first

        val fetched = coroutineScope {
            val billJob = async { fetch { app.api.monthlyBill(current, openid) } }
            val halfJob = async { fetch { app.api.halfYear(start6, current, monthArray, openid) } }
            val tradeJob = async {
                fetch { app.api.trades(start, end, currentType.id, 0, TradesModel.PAGE_SIZE, openid) }
            }
            val rechargeJob = async { resultOf { fetchRechargeTrades(rechargeStart, end) } }
            Fetched(
                bill = billJob.await(),
                half = halfJob.await(),
                trades = tradeJob.await(),
                rechargeItems = rechargeJob.await(),
            )
        }
        val bill = fetched.bill
        val half = fetched.half
        val first = fetched.trades
        val rechargeItems = fetched.rechargeItems

        if (bill.ok || half.ok || first.ok || rechargeItems.ok) app.markSynced()
        val pageData = TradesModel.parsePage(first.value)
        val rechargeRows = BillRecharge.monthly(rechargeItems.value.orEmpty(), months)
        model = BillsModel.build(
            month = current,
            bill = bill.value,
            monthlyError = bill.error,
            rechargeRows = rechargeRows,
            rechargeError = rechargeItems.error,
            half = half.value,
            trendError = half.error,
        )
        items.clear()
        items.addAll(pageData.items)
        page = 0
        totalPages = pageData.totalPages
        lastPageCount = pageData.lastCount
        tradesError = first.error
        if (!isAlive) return
        render(currentType)
    }

    private suspend fun <T> fetch(block: suspend () -> T): ApiResult<T> =
        resultOf { app.withRecovery(block) }

    private data class Fetched(
        val bill: ApiResult<org.json.JSONObject>,
        val half: ApiResult<org.json.JSONObject>,
        val trades: ApiResult<org.json.JSONObject>,
        val rechargeItems: ApiResult<List<TradeItem>>,
    )

    /**
     * 拉取近半年的充值流水（tradetype=2）。
     *
     * 官方充值汇总接口在没有有效图表配置时返回消费口径，无法直接使用；充值流水本身是准确的，
     * 这里分页读完后交给 [BillRecharge] 按月汇总。
     */
    private suspend fun fetchRechargeTrades(startDate: String, endDate: String): List<TradeItem> {
        val all = ArrayList<TradeItem>()
        var page = 0
        while (page < 10) {
            val data = app.withRecovery {
                app.api.trades(startDate, endDate, 2, page, TradesModel.PAGE_SIZE, app.openid())
            }
            val parsed = TradesModel.parsePage(data)
            all.addAll(parsed.items)
            val more = if (parsed.totalPages > 0) {
                page < parsed.totalPages - 1
            } else {
                parsed.lastCount >= TradesModel.PAGE_SIZE
            }
            if (!more) break
            page++
        }
        return all
    }

    private fun render(currentType: TradeType = type) {
        clearBody()
        val context = requireContext()
        addMonthHeader(context)
        val current = model ?: return
        addOverviewCard(context, current)
        addCategoryCard(context, current)
        addRechargeCard(context, current)
        addTrendCard(context, current)
        addTradesSection(context, currentType)
    }

    // ---------------- 月份选择 ----------------

    private fun addMonthHeader(context: Context) {
        val card = Ui.addCard(body)
        val row = Ui.row(context)
        row.addView(
            Ui.iconButton(context, R.drawable.ic_chevron_left, "上一个月") { shiftMonth(-1) },
        )
        row.addView(
            monthSelector(context, month),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val canNext = month != Json.currentMonth()
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
                    reset()
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
        reset()
    }

    // ---------------- 概览 ----------------

    private fun addOverviewCard(context: Context, current: BillsModel) {
        val card = Ui.addCard(body)
        val row = Ui.row(context).apply { gravity = Gravity.TOP }
        addOverviewMetric(
            context,
            row,
            "消费",
            if (current.monthlyError != null) "—" else "¥ ${current.monthlyTotal}",
            countText(current.monthlyCount),
        )
        row.addView(
            android.view.View(context).apply { setBackgroundColor(Ui.color(context, R.color.divider)) },
            LinearLayout.LayoutParams(Ui.dp(context, 1f), Ui.dp(context, 34f)).apply {
                marginStart = Ui.dp(context, 16f)
                marginEnd = Ui.dp(context, 16f)
            },
        )
        addOverviewMetric(
            context,
            row,
            "充值",
            if (current.rechargeError != null) "—" else "¥ ${current.rechargeMonthAmount}",
            "",
        )
        card.addView(row)
    }

    private fun addOverviewMetric(
        context: Context,
        parent: LinearLayout,
        label: String,
        value: String,
        count: String,
    ) {
        val column = Ui.column(context)
        column.addView(Ui.text(context, label, 12f, R.color.text_secondary))
        column.addView(
            Ui.text(context, value, 24f, R.color.text_primary, bold = true),
            Ui.rowParams(context, 4f),
        )
        if (count.isNotEmpty()) {
            column.addView(Ui.text(context, count, 11f, R.color.text_hint))
        }
        parent.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun countText(count: String): String = if (count.isBlank()) "" else "$count 笔"

    // ---------------- 消费分类 ----------------

    private fun addCategoryCard(context: Context, current: BillsModel) {
        val card = Ui.addSection(body, "消费分类")
        if (current.monthlyError != null) {
            hint(context, card, "加载失败：${current.monthlyError}")
            return
        }
        if (current.categories.isEmpty()) {
            hint(context, card, "该月暂无消费")
            return
        }
        val palette = intArrayOf(
            R.color.brand_blue,
            R.color.brand_teal,
            R.color.brand_gold,
            R.color.brand_blue_bright,
            R.color.orange,
        )
        current.categories.forEachIndexed { index, category ->
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

    // ---------------- 充值汇总 ----------------

    private fun addRechargeCard(context: Context, current: BillsModel) {
        val card = Ui.addSection(body, "充值")
        if (current.rechargeError != null) {
            hint(context, card, "加载失败：${current.rechargeError}")
            return
        }
        if (current.rechargeRows.isEmpty()) {
            hint(context, card, "近半年暂无充值")
            return
        }
        current.rechargeRows.forEach { row ->
            val label = row.month + if (row.count.isBlank()) "" else " · ${row.count} 笔"
            card.addView(
                Ui.barRow(context, label, "¥ ${row.amount}", row.fraction, R.color.brand_gold_deep),
            )
        }
    }

    // ---------------- 支出趋势 ----------------

    private fun addTrendCard(context: Context, current: BillsModel) {
        val card = Ui.addSection(body, "支出趋势")
        if (current.trendError != null) {
            hint(context, card, "加载失败：${current.trendError}")
            return
        }
        val head = Ui.row(context)
        head.addView(
            Ui.text(context, "近半年合计", 12f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Ui.text(context, "¥ ${current.trendTotal}", 15f, R.color.text_primary, bold = true))
        if (current.trendCount.isNotBlank()) {
            head.addView(
                Ui.pill(
                    context,
                    "${current.trendCount} 笔",
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

        if (current.trendRows.isEmpty()) {
            hint(context, card, "暂无月度数据")
            return
        }
        val chart = Ui.row(context).apply {
            gravity = Gravity.BOTTOM
            layoutParams = Ui.rowParams(context, 18f)
        }
        current.trendRows.forEach { row ->
            chart.addView(
                Ui.columnBar(context, row.label, row.amount, row.fraction, barHeightDp = 96),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        card.addView(chart)
    }

    // ---------------- 明细 ----------------

    private fun addTradesSection(context: Context, currentType: TradeType) {
        Ui.sectionHeader(body, "明细")
        addTypeChips(context, currentType)

        val card = Ui.addCard(body)
        if (tradesError != null) {
            hint(context, card, "加载失败：$tradesError")
        } else if (items.isEmpty()) {
            hint(context, card, "该月暂无记录")
        } else {
            items.forEachIndexed { index, item ->
                addTradeRow(context, card, item)
                if (index < items.lastIndex) Ui.divider(card, 50f)
            }
        }
        addFooter(context)
    }

    private fun addTypeChips(context: Context, currentType: TradeType) {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val chipRow = Ui.row(context)
        scroll.addView(chipRow)
        TradeType.entries.forEachIndexed { index, entry ->
            val chip = Ui.chip(context, entry.label, selected = currentType == entry) {
                selectType(entry)
            }
            chip.contentDescription = "筛选：${entry.label}"
            chipRow.addView(
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
            ).apply { bottomMargin = Ui.dp(context, 12f) },
        )
    }

    private fun addTradeRow(context: Context, parent: LinearLayout, item: TradeItem) {
        val line = Ui.row(context)
        line.addView(
            Ui.iconTile(
                context,
                R.drawable.ic_trade,
                if (item.negative) R.color.red else R.color.green,
                if (item.negative) R.color.brand_red_soft else R.color.brand_green_soft,
                38,
            ),
        )

        val left = Ui.column(context)
        left.addView(Ui.text(context, item.merchant, 13.5f, R.color.text_primary, bold = true))
        left.addView(
            Ui.text(context, item.time, 11f, R.color.text_hint),
            Ui.rowParams(context, 3f),
        )
        line.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 12f)
            },
        )
        line.addView(
            Ui.text(
                context,
                item.amount,
                14.5f,
                if (item.negative) R.color.red else R.color.green,
                bold = true,
            ).apply { gravity = Gravity.END },
        )
        parent.addView(line, Ui.rowParams(context, 7f))
    }

    private fun addFooter(context: Context) {
        if (items.isEmpty() || tradesError != null) return
        if (hasMore()) {
            val more = Ui.lightButton(context, if (loadingMore) "正在加载…" else "加载更多")
            more.isEnabled = !loadingMore
            more.setOnClickListener { loadMore() }
            body.addView(
                more,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 4f) },
            )
        }
    }

    /** 分页判断：接口返回 totalpage 时按它判断；否则按「本页是否取满」推断。 */
    private fun hasMore(): Boolean = when {
        totalPages > 0 -> page < totalPages - 1
        totalPages == -1 -> lastPageCount >= TradesModel.PAGE_SIZE
        else -> false
    }

    private fun loadMore() {
        if (loadingMore || !hasMore()) return
        val next = page + 1
        val nextTotalPages = totalPages
        val current = month
        val currentType = type
        val (start, end) = Json.monthRange(current)
        loadingMore = true
        render(currentType)

        val scope = viewLifecycleOwner.lifecycleScope
        moreJob = scope.launch {
            val result = resultOf {
                app.withRecovery {
                    app.api.trades(start, end, currentType.id, next, TradesModel.PAGE_SIZE, app.openid())
                }
            }
            loadingMore = false
            if (!isAlive) return@launch
            if (!result.ok) {
                tradesError = result.error
                render(currentType)
                return@launch
            }
            val pageData = TradesModel.parsePage(result.value)
            items.addAll(pageData.items)
            page = next
            lastPageCount = pageData.lastCount
            totalPages = when {
                pageData.totalPages >= 0 -> pageData.totalPages
                pageData.lastCount < TradesModel.PAGE_SIZE -> next + 1
                else -> nextTotalPages
            }
            render(currentType)
        }
    }

    private fun selectType(value: TradeType) {
        if (type == value) return
        type = value
        reset()
    }

    private fun reset() {
        moreJob?.cancel()
        items.clear()
        page = 0
        totalPages = -1
        lastPageCount = 0
        tradesError = null
        refresh()
    }

    private fun hint(context: Context, parent: LinearLayout, text: String) {
        parent.addView(
            Ui.text(context, text, 12f, R.color.text_hint),
            Ui.rowParams(context, 6f),
        )
    }

    override fun onDestroyView() {
        moreJob?.cancel()
        moreJob = null
        super.onDestroyView()
    }
}
