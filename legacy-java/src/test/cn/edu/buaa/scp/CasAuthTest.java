package cn.edu.buaa.scp;

import org.junit.Test;

import cn.edu.buaa.scp.net.CasAuth;
import cn.edu.buaa.scp.net.CookieJar;
import cn.edu.buaa.scp.net.Html;
import cn.edu.buaa.scp.net.HttpEngine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** CAS 链路参数构造测试（不发起网络请求）。 */
public class CasAuthTest {

    private final CasAuth auth = new CasAuth(new HttpEngine(new CookieJar()));

    @Test
    public void serviceUrlUsesSentinelOpenid() {
        assertEquals("https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/6/auth",
                CasAuth.SERVICE);
        assertTrue(CasAuth.SERVICE.contains(CasAuth.SENTINEL_OPENID));
    }

    @Test
    public void authUrlContainsEncodedServiceAndFlag() {
        String url = auth.authUrl(true);
        assertEquals("https://sso.buaa.edu.cn/login?service="
                + Html.urlEncode(CasAuth.SERVICE) + "&noAutoRedirect=1", url);
        assertFalse(url.contains("openid=1"));
        assertTrue(url.contains("112233"));
        assertTrue(url.contains("noAutoRedirect=1"));
    }

    @Test
    public void authUrlCanOmitNoAutoRedirect() {
        assertFalse(auth.authUrl(false).contains("noAutoRedirect"));
    }

    @Test
    public void maskKeepsEdges() {
        assertEquals("1F76…1234", CasAuth.mask("1F76D5049ABCDEF01234"));
        assertEquals("1***X", CasAuth.mask("1ABX"));
    }
}
