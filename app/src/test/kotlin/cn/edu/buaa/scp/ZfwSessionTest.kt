package cn.edu.buaa.scp

import cn.edu.buaa.scp.store.ZfwSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 校园网会话快照测试：Cookie 解析与持久化字段。 */
class ZfwSessionTest {

    @Test
    fun parsesCookieHeader() {
        val pairs = ZfwSession.parseCookieHeader(
            "PHPSESSID_8800=abc123; _csrf-8800=xyz%3A2; __services_identity=id",
        )
        assertEquals(3, pairs.size)
        assertEquals("PHPSESSID_8800" to "abc123", pairs[0])
        assertEquals("_csrf-8800" to "xyz%3A2", pairs[1])
    }

    @Test
    fun ignoresMalformedCookieItems() {
        val pairs = ZfwSession.parseCookieHeader(" broken ; =empty; a=b")
        assertEquals(1, pairs.size)
        assertEquals("a", pairs[0].first)
        assertEquals("b", pairs[0].second)
    }

    @Test
    fun jsonRoundTrip() {
        val session = ZfwSession(
            username = "20250001",
            name = "张同学",
            cookie = "PHPSESSID_8800=abc",
            savedAt = 100L,
            lastSyncAt = 200L,
        )
        val restored = ZfwSession.fromJson(session.toJson())!!
        assertEquals(session, restored)
    }

    @Test
    fun dropsSessionWithoutCookie() {
        val json = JSONObject().put("username", "20250001").put("cookie", "")
        assertNull(ZfwSession.fromJson(json))
        assertTrue(ZfwSession().hasCookie.not())
    }
}
