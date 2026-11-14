package cn.edu.buaa.scp.ui

import android.content.Intent
import android.widget.LinearLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.CardModel
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json

/** 卡务页：卡片档案、卡状态、余额、支付渠道与官方充值入口。 */
class CardFragment : ScpFragment() {

    override suspend fun load() {
        val loss = resultOf { app.withRecovery { app.api.reportLossInfo() } }
        val pay = resultOf { app.withRecovery { app.api.openCardPay() } }
        val home = resultOf { app.api.openHomePageApp(app.openid()) }

        if (pay.ok) app.markSynced()
        val model = CardModel.build(
            loss = loss.value,
            lossError = loss.error,
            pay = pay.value,
            payError = pay.error,
            home = home.value,
            studentId = app.studentId(),
        )
        if (!isAlive) return
        render(model)
    }

    private fun render(model: CardModel) {
        clearBody()
        val context = requireContext()

        val info = Ui.addSection(body, "卡片信息")
        Ui.addKv(info, "姓名", Json.or(model.name, "—"))
        Ui.addKv(info, "卡号", Json.or(model.idserial, "—"))
        Ui.addKv(info, "学号 / 工号", Json.or(model.studentId, "—"))
        Ui.addKv(
            info,
            "卡状态",
            Json.or(model.status, "未获取"),
            if (model.statusNormal) R.color.green else R.color.orange,
        )
        Ui.addKv(info, "卡类型", model.cardType)
        Ui.addKv(
            info,
            "余额",
            if (model.balance.isEmpty()) "—" else Json.money(model.balance),
            R.color.buaa_blue,
        )

        if (model.channels.isNotEmpty()) {
            val channels = Ui.addSection(body, "支付渠道")
            model.channels.forEach { Ui.addKv(channels, it) }
        }

        val recharge = Ui.lightButton(context, "校园卡充值")
        recharge.setOnClickListener {
            startActivity(Intent(requireContext(), RechargeActivity::class.java))
        }
        body.addView(
            recharge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        if (model.bothFailed) {
            addErrorCard("卡务信息加载失败：${Json.or(model.payError, model.lossError ?: "未知错误")}") {
                refresh()
            }
        }
    }
}
