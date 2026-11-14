package cn.edu.buaa.scp;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.edu.buaa.scp.net.CardApi;
import cn.edu.buaa.scp.net.CasAuth;
import cn.edu.buaa.scp.net.CookieJar;
import cn.edu.buaa.scp.net.Html;
import cn.edu.buaa.scp.net.HttpEngine;
import cn.edu.buaa.scp.net.SelfCheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真实链路探针：默认跳过，需要显式开启：
 *
 * <pre>./gradlew :app:testDebugUnitTest -Plive=1 --tests '*LiveChainTest*'</pre>
 *
 * <p>验证的是「设备侧网络栈 + 协议实现」到学校服务的连通性，不需要账号密码，
 * 因此可以放进 CI/交付前检查。
 */
public class LiveChainTest {

    @Before
    public void requireLiveFlag() throws Exception {
        org.junit.Assume.assumeTrue("需要 SCP_LIVE_TEST=1（或 -Plive=1）才运行真实网络测试",
                "1".equals(System.getenv("SCP_LIVE_TEST")) || Boolean.getBoolean("live"));
        relaxJvmTrustForProbe();
    }

    /**
     * 桌面 JDK 的 cacerts 与 Android 系统信任库并不相同：m.icw.buaa.edu.cn 使用
     * CFCA 证书链，桌面 JDK 默认不信任，而 Android 端（模拟器/真机实测）可以正常校验通过。
     *
     * <p>因此本探针只验证「协议与连通性」，在 JVM 侧临时放宽证书校验；
     * 证书链验证以 Android 端「网络自检」结果为准。
     */
    private static void relaxJvmTrustForProbe() throws Exception {
        javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                   String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                   String authType) {
                    }

                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
        };
        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(null, trustAll, new java.security.SecureRandom());
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(
                context.getSocketFactory());
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
    }

    @Test
    public void selfCheckAllItemsPass() {
        List<SelfCheck.Item> items = SelfCheck.run();
        StringBuilder report = new StringBuilder();
        for (SelfCheck.Item item : items) {
            report.append(item.line()).append('\n');
        }
        System.out.println(report);
        for (SelfCheck.Item item : items) {
            assertTrue("自检失败：" + item.name + " → " + item.detail, item.ok);
        }
    }

    @Test
    public void casLoginPageExposesExecution() throws Exception {
        HttpEngine http = new HttpEngine(new CookieJar());
        HttpEngine.Response response = http.get("https://" + CasAuth.SSO_HOST + "/login?service="
                + Html.urlEncode(CasAuth.SERVICE) + "&noAutoRedirect=1");
        assertEquals(200, response.status);
        String execution = Html.hiddenInputValue(response.body, "execution");
        assertNotNull("CAS 登录页未解析出 execution（页面可能改版）", execution);
        assertTrue("execution 长度异常：" + execution.length(), execution.length() > 500);
    }

    @Test
    public void entryUrlRedirectsIntoCardApp() throws Exception {
        HttpEngine http = new HttpEngine(new CookieJar());
        HttpEngine.Response response = http.get(SelfCheck.ENTRY);
        assertEquals(302, response.status);
        assertNotNull(response.location());
        assertTrue("跳转目标异常：" + response.location(), response.location().contains("/card"));
    }

    @Test
    public void businessApiRejectsAnonymousRequest() throws Exception {
        HttpEngine http = new HttpEngine(new CookieJar());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-requested-with", "XMLHttpRequest");
        headers.put("session-type", "uniapp");
        headers.put("isWechatApp", "true");
        headers.put("orgid", "2");
        headers.put("Content-Type", "application/json;charset=UTF-8");
        HttpEngine.Response response = http.post(
                CardApi.BASE + "/cardpay/openCardPay", headers, "{}");
        assertEquals(403, response.status);
    }
}
