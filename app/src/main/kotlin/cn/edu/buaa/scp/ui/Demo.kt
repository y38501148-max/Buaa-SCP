package cn.edu.buaa.scp.ui

import cn.edu.buaa.scp.model.BillCategory
import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.model.FinanceIncome
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.model.KeyValue
import cn.edu.buaa.scp.model.RechargeRow
import cn.edu.buaa.scp.model.TradeItem
import cn.edu.buaa.scp.model.TrendRow
import cn.edu.buaa.scp.model.ZfwHome
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
                month = value,
                label = value.substring(5).trimStart('0') + "月",
                amount = amount,
                fraction = (amount.toDouble() / trendMax).toFloat(),
                count = "6",
            )
        }
        val rechargeRows = listOf(
            RechargeRow("2026-06", "200.00", "1", 1f),
            RechargeRow("2026-08", "100.00", "1", 0.5f),
            RechargeRow("2026-09", "300.00", "2", 1f),
            RechargeRow("2026-10", "150.00", "1", 0.5f),
        )
        return BillsModel(
            month = month,
            monthlyTotal = "128.60",
            monthlyCount = "18",
            categories = listOf(
                BillCategory("食堂", "78.60", 0.61f),
                BillCategory("超市", "32.00", 0.25f),
                BillCategory("其他", "18.00", 0.14f),
            ),
            monthlyError = null,
            rechargeRows = rechargeRows,
            rechargeError = null,
            rechargeMonthAmount = "150.00",
            rechargeMonthCount = "1",
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
        TradeItem("校园卡充值", "11-08 18:20", "+¥200.00", false),
    )

    /** 财务收入示例：覆盖两个年度、一次扣税与不同项目。 */
    fun financeIncomes(): List<FinanceIncome> = listOf(
        FinanceIncome(
            id = "2026090001",
            project = "研究生学业奖学金",
            description = "2026年学业奖学金",
            amountCents = 800000,
            taxCents = 0,
            paidCents = 800000,
            taxRate = "",
            recordedDate = "2026-09-12",
            year = "2026",
            month = "9",
        ),
        FinanceIncome(
            id = "2026100002",
            project = "助教酬金",
            description = "2026年秋季学期助教酬金",
            amountCents = 80050,
            taxCents = 2005,
            paidCents = 78045,
            taxRate = "2.51",
            recordedDate = "2026-10-08",
            year = "2026",
            month = "10",
        ),
        FinanceIncome(
            id = "2026060003",
            project = "助学金",
            description = "2026年6月助学金",
            amountCents = 60000,
            taxCents = 0,
            paidCents = 60000,
            taxRate = "0",
            recordedDate = "2026-06-15",
            year = "2026",
            month = "6",
        ),
        FinanceIncome(
            id = "2025120004",
            project = "科研补助",
            description = "2025年科研项目补助",
            amountCents = 120000,
            taxCents = 0,
            paidCents = 120000,
            taxRate = "",
            recordedDate = "2025-12-20",
            year = "2025",
            month = "12",
        ),
    )

    /** 首页网费卡片的示例数据。 */
    fun zfwHome(): ZfwHome = ZfwHome(
        username = "20000000",
        name = "示例同学",
        status = "正常",
        product = "学生",
        strategy = "免费70G / 70G-270G 收费 0.25 元/G",
        usedTraffic = "85.689G",
        usedDuration = "1177时32分51秒",
        freeRemain = "0byte",
        billedRemain = "415.181G",
        settleDate = "2026-12-01",
        onlineCount = 2,
    )

    private fun channels(): List<KeyValue> = listOf(
        KeyValue("微信支付", "暂未开通"),
        KeyValue("支付宝", "暂未开通"),
        KeyValue("建设银行", "暂未开通"),
    )
}
