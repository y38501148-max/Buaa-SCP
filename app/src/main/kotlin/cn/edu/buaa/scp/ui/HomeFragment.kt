package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json

/** 首页：余额、卡状态、账户信息、通知、快捷入口与官方充值入口。 */
class HomeFragment : ScpFragment() {

    override suspend fun load() {
        val openid = app.openid()
        val home = resultOf { app.withRecovery { app.api.openHomePageApp(openid) } }
        val pay = resultOf { app.withRecovery { app.api.openCardPay() } }
        val loss = resultOf { app.api.reportLossInfo() }

        if (pay.ok) app.markSynced()
        val model = HomeModel.build(
            home = home.value,
            homeError = home.error,
            pay = pay.value,
            payError = pay.error,
            loss = loss.value,
            studentId = app.studentId(),
            updatedAt = System.currentTimeMillis(),
        )
        if (!isAlive) return
        render(model)
    }

    private fun render(model: HomeModel) {
        clearBody()
        val context = requireContext()

        addBalanceCard(context, model)
        addQuickActions(context)

        val info = Ui.addSection(body, "账户信息")
        Ui.addKv(info, "姓名", Json.or(model.name, "—"))
        Ui.addKv(info, "学号 / 工号", Json.or(model.studentId, "—"))
        Ui.addKv(info, "卡号", Json.or(model.idserial, "—"))
        Ui.addKv(info, "卡类型", model.cardType)

        if (model.notices.isNotEmpty()) {
            val notices = Ui.addSection(body, "消息通知")
            model.notices.forEach { notice -> Ui.addKv(notices, "·", notice) }
        }

        if (model.channels.isNotEmpty()) {
            val channels = Ui.addSection(body, "支付渠道")
            model.channels.forEach { Ui.addKv(channels, it) }
        }

        if (model.bothFailed) {
            addErrorCard("余额加载失败：${Json.or(model.payError, model.homeError ?: "未知错误")}") {
                refresh()
            }
        }
    }

    private fun addBalanceCard(context: Context, model: HomeModel) {
        val card = Ui.column(context).apply {
            setBackgroundResource(R.drawable.bg_balance_card)
            val pad = Ui.dp(context, 20f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(context, 12f) }
        }
        body.addView(card)

        val headRow = Ui.row(context)
        headRow.addView(
            Ui.text(context, "校园卡余额", 13f, R.color.white).apply {
                setTextColor(0xCCFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        headRow.addView(rechargeButton(context))
        card.addView(headRow)

        val amount = Ui.text(
            context,
            if (model.balance.isEmpty()) "¥ --" else "¥ ${model.balance}",
            36f,
            R.color.white,
            bold = true,
        ).apply { typeface = Typeface.DEFAULT_BOLD }
        card.addView(amount, Ui.rowParams(context, 2f))

        val statusRow = Ui.row(context)
        statusRow.addView(Ui.statusDot(context, model.statusNormal))
        statusRow.addView(
            Ui.text(context, "状态：${Json.or(model.status, "未知")}", 13f, R.color.white)
                .apply { setTextColor(0xE6FFFFFF.toInt()) },
        )
        card.addView(statusRow, Ui.rowParams(context, 10f))

        val time = Json.time(model.updatedAt)
        if (time.isNotEmpty()) {
            card.addView(
                Ui.text(context, "更新于 $time", 11f, R.color.white)
                    .apply { setTextColor(0x99FFFFFF.toInt()) },
                Ui.rowParams(context, 6f),
            )
        }
    }

    /** 余额卡右上角的「去充值」：打开学校官方充值页。 */
    private fun rechargeButton(context: Context): android.widget.TextView =
        Ui.text(context, "去充值", 13f, R.color.buaa_blue, bold = true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(Ui.dp(context, 14f), Ui.dp(context, 6f), Ui.dp(context, 14f), Ui.dp(context, 6f))
            setBackgroundResource(R.drawable.bg_chip)
            setOnClickListener {
                startActivity(Intent(requireContext(), RechargeActivity::class.java))
            }
        }

    private fun addQuickActions(context: Context) {
        val card = Ui.addCard(body)
        card.addView(Ui.text(context, "快捷入口", 15f, R.color.text_primary, bold = true))
        val row = Ui.row(context)
        val entries = listOf(
            "账单" to R.id.nav_bills,
            "流水" to R.id.nav_trades,
            "卡务" to R.id.nav_card,
        )
        entries.forEachIndexed { index, (title, tabId) ->
            val button = Ui.lightButton(context, title)
            button.setOnClickListener {
                (activity as? MainActivity)?.switchTab(tabId)
            }
            row.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    topMargin = Ui.dp(context, 10f)
                    if (index > 0) leftMargin = Ui.dp(context, 8f)
                },
            )
        }
        card.addView(row)
    }
}
