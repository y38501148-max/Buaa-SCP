package cn.edu.buaa.scp.ui

import android.content.Context
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.TradeItem
import cn.edu.buaa.scp.model.TradeType
import cn.edu.buaa.scp.model.TradesModel
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 流水页：按月份 + 交易类型分页拉取 `/selftrade/queryCardSelfTradeList`。
 *
 * tradetype 取值来自官方前端：-1 全部、1 消费、2 充值、3 补助、4 转账。
 */
class TradesFragment : ScpFragment() {

    private var month = Json.currentMonth()
    private var type = TradeType.ALL
    private var page = 0
    private var totalPages = -1
    private var lastPageCount = 0
    private val items = ArrayList<TradeItem>()
    private var summary = ""
    private var loadingMore = false
    private var moreJob: Job? = null

    override suspend fun load() {
        val current = month
        val currentType = type
        val (start, end) = Json.monthRange(current)

        val first = resultOf {
            app.withRecovery {
                app.api.trades(start, end, currentType.id, 0, TradesModel.PAGE_SIZE, app.openid())
            }
        }
        val pageData = TradesModel.parsePage(first.value)
        val parsed = pageData.items

        val summaryText = when (currentType) {
            TradeType.CONSUME -> resultOf {
                app.withRecovery { app.api.halfYear(current, current) }
            }.let { result ->
                result.value?.let(TradesModel::summaryForConsume)
                    ?: "汇总暂不可用"
            }

            TradeType.RECHARGE -> resultOf {
                app.withRecovery { app.api.rechargeByMonth(current, current) }
            }.let { result ->
                result.value?.let(TradesModel::summaryForRecharge)
                    ?: "汇总暂不可用"
            }

            else -> ""
        }

        if (first.ok) app.markSynced()
        items.clear()
        items.addAll(parsed)
        page = 0
        totalPages = pageData.totalPages
        lastPageCount = pageData.lastCount
        summary = summaryText
        if (!isAlive) return
        render(first.error)
    }

    private fun render(error: String?) {
        clearBody()
        val context = requireContext()
        addHeader(context)
        addTypeChips(context)
        addSummary(context)
        addList(context, error)
        addFooter(context)
    }

    private fun addHeader(context: Context) {
        val card = Ui.addCard(body)
        val row = Ui.row(context)
        row.addView(Ui.chip(context, "‹ 上月", selected = false) { shiftMonth(-1) })

        val label = Ui.text(context, Json.monthLabel(month), 17f, R.color.text_primary, bold = true)
        label.gravity = Gravity.CENTER
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val canNext = month != Json.currentMonth()
        val next = Ui.chip(context, "下月 ›", selected = false) { shiftMonth(1) }
        next.isEnabled = canNext
        next.alpha = if (canNext) 1f else 0.35f
        row.addView(next)
        card.addView(row)
    }

    private fun addTypeChips(context: Context) {
        val card = Ui.addCard(body)
        card.addView(Ui.text(context, "交易类型", 13f, R.color.text_secondary))

        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = Ui.row(context)
        scroll.addView(chipRow)
        TradeType.entries.forEach { entry ->
            val chip = Ui.chip(context, entry.label, selected = type == entry) { selectType(entry) }
            chipRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = Ui.dp(context, 8f) },
            )
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 8f) },
        )
    }

    private fun addSummary(context: Context) {
        if (summary.isBlank()) return
        val card = Ui.addCard(body)
        card.addView(Ui.text(context, summary, 13f, R.color.text_primary))
    }

    private fun addList(context: Context, error: String?) {
        val card = Ui.addSection(body, "流水明细")
        if (error != null) {
            card.addView(
                Ui.text(context, "加载失败：$error", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        if (items.isEmpty()) {
            card.addView(
                Ui.text(context, "未查询到记录", 12f, R.color.text_hint),
                Ui.rowParams(context, 6f),
            )
            return
        }
        items.forEachIndexed { index, item ->
            addTradeRow(context, card, item)
            if (index < items.lastIndex) Ui.divider(card)
        }
    }

    private fun addTradeRow(context: Context, parent: LinearLayout, item: TradeItem) {
        val line = Ui.row(context)
        val left = Ui.column(context)
        left.addView(Ui.text(context, item.merchant, 14f, R.color.text_primary))
        left.addView(Ui.text(context, item.time, 11f, R.color.text_hint), Ui.rowParams(context, 3f))
        line.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val amount = Ui.text(
            context,
            item.amount,
            15f,
            if (item.negative) R.color.red else R.color.green,
            bold = true,
        ).apply { gravity = Gravity.END }
        line.addView(amount)
        parent.addView(line, Ui.rowParams(context, 8f))
    }

    private fun addFooter(context: Context) {
        if (items.isEmpty()) return
        if (hasMore()) {
            val more = Ui.lightButton(context, if (loadingMore) "正在加载…" else "加载更多（${page + 1}/$totalPages）")
            more.isEnabled = !loadingMore
            more.setOnClickListener { loadMore() }
            body.addView(
                more,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 4f) },
            )
        } else {
            val end = Ui.text(context, "没有更多数据了", 12f, R.color.text_hint)
            end.gravity = Gravity.CENTER
            body.addView(end, Ui.rowParams(context, 8f))
        }
    }

    /**
     * 分页判断：接口返回 totalpage 时按它判断；未返回时（当前线上结构）按「本页是否取满」推断，
     * 加载到不满一页即视为结束。
     */
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
        render(null)

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
                render(result.error)
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
            render(null)
        }
    }

    private fun shiftMonth(delta: Int) {
        val target = Json.shiftMonth(month, delta)
        if (target > Json.currentMonth()) return
        month = target
        reset()
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
        summary = ""
        refresh()
    }

    override fun onDestroyView() {
        moreJob?.cancel()
        moreJob = null
        super.onDestroyView()
    }
}
