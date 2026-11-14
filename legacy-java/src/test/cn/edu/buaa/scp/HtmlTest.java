package cn.edu.buaa.scp;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import cn.edu.buaa.scp.net.Html;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HTML/URL 解析测试，含一份脱敏后的真实 CAS 登录页夹具（{@code src/test/resources/cas_login.html}）。
 */
public class HtmlTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = HtmlTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("缺少测试夹具 " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void hiddenInputValue_fromRealCasPage() throws IOException {
        String html = fixture("cas_login.html");
        String execution = Html.hiddenInputValue(html, "execution");
        assertNotNull("真实 CAS 页面必须能解析出 execution", execution);
        assertTrue("execution 长度异常: " + execution.length(), execution.length() > 1000);
        assertTrue(execution.startsWith("FIXTURE-"));
        assertEquals("submit", Html.hiddenInputValue(html, "_eventId"));
        assertEquals("username_password", Html.hiddenInputValue(html, "type"));
    }

    @Test
    public void hiddenInputValue_acceptsAnyAttributeOrder() {
        assertEquals("v1", Html.hiddenInputValue("<input value=\"v1\" name=\"execution\">", "execution"));
        assertEquals("v2", Html.hiddenInputValue("<input name='execution' value='v2'/>", "execution"));
        assertNull(Html.hiddenInputValue("<input name=\"other\" value=\"x\">", "execution"));
    }

    @Test
    public void errorMessage_detectsInvalidCredentials() {
        String message = Html.errorMessage(
                "<html><body><div class='alert'>Invalid credentials.</div></body></html>");
        assertNotNull(message);
        assertTrue(message.contains("账号或密码错误"));
    }

    @Test
    public void errorMessage_detectsCaptchaRequirement() {
        String message = Html.errorMessage("<html><body>"
                + "<div>请输入验证码</div>"
                + "<input name=\"validateCode\" value=\"\"/>"
                + "</body></html>");
        assertNotNull(message);
        assertTrue(message.contains("验证码"));
    }

    @Test
    public void stripTags_removesScriptsAndTags() {
        String text = Html.stripTags("<div>余额 <b>123.45</b></div><script>var a=1;</script>");
        assertEquals("余额 123.45", text);
    }

    @Test
    public void queryParam_readsEncodedValues() {
        String url = "https://m.icw.buaa.edu.cn/card/home/sisUstbMiniReturnPage/112233/6/auth"
                + "?ticket=ST-1&openid=1F76%2BD504&usertype=6";
        assertEquals("ST-1", Html.queryParam(url, "ticket"));
        assertEquals("1F76+D504", Html.queryParam(url, "openid"));
        assertEquals("6", Html.queryParam(url, "usertype"));
        assertNull(Html.queryParam(url, "missing"));
    }

    @Test
    public void failPageMessage_decodesDataJson() {
        String location = "https://m.icw.buaa.edu.cn/card/?isError=ERROR#/pages/common/fail/fail"
                + "?data=%7B%22message%22%3A%22%E7%94%A8%E6%88%B7%E4%BF%A1%E6%81%AF%E4%B8%8D"
                + "%E5%8C%B9%E9%85%8D%22%7D";
        assertEquals("用户信息不匹配", Html.failPageMessage(location));
    }

    @Test
    public void jsonMessage_returnsBusinessMessage() {
        assertEquals("资源受限，页面丢失!",
                Html.jsonMessage("{\"message\":\"资源受限，页面丢失!\",\"success\":false}"));
        assertNull(Html.jsonMessage("<html></html>"));
    }
}
