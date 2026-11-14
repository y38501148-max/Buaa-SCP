package cn.edu.buaa.scp.model

import cn.edu.buaa.scp.util.Json
import cn.edu.buaa.scp.util.objects
import cn.edu.buaa.scp.util.pathArray
import cn.edu.buaa.scp.util.pathObject
import cn.edu.buaa.scp.util.pathString
import org.json.JSONObject

/** 通用「标签 / 值」展示项。 */
data class KeyValue(
    val key: String,
    val value: String,
    val accent: Boolean = false,
)

// ---------------- 首页 ----------------

data class HomeModel(
    val balance: String,
    val idserial: String,
    val status: String,
    val name: String,
    val studentId: String,
    val cardType: String,
    val notices: List<String>,
    val channels: List<KeyValue>,
    val updatedAt: Long,
    val homeError: String?,
    val payError: String?,
) {
    val bothFailed: Boolean get() = homeError != null && payError != null
    val statusNormal: Boolean
        get() = status.isBlank() ||
                status.contains("正常") ||
                status.lowercase().contains("normal")

    companion object {
        fun build(
            home: JSONObject?,
            homeError: String?,
            pay: JSONObject?,
            payError: String?,
            loss: JSONObject?,
            studentId: String,
            updatedAt: Long,
        ): HomeModel {
            val balance = home?.pathString("data", "cardinfo", "cardbal").orEmpty()
                .ifEmpty { pay?.pathString("data", "cardPay", "cardbal").orEmpty() }
            val status = home?.pathString("data", "cardinfo", "accstatusStr").orEmpty()
                .ifEmpty { loss?.pathString("data", "accstatusStr").orEmpty() }
            val idserial = home?.pathString("data", "cardinfo", "idserial").orEmpty()
                .ifEmpty { pay?.pathString("data", "cardPay", "idserial").orEmpty() }
                .ifEmpty { loss?.pathString("data", "idserial").orEmpty() }
            val name = loss?.pathString("data", "username").orEmpty()
            val cardType = Json.cardType(home?.pathString("data", "pcodeCard"))
            val notices = home?.pathArray("data", "messagenotice")
                ?.objects()
                ?.mapNotNull { item ->
                    item.pathString("title")
                        .ifBlank { item.pathString("content") }
                        .ifBlank { item.pathString("name") }
                        .ifBlank { item.pathString("message") }
                        .ifBlank { null }
                }
                .orEmpty()
                .take(5)
            return HomeModel(
                balance = Json.yuan(balance).takeIf { balance.isNotEmpty() } ?: "",
                idserial = idserial,
                status = status,
                name = name,
                studentId = studentId,
                cardType = cardType,
                notices = notices,
                channels = channelsOf(pay),
                updatedAt = updatedAt,
                homeError = homeError,
                payError = payError,
            )
        }

        private fun channelsOf(pay: JSONObject?): List<KeyValue> {
            val jsonmsg = pay?.pathObject("data", "jsonmsg") ?: return emptyList()
            return listOf(
                KeyValue("微信支付", jsonmsg.pathString("wechat").ifBlank { "未开放" }),
                KeyValue("支付宝", jsonmsg.pathString("alipay").ifBlank { "未开放" }),
                KeyValue("建设银行", jsonmsg.pathString("abc").ifBlank { "未开放" }),
            )
        }
    }
}

// ---------------- 网费（zfw） ----------------

/** 首页「网费」卡片的数据：门户余额 + zfw 流量状态。 */
data class NetworkModel(
    val balance: String?,
    val balanceError: String?,
    val zfw: ZfwState,
)

/** 首页「网费」卡片的三种状态。 */
sealed class ZfwState {
    /** 尚未登录校园网（或曾登录但会话已失效）。 */
    data class NotLoggedIn(val expired: Boolean = false) : ZfwState()

    /** 已登录且成功拿到首页数据。 */
    data class LoggedIn(val home: ZfwHome) : ZfwState()

    /** 已登录但本次拉取失败。 */
    data class Failed(val message: String) : ZfwState()
}

/**
 * 校园网自助服务首页数据。
 *
 * 字段直接对应深澜页面上的「用户信息」与「产品信息」表格；
 * 官方单位为 G / byte，展示前由 [billedRemainText] 等函数统一成一位/两位小数。
 */
