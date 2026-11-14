package cn.edu.buaa.scp

import cn.edu.buaa.scp.model.BillRecharge
import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.model.TradeItem
import cn.edu.buaa.scp.model.TradeType
import cn.edu.buaa.scp.model.TradesModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * 页面数据模型测试。
 *
 * 夹具按真实接口返回结构构造（字段来自官方前端 bundle 的读取逻辑），
 * 敏感字段（姓名、学号、卡号、金额）已替换为虚构值。
 */
class ModelsTest {

    private val home: JSONObject = TestFixtures.json("homePage.json")
    private val pay: JSONObject = TestFixtures.json("openCardPay.json")
    private val loss: JSONObject = TestFixtures.json("reportLoss.json")

    @Test
    fun homeModel_mergesHomePayAndLoss() {
        val model = HomeModel.build(home, null, pay, null, loss, "20241234", 1_700_000_000_000L)
        assertEquals("123.45", model.balance)
        assertEquals("20241234", model.idserial)
        assertEquals("正常", model.status)
        assertEquals("张同学", model.name)
        assertEquals("学生卡", model.cardType)
        assertTrue(model.statusNormal)
        assertFalse(model.bothFailed)
        assertEquals(2, model.notices.size)
        assertEquals("开学充值提醒", model.notices[0])
        assertEquals("余额不足提醒", model.notices[1])
        assertEquals(3, model.channels.size)
        assertEquals("暂未开通", model.channels[0].value)
    }

    @Test
    fun homeModel_fallsBackToOpenCardPay() {
        val model = HomeModel.build(null, "首页接口超时", pay, null, null, "20241234", 0L)
        assertEquals("123.45", model.balance)
        assertEquals("20241234", model.idserial)
        assertTrue(model.homeError != null)
        assertFalse(model.bothFailed)
        assertTrue(model.notices.isEmpty())
    }

    @Test
    fun homeModel_marksBothFailed() {
        val model = HomeModel.build(null, "err1", null, "err2", null, "", 0L)
        assertTrue(model.bothFailed)
        assertEquals("", model.balance)
        assertEquals("未获取", model.cardType)
    }

    @Test
    fun billsModel_buildsCategoriesRechargeAndTrend() {
        val model = BillsModel.build(
            month = "2026-11",
            bill = TestFixtures.json("monthlyBill.json"),
            monthlyError = null,
            rechargeRows = rechargeRows(),
            rechargeError = null,
            half = TestFixtures.json("halfYear.json"),
            trendError = null,
        )
        assertEquals("80.58", model.monthlyTotal)
        assertEquals(2, model.categories.size)
        assertEquals("食堂消费", model.categories[0].label)
        assertEquals("¥60.58", model.categories[0].amount)
        assertEquals(0.75f, model.categories[0].fraction, 0.01f)

        assertEquals(2, model.rechargeRows.size)
        // 月份倒序：09 月在前，06 月在后
        assertEquals("2026-09", model.rechargeRows[0].month)
        assertEquals("50.00", model.rechargeRows[0].amount)
        assertEquals("1", model.rechargeRows[0].count)
        assertEquals(1.0f, model.rechargeRows[0].fraction, 0.001f)
        assertEquals("2026-06", model.rechargeRows[1].month)
        assertEquals("10.00", model.rechargeRows[1].amount)

        // 所选月份 2026-11 没有充值流水，合计为 0.00
        assertEquals("0.00", model.rechargeMonthAmount)

        assertEquals(2, model.trendRows.size)
        assertEquals("6月", model.trendRows[0].label)
        assertEquals("60.00", model.trendTotal)
        assertEquals("3", model.trendCount)
    }

    @Test
    fun billsModel_picksSelectedMonthRechargeTotal() {
        val model = BillsModel.build(
            month = "2026-09",
            bill = TestFixtures.json("monthlyBill.json"),
            monthlyError = null,
            rechargeRows = rechargeRows(),
            rechargeError = null,
            half = TestFixtures.json("halfYear.json"),
            trendError = null,
        )
        assertEquals("50.00", model.rechargeMonthAmount)
        assertEquals("1", model.rechargeMonthCount)
    }

