package cn.edu.buaa.scp.net

import cn.edu.buaa.scp.model.ZfwHome

/**
 * zfw.buaa.edu.cn（深澜认证计费系统）首页 HTML 解析。
 *
 * 页面是 Yii2 服务端渲染的桌面版表格，没有 JSON 接口；这里只做保守的文本提取：
 * - 先判断是否落到登录页；
 * - 再从「用户信息」列表和「产品信息」表格里取值；
 * - 字段缺失时返回空串，不抛异常，由 UI 决定展示「—」还是错误。
 *
 * 解析规则集中在此，便于用脱敏 fixture 做单元测试。
 */
object ZfwHtml {

    private val USERNAME = Regex("""用户名</label>\s*([^<]+)</li>""")
    private val NAME = Regex("""姓名</label>\s*([^<]+)</li>""")
    private val STATUS = Regex("""状态</label>\s*<a[^>]*>([^<]+)</a>""")
    private val ONLINE_ROW = Regex("""<tr[^>]*class="w1"[^>]*data-key=""")
    private val PRODUCT_ROW = Regex("""<tr[^>]*class="w3"[^>]*>""")
    private val CELL = Regex("""<td[^>]*data-col-seq="(\d+)"[^>]*>([\s\S]*?)</td>""")

    /** 登录页特征：深澜登录表单固定使用 #login-form。 */
    fun isLoginPage(html: String): Boolean =
        html.contains("id=\"login-form\"") || html.contains("LoginForm[username]")

    /** 解析首页；落到登录页或页面结构完全对不上时返回 null。 */
    fun parseHome(html: String): ZfwHome? {
        if (isLoginPage(html)) return null

        val username = USERNAME.find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val name = NAME.find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val status = STATUS.find(html)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val onlineCount = ONLINE_ROW.findAll(html).count()

        val cells = productCells(html)
        if (username.isEmpty() && cells.isEmpty()) return null

        return ZfwHome(
            username = username,
            name = name,
            status = status,
            product = cells["1"].orEmpty(),
            strategy = cells["2"].orEmpty(),
            usedTraffic = cells["3"].orEmpty(),
            usedDuration = cells["4"].orEmpty(),
            freeRemain = cells["6"].orEmpty(),
            billedRemain = cells["7"].orEmpty(),
            settleDate = cells["12"].orEmpty(),
            onlineCount = onlineCount,
        )
    }

    /** 定位「产品信息」表格里的第一行，按 data-col-seq 取值。 */
    private fun productCells(html: String): Map<String, String> {
        val anchor = html.indexOf("产品名称")
        if (anchor < 0) return emptyMap()
        val row = PRODUCT_ROW.find(html, anchor) ?: return emptyMap()
        val start = row.range.last + 1
        val end = html.indexOf("</tr>", start).takeIf { it > start } ?: return emptyMap()
        val rowHtml = html.substring(start, end)
        return CELL.findAll(rowHtml).mapNotNull { match ->
            val seq = match.groupValues[1]
            val value = clean(match.groupValues[2])
            if (value.isEmpty()) null else seq to value
        }.toMap()
    }

    /** 去标签、解常见实体、压缩空白。 */
    fun clean(raw: String): String = raw
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
