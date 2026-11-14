package cn.edu.buaa.scp.ui

import cn.edu.buaa.scp.net.Html

/** 官方充值页地址。用现有 JSESSIONID 在 WebView 内打开，支付仍由学校平台完成。 */
object RechargePage {

    const val ORIGIN = "https://m.icw.buaa.edu.cn"
    const val PAGE = "$ORIGIN/card/#/pages_other/recharge/cardrecharge/cardrecharge"

    fun url(openid: String): String =
        "$PAGE?openid=${Html.urlEncode(openid)}&usertype=6"
}
