package cn.edu.buaa.scp.ui

import cn.edu.buaa.scp.model.BillCategory
import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.model.CardModel
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.model.KeyValue
import cn.edu.buaa.scp.model.RechargeRow
import cn.edu.buaa.scp.model.TradeItem
import cn.edu.buaa.scp.model.TrendRow
import cn.edu.buaa.scp.util.Json
import java.util.Locale

/** Debug 构建的离线示例数据；release 不进入任何分支。 */
object Demo {

    private const val BALANCE = "286.50"

    fun home(): HomeModel = HomeModel(
        balance = BALANCE,
        idserial = "20250001",
        status = "正常",
        name = "示例同学",
        studentId = "20000000",
        cardType = "学生卡",
        notices = listOf("校园卡系统将于本周六 02:00 维护"),
        channels = channels(),
        updatedAt = System.currentTimeMillis(),
        homeError = null,
        payError = null,
    )

    fun bills(month: String): BillsModel {
        val start = Json.shiftMonth(month, -5)
        val trendAmounts = listOf("96.40", "128.20", "152.60", "118.80", "206.00", "134.40")
        val trendMax = 206.0
        val trendRows = Json.monthList(start, month).mapIndexed { index, value ->
            val amount = trendAmounts[index % trendAmounts.size]
            TrendRow(
                label = value.substring(5) + "月",
                amount = amount,
                fraction = (amount.toDouble() / trendMax).toFloat(),
            )
        }
        return BillsModel(
            month = month,
            monthlyTotal = "128.60",
            categories = listOf(
                BillCategory("食堂", "78.60", 0.61f),
                BillCategory("超市", "32.00", 0.25f),
                BillCategory("其他", "18.00", 0.14f),
            ),
            monthlyError = null,
            rechargeRows = listOf(
                RechargeRow("2026-06", "200.00", "1", 1f),
                RechargeRow("2026-08", "100.00", "1", 0.5f),
                RechargeRow("2026-09", "300.00", "2", 1f),
                RechargeRow("2026-10", "150.00", "1", 0.5f),
            ),
            rechargeError = null,
            trendRows = trendRows,
            trendTotal = trendRows.sumOf { it.amount.toDouble() }.let {
                String.format(Locale.US, "%.2f", it)
            },
            trendCount = "42",
            trendError = null,
        )
    }

    fun trades(): List<TradeItem> = listOf(
        TradeItem("北区学生第三食堂", "11-14 12:08", "-¥16.80", true),
        TradeItem("航味星语烘焙坊", "11-14 09:32", "-¥11.80", true),
        TradeItem("校园超市", "11-13 19:05", "-¥28.18", true),
        TradeItem("图书馆打印", "11-13 15:40", "-¥9.00", true),
        TradeItem("宿舍电费", "11-12 21:16", "-¥14.80", true),
        TradeItem("补助发放", "11-10 10:00", "+¥60.00", false),
    )

    const val TRADE_SUMMARY = "本月消费 ¥80.58 · 共 5 笔"

    fun card(): CardModel = CardModel(
        name = "示例同学",
        idserial = "20250001",
        studentId = "20000000",
        status = "正常",
        cardType = "学生卡",
        balance = BALANCE,
        channels = channels(),
        lossError = null,
        payError = null,
    )

    private fun channels(): List<KeyValue> = listOf(
        KeyValue("微信支付", "暂未开通"),
        KeyValue("支付宝", "暂未开通"),
        KeyValue("建设银行", "暂未开通"),
    )
}
