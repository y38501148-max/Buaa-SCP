package cn.edu.buaa.scp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import cn.edu.buaa.scp.net.CookieJar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Cookie 存储测试：跨域隔离、路径匹配、Secure、过期删除。 */
public class CookieJarTest {

    @Test
    public void absorbAndHeaderPerHost() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://sso.buaa.edu.cn/login?service=x",
                Collections.singletonList("CASTGC=TGT-abc; Path=/; Secure; HttpOnly"));

        String ssoHeader = jar.header("https://sso.buaa.edu.cn/login?service=x");
        assertNotNull(ssoHeader);
        assertTrue(ssoHeader.contains("CASTGC=TGT-abc"));
        assertEquals("TGT-abc", jar.valueOf("sso.buaa.edu.cn", "CASTGC"));
        assertNull(jar.header("https://m.icw.buaa.edu.cn/card/goIndex"));
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "CASTGC"));
    }

    @Test
    public void multipleSetCookieInOneResponse() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://m.icw.buaa.edu.cn/card/goIndex?openid=1",
                Arrays.asList("JSESSIONID=ABC123; Path=/; HttpOnly", "other=1; Path=/"));

        String header = jar.header("https://m.icw.buaa.edu.cn/card/goIndex");
        assertNotNull(header);
        assertTrue(header.contains("JSESSIONID=ABC123"));
        assertTrue(header.contains("other=1"));
        assertEquals("ABC123", jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"));
    }

    @Test
    public void pathScopedCookieOnlyMatchesSubtree() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://m.icw.buaa.edu.cn/card/login",
                Collections.singletonList("token=t1; Path=/card"));

        assertNotNull(jar.header("https://m.icw.buaa.edu.cn/card/goIndex"));
        assertNull(jar.header("https://m.icw.buaa.edu.cn/htwx/index"));
    }

    @Test
    public void maxAgeZeroDeletesCookie() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://m.icw.buaa.edu.cn/card/a",
                Collections.singletonList("JSESSIONID=s1; Path=/"));
        assertNotNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"));

        jar.absorb("https://m.icw.buaa.edu.cn/card/b",
                Collections.singletonList("JSESSIONID=deleted; Path=/; Max-Age=0"));
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"));
    }

    @Test
    public void domainCookieMatchesParentDomain() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://sso.buaa.edu.cn/login",
                Collections.singletonList("buaa=1; Domain=.buaa.edu.cn; Path=/"));

        assertTrue(jar.header("https://sso.buaa.edu.cn/login").contains("buaa=1"));
        assertTrue(jar.header("https://m.icw.buaa.edu.cn/card").contains("buaa=1"));
        assertNull(jar.header("https://example.com/"));
    }

    @Test
    public void secureCookieNotSentOverPlainHttp() {
        CookieJar jar = new CookieJar();
        jar.absorb("https://m.icw.buaa.edu.cn/card",
                Collections.singletonList("secureOnly=1; Path=/; Secure"));

        assertNotNull(jar.header("https://m.icw.buaa.edu.cn/card"));
        assertNull(jar.header("http://m.icw.buaa.edu.cn/card"));
    }

    @Test
    public void putAndClear() {
        CookieJar jar = new CookieJar();
        jar.put("m.icw.buaa.edu.cn", "JSESSIONID", "restored-session");
        assertEquals("restored-session", jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"));
        assertFalse(jar.isEmpty());
        jar.clear();
        assertTrue(jar.isEmpty());
        assertNull(jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID"));
    }
}