    @Test
    fun billsModel_computesTrendTotalWhenServerOmitsIt() {
        val model = BillsModel.build(
            month = "2026-11",
            bill = TestFixtures.json("monthlyBill.json"),
            monthlyError = null,
            rechargeRows = rechargeRows(),
            rechargeError = null,
            half = TestFixtures.json("halfYearNoTotal.json"),
            trendError = null,
        )
        // 官方接口缺失 totalamt 时应按 billList 求和：10.00 + 50.00
        assertEquals("60.00", model.trendTotal)
        assertEquals("", model.trendCount)
    }

    @Test
    fun tradesModel_parsesRowsAndPaging() {
        val page = TradesModel.parsePage(TestFixtures.json("trades.json"))
        val items = page.items
        assertEquals(3, items.size)
        assertEquals(3, page.totalPages)
        assertEquals("学一食堂", items[0].merchant)
        assertEquals("-¥12.30", items[0].amount)
        assertTrue(items[0].negative)
        assertEquals("+¥50.00", items[1].amount)
        assertFalse(items[1].negative)
        assertEquals("2026-11-12 12:30:00", items[0].time)
    }

    @Test
    fun tradesModel_parsesFlatStructureWithoutPaging() {
        // 当前线上结构：{"data":{"data":[...]}}，无 totalpage
        val page = TradesModel.parsePage(TestFixtures.json("tradesFlat.json"))
        assertEquals(5, page.items.size)
        assertEquals(5, page.lastCount)
        assertEquals(-1, page.totalPages)
        assertEquals("第一食堂", page.items[0].merchant)
        assertEquals("-¥16.80", page.items[0].amount)
        assertEquals("2026-11-13 11:31:48", page.items[0].time)
        assertTrue(page.items.all { it.negative })
    }

    @Test
    fun billRecharge_groupsByMonthFromRechargeTrades() {
        val months = listOf("2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11")
        val items = listOf(
            TradeItem("校园卡充值", "2026-09-10 09:00:00", "+¥200.00", false, "200.00", "2026-09"),
            TradeItem("校园卡充值", "2026-09-20 09:00:00", "+¥100.00", false, "100.00", "2026-09"),
            TradeItem("校园卡充值", "2026-11-08 18:20:00", "+¥14.00", false, "14.00", "2026-11"),
            // 不在近半年范围内，应被忽略
            TradeItem("校园卡充值", "2025-12-01 09:00:00", "+¥50.00", false, "50.00", "2025-12"),
        )
        val rows = BillRecharge.monthly(items, months)
        assertEquals(2, rows.size)
        assertEquals("2026-11", rows[0].month)
        assertEquals("14.00", rows[0].amount)
        assertEquals("1", rows[0].count)
        assertEquals(0.0467f, rows[0].fraction, 0.001f)
        assertEquals("2026-09", rows[1].month)
        assertEquals("300.00", rows[1].amount)
        assertEquals("2", rows[1].count)
        assertEquals(1.0f, rows[1].fraction, 0.001f)
    }

    @Test
    fun tradeItem_carriesRawAmountAndMonth() {
        val page = TradesModel.parsePage(TestFixtures.json("trades.json"))
        assertEquals("50.00", page.items[1].yuan)
        assertEquals("2026-11", page.items[1].month)
        assertEquals("-12.30", page.items[0].yuan)
    }

    private fun rechargeRows(): List<cn.edu.buaa.scp.model.RechargeRow> = BillRecharge.monthly(
        listOf(
            TradeItem("校园卡充值", "2026-09-10 09:00:00", "+¥50.00", false, "50.00", "2026-09"),
            TradeItem("校园卡充值", "2026-06-10 09:00:00", "+¥10.00", false, "10.00", "2026-06"),
        ),
        listOf("2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"),
    )

    @Test
    fun tradeType_fromId() {
        assertEquals(TradeType.ALL, TradeType.fromId(-1))
        assertEquals(TradeType.CONSUME, TradeType.fromId(1))
        assertEquals(TradeType.RECHARGE, TradeType.fromId(2))
        assertEquals(TradeType.SUBSIDY, TradeType.fromId(3))
        assertEquals(TradeType.TRANSFER, TradeType.fromId(4))
        assertEquals(TradeType.ALL, TradeType.fromId(99))
    }
}
