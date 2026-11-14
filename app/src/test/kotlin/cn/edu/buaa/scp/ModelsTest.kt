package cn.edu.buaa.scp

import cn.edu.buaa.scp.model.BillsModel
import cn.edu.buaa.scp.model.CardModel
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.model.TradeType
import cn.edu.buaa.scp.model.TradesModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            recharge = TestFixtures.json("rechargeByMonth.json"),
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
        assertEquals("2026-06", model.rechargeRows[0].month)
        assertEquals("¥10.00", model.rechargeRows[0].amount)
        assertEquals("2", model.rechargeRows[1].count)
        assertEquals(1.0f, model.rechargeRows[1].fraction, 0.001f)

        assertEquals(2, model.trendRows.size)
        assertEquals("06月", model.trendRows[0].label)
        assertEquals("60.00", model.trendTotal)
        assertEquals("3", model.trendCount)
    }

    @Test
    fun billsModel_computesTrendTotalWhenServerOmitsIt() {
        val model = BillsModel.build(
            month = "2026-11",
            bill = TestFixtures.json("monthlyBill.json"),
            monthlyError = null,
            recharge = TestFixtures.json("rechargeByMonth.json"),
            rechargeError = null,
            half = TestFixtures.json("halfYearNoTotal.json"),
            trendError = null,
        )
        // 官方接口缺失 totalamt 时应按 billList 求和：10.00 + 50.00
        assertEquals("60.00", model.trendTotal)
        assertEquals("", model.trendCount)
    }

    @Test
    fun summaryForConsume_fallsBackToBillListSum() {
        val summary = TradesModel.summaryForConsume(TestFixtures.json("halfYearNoTotal.json"))
        assertEquals("本月消费 ¥60.00", summary)
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

        val model = TradesModel("2026-11", TradeType.ALL, "", items, page = 0, totalPages = 3, error = null)
        assertTrue(model.hasMore)
        assertEquals(false, model.copy(page = 2).hasMore)
        assertEquals(false, model.copy(totalPages = 0).hasMore)
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
    fun tradesModel_summaries() {
        val consume = TradesModel.summaryForConsume(TestFixtures.json("halfYear.json"))
        assertEquals("本月消费 ¥60.00，共 3 笔", consume)

        val recharge = TradesModel.summaryForRecharge(TestFixtures.json("rechargeByMonth.json"))
        assertEquals("本月充值 ¥10.00，共 1 笔", recharge)

        val empty = TradesModel.summaryForRecharge(JSONObject())
        assertEquals("本月暂无充值记录", empty)
    }

    @Test
    fun tradeType_fromId() {
        assertEquals(TradeType.ALL, TradeType.fromId(-1))
        assertEquals(TradeType.CONSUME, TradeType.fromId(1))
        assertEquals(TradeType.RECHARGE, TradeType.fromId(2))
        assertEquals(TradeType.SUBSIDY, TradeType.fromId(3))
        assertEquals(TradeType.TRANSFER, TradeType.fromId(4))
        assertEquals(TradeType.ALL, TradeType.fromId(99))
    }

    @Test
    fun cardModel_mergesSources() {
        val model = CardModel.build(loss, null, pay, null, home, "20241234")
        assertEquals("张同学", model.name)
        assertEquals("20241234", model.idserial)
        assertEquals("正常", model.status)
        assertEquals("学生卡", model.cardType)
        assertEquals("123.45", model.balance)
        assertTrue(model.statusNormal)
        assertFalse(model.bothFailed)
        assertEquals(3, model.channels.size)
    }

    @Test
    fun cardModel_failureFlags() {
        val model = CardModel.build(null, "err1", null, "err2", null, "")
        assertTrue(model.bothFailed)
        assertEquals("未获取", model.cardType)
        assertTrue(model.balance.isEmpty())
    }
}