data class ZfwHome(
    val username: String,
    val name: String,
    val status: String,
    val product: String,
    val strategy: String,
    val usedTraffic: String,
    val usedDuration: String,
    val freeRemain: String,
    val billedRemain: String,
    val settleDate: String,
    val onlineCount: Int,
) {
    val statusNormal: Boolean
        get() = status.isBlank() || status.contains("正常")

    /** 主展示：计费流量剩余，例如 "415.18 G"。 */
    val billedRemainText: String get() = trafficText(billedRemain)

    /** 副展示：已用流量，例如 "85.69 G"。 */
    val usedTrafficText: String get() = trafficText(usedTraffic)

    /** 免费流量剩余，0byte 显示为 "0"。 */
    val freeRemainText: String get() = trafficText(freeRemain)

    companion object {
        /**
         * 流量文本格式化：保留单位、数值最多两位小数；`0byte` 直接显示 0。
         *
         * 官方页面常见形式：`85.689G`、`0byte`、`1.5T`、`512M`。
         */
        fun trafficText(raw: String?): String {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return "—"
            if (text.equals("0byte", ignoreCase = true) || text == "0") return "0"
            val match = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)$""").find(text)
                ?: return text
            val number = match.groupValues[1].toBigDecimalOrNull() ?: return text
            val rounded = number.setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            val unit = match.groupValues[2]
            val displayUnit = when (unit.lowercase(java.util.Locale.US)) {
                "byte", "bytes", "b" -> "B"
                "k", "kb" -> "KB"
                "m", "mb" -> "MB"
                "g", "gb" -> "GB"
                "t", "tb" -> "TB"
                else -> unit
            }
            return "$rounded $displayUnit"
        }
    }
}

// ---------------- 账单 ----------------

data class BillCategory(
    val label: String,
    val amount: String,
    val fraction: Float,
)

data class RechargeRow(
    val month: String,
    val amount: String,
    val count: String,
    val fraction: Float,
)

data class TrendRow(
    val month: String = "",
    val label: String,
    val amount: String,
    val fraction: Float,
    val count: String = "",
)

/**
 * 账单页模型：月度消费分类 + 本月消费/充值合计 + 近半年趋势。
 *
 * 「本月消费」来自 `queryUserMonthlybill` 的 sumamt，消费趋势来自
 * `queryUserMonthlybillHalfOfYear`。充值不用 `/bill/queryRechargeGroupByMonth`：
 * 官方接口在缺少有效图表配置（chartId）时会返回消费口径的数据，把消费金额当成充值；
 * 客户端改用充值流水（`queryCardSelfTradeList` 且 `tradetype=2`）自行按月汇总，口径确定。
 */
data class BillsModel(
    val month: String,
    val monthlyTotal: String,
    val monthlyCount: String,
    val categories: List<BillCategory>,
    val monthlyError: String?,
    val rechargeRows: List<RechargeRow>,
    val rechargeError: String?,
    val rechargeMonthAmount: String,
    val rechargeMonthCount: String,
    val trendRows: List<TrendRow>,
    val trendTotal: String,
    val trendCount: String,
    val trendError: String?,
) {
    companion object {
        fun build(
            month: String,
            bill: JSONObject?,
            monthlyError: String?,
            rechargeRows: List<RechargeRow>,
            rechargeError: String?,
            half: JSONObject?,
            trendError: String?,
        ): BillsModel {
            val categories = buildCategories(bill)
            val trendRows = buildTrendRows(half)
            val monthTrend = trendRows.firstOrNull { it.month == month }
            val rawTotal = bill?.pathString("resultData", "sumamt").orEmpty()
            val rechargeMonth = rechargeRows.firstOrNull { it.month == month }
            return BillsModel(
                month = month,
                monthlyTotal = if (rawTotal.isBlank()) monthTrend?.amount ?: "0.00" else Json.yuan(rawTotal),
                monthlyCount = monthTrend?.count.orEmpty(),
                categories = categories,
                monthlyError = monthlyError,
                rechargeRows = rechargeRows,
                rechargeError = rechargeError,
                rechargeMonthAmount = rechargeMonth?.amount ?: "0.00",
                rechargeMonthCount = rechargeMonth?.count.orEmpty(),
                trendRows = trendRows,
                trendTotal = trendTotalOf(half, trendRows),
                trendCount = half?.pathString("resultData", "totalcount").orEmpty(),
                trendError = trendError,
            )
        }

        private fun buildCategories(bill: JSONObject?): List<BillCategory> {
            val list = bill?.pathArray("resultData", "billList")?.objects() ?: return emptyList()
            val total = Json.numberOrNull(bill.pathString("resultData", "sumamt")) ?: 0.0
            return list.map { row ->
                val rawName = row.pathString("name")
                val label = rawName.substringBefore(':').substringBefore('：').ifBlank { rawName }
                val value = row.pathString("value")
                val amount = Json.numberOrNull(value) ?: 0.0
                BillCategory(
                    label = label,
                    amount = Json.money(value),
                    fraction = if (total > 0) (amount / total).toFloat() else 0f,
                )
            }
        }

        /**
         * 趋势合计：官方接口在缺失 chartId 时可能只返回 billList、不返回 totalamt，
         * 此时用 billList 自行求和，避免页面显示「合计 ¥0.00」。
         */
        private fun trendTotalOf(half: JSONObject?, rows: List<TrendRow>): String {
            val rawTotal = half?.pathString("resultData", "totalamt").orEmpty()
            val total = Json.numberOrNull(Json.fenToYuan(rawTotal)) ?: 0.0
            if (total > 0 || rows.isEmpty()) return Json.fenToYuan(rawTotal)
            val sum = rows.sumOf { Json.numberOrNull(it.amount) ?: 0.0 }
            return String.format(java.util.Locale.US, "%.2f", sum)
        }

        /** 近半年按月消费：金额单位分，按月份正序供柱状图使用。 */
        private fun buildTrendRows(half: JSONObject?): List<TrendRow> {
            val list = half?.pathArray("resultData", "billList")?.objects() ?: return emptyList()
            val amounts = list.map { Json.fenToYuan(it.pathString("txamt")) to it }
            val max = amounts.maxOfOrNull { Json.numberOrNull(it.first) ?: 0.0 } ?: 0.0
            return amounts.map { (amount, row) ->
                val date = row.pathString("txdate")
                val value = Json.numberOrNull(amount) ?: 0.0
                TrendRow(
                    month = date,
                    label = if (date.length >= 7) date.substring(5, 7).trimStart('0') + "月" else date,
                    amount = Json.yuan(amount),
                    fraction = if (max > 0) (value / max).toFloat() else 0f,
                    count = row.pathString("countnum"),
                )
            }.sortedBy { it.month }
        }
    }
}

/**
 * 充值按月汇总：数据来自充值流水（`tradetype=2`），不依赖官方充值汇总接口。
 *
 * 官方 `/bill/queryRechargeGroupByMonth` 在没有有效图表配置时会返回消费口径的数据，
 * 因此客户端只信任充值流水：金额单位元，按流水时间归月。
 */
object BillRecharge {

    fun monthly(entries: List<TradeItem>, months: List<String>): List<RechargeRow> {
        val bucket = LinkedHashMap<String, Pair<Double, Int>>()
        for (entry in entries) {
            val month = entry.month
            if (month.isEmpty() || month !in months) continue
            val value = entry.yuan.removePrefix("-").toDoubleOrNull() ?: continue
            val previous = bucket[month] ?: (0.0 to 0)
            bucket[month] = (previous.first + value) to (previous.second + 1)
        }
        val max = bucket.values.maxOfOrNull { it.first } ?: 0.0
        return bucket.entries
            .sortedByDescending { it.key }
            .map { (month, value) ->
                RechargeRow(
                    month = month,
                    amount = String.format(java.util.Locale.US, "%.2f", value.first),
                    count = value.second.toString(),
                    fraction = if (max > 0) (value.first / max).toFloat() else 0f,
                )
            }
    }
}

// ---------------- 流水 ----------------

enum class TradeType(val id: Int, val label: String) {
    ALL(-1, "全部"),
    CONSUME(1, "消费"),
    RECHARGE(2, "充值"),
    SUBSIDY(3, "补助"),
    TRANSFER(4, "转账"),
    ;

    companion object {
        fun fromId(id: Int): TradeType = entries.firstOrNull { it.id == id } ?: ALL
    }
}

data class TradeItem(
    val merchant: String,
    val time: String,
    val amount: String,
    val negative: Boolean,
    /** 原始金额（元，保留正负号），用于按类型汇总。 */
    val yuan: String = "",
    /** 交易所属月份（yyyy-MM），用于充值按月汇总。 */
    val month: String = "",
)

/** 流水接口一页数据。 */
data class TradesPage(
    val items: List<TradeItem>,
    val totalPages: Int,
    val lastCount: Int,
)

/** 流水接口解析与分页常量。 */
object TradesModel {
    const val PAGE_SIZE = 10

    /**
     * 解析某一页。
     *
     * 实测两种返回结构并存：
     * - `{"resultData":{"rows":[…],"totalpage":"3"}}`（官方 bundle 里读取的结构）
     * - `{"data":{"data":[…]}}`（当前线上接口，扁平数组、无分页字段）
     *
     * 因此两种都兼容；缺失 totalpage 时 totalPages=-1，由调用方按「返回条数是否满一页」推断。
     */
    fun parsePage(data: JSONObject?): TradesPage {
        if (data == null) return TradesPage(emptyList(), -1, 0)
        val node = data.pathObject("resultData") ?: data.pathObject("data")
        val rows = node?.pathArray("rows") ?: node?.pathArray("data")
        val items = rows?.objects()?.map { row ->
            val raw = row.pathString("txamt")
            val yuan = Json.fenToYuan(raw)
            val negative = yuan.startsWith("-")
            val abs = if (negative) yuan.removePrefix("-") else yuan
            val time = row.pathString("txdate").ifBlank { row.pathString("txtime") }
            TradeItem(
                merchant = row.pathString("mername")
                    .ifBlank { row.pathString("txname") }
                    .ifBlank { row.pathString("summary") }
                    .ifBlank { "未知商户" },
                time = time,
                amount = if (negative) "-¥$abs" else "+¥$abs",
                negative = negative,
                yuan = yuan,
                month = if (time.length >= 7) time.substring(0, 7) else "",
            )
        }.orEmpty()
        val totalPages = node?.pathString("totalpage")?.toIntOrNull() ?: -1
        return TradesPage(items, totalPages, items.size)
    }
}
