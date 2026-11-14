package cn.edu.buaa.scp;

import org.junit.Test;

import cn.edu.buaa.scp.util.Json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 金额、月份、脱敏等格式化工具测试。 */
public class JsonTest {

    @Test
    public void fenToYuan() {
        assertEquals("51.80", Json.fenToYuan("5180"));
        assertEquals("-12.30", Json.fenToYuan("-1230"));
        assertEquals("123.45", Json.fenToYuan("123.45"));
        assertEquals("0.00", Json.fenToYuan(""));
        assertEquals("0.00", Json.fenToYuan(null));
        assertEquals("-5.00", Json.fenToYuan("-500"));
    }

    @Test
    public void yuan_normalizesScale() {
        assertEquals("80.58", Json.yuan("80.58"));
        assertEquals("80.00", Json.yuan("80"));
        assertEquals("0.00", Json.yuan(""));
        assertEquals("¥123.45", Json.money("123.45"));
    }

    @Test
    public void monthArithmetic() {
        assertEquals("2025-12", Json.shiftMonth("2026-01", -1));
        assertEquals("2027-01", Json.shiftMonth("2026-12", 1));
        assertEquals("2026-11", Json.shiftMonth("2026-11", 0));
        assertEquals("2026年11月", Json.monthLabel("2026-11"));
    }

    @Test
    public void monthRange() {
        String[] feb2026 = Json.monthRange("2026-02");
        assertEquals("2026-02-01", feb2026[0]);
        assertEquals("2026-02-28", feb2026[1]);

        String[] feb2024 = Json.monthRange("2024-02");
        assertEquals("2024-02-29", feb2024[1]);

        String[] nov = Json.monthRange("2026-11");
        assertEquals("2026-11-30", nov[1]);
    }

    @Test
    public void cardType() {
        assertEquals("学生卡", Json.cardType("BKS"));
        assertEquals("员工卡", Json.cardType("YG"));
        assertEquals("访客卡", Json.cardType("FK"));
        assertEquals("未获取", Json.cardType(""));
        assertEquals("XYZ", Json.cardType("XYZ"));
    }

    @Test
    public void maskHidesMiddle() {
        assertEquals("20****34", Json.mask("20241234"));
        assertEquals("12****56", Json.mask("123456"));
        assertEquals("", Json.mask(""));
    }

    @Test
    public void blankHelpers() {
        assertTrue(Json.isBlank(null));
        assertTrue(Json.isBlank("  "));
        assertFalse(Json.isBlank("x"));
        assertEquals("—", Json.or("", "—"));
        assertEquals("x", Json.or("x", "—"));
    }
}
