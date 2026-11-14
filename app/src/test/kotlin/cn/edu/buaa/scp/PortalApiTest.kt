package cn.edu.buaa.scp

import cn.edu.buaa.scp.net.PortalApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 门户网费余额格式化测试。 */
class PortalApiTest {

    @Test
    fun formatsDoubleFromPortal() {
        assertEquals("103.79", PortalApi.formatAmount(103.79000000000001))
        assertEquals("0.00", PortalApi.formatAmount(0.0))
        assertEquals("200.00", PortalApi.formatAmount(200))
    }

    @Test
    fun formatsStringAmount() {
        assertEquals("429.17", PortalApi.formatAmount("429.17"))
        assertEquals("123.40", PortalApi.formatAmount("123.4"))
    }

    @Test
    fun rejectsMissingOrInvalidValue() {
        assertNull(PortalApi.formatAmount(null))
        assertNull(PortalApi.formatAmount(JSONObject.NULL))
        assertNull(PortalApi.formatAmount("abc"))
    }
}
