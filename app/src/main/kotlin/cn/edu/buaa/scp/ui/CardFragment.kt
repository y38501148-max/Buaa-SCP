package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.CardModel
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json

/** 卡务页：卡片档案、卡状态、余额与官方充值入口。 */
class CardFragment : ScpFragment() {

    override suspend fun load() {
        if (app.demoMode) {
            render(Demo.card())
            return
        }
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

        addCardVisual(context, model)

        val info = Ui.addSection(body, "卡片信息")
        Ui.addKv(info, "姓名", Json.or(model.name, "—"))
        Ui.addKv(info, "学号 / 工号", Json.or(model.studentId, "—"))
        Ui.addKv(info, "卡号", Json.mask(model.idserial))
        Ui.addKv(
            info,
            "卡状态",
            Json.or(model.status, "未获取"),
            if (model.statusNormal) R.color.green else R.color.orange,
        )
        Ui.addKv(info, "卡类型", model.cardType)

        val recharge = Ui.primaryButton(context, "校园卡充值")
        recharge.setIconResource(R.drawable.ic_recharge)
        recharge.iconTint = Ui.tint(Ui.color(context, R.color.brand_gold))
        recharge.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_START
        recharge.iconPadding = Ui.dp(context, 8f)
        recharge.setOnClickListener {
            startActivity(Intent(requireContext(), RechargeActivity::class.java))
        }
        body.addView(
            recharge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 2f) },
        )

        if (model.bothFailed) {
            addErrorCard("卡务信息加载失败：${Json.or(model.payError, model.lossError ?: "未知错误")}") {
                refresh()
            }
        }
    }

    private fun addCardVisual(context: Context, model: CardModel) {
        val hero = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_hero)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(context, 16f) }
        }
        hero.addView(
            View(context).apply { setBackgroundResource(R.drawable.bg_hero_glow) },
            FrameLayout.LayoutParams(Ui.dp(context, 180f), Ui.dp(context, 180f)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = -Ui.dp(context, 74f)
                marginEnd = -Ui.dp(context, 54f)
            },
        )

        val content = Ui.column(context).apply {
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 18f), Ui.dp(context, 20f), Ui.dp(context, 18f))
        }
        hero.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val top = Ui.row(context)
        top.addView(
            Ui.text(context, "通财航", 12f, R.color.brand_gold, bold = true, letterSpacing = 0.16f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            Ui.text(context, Json.or(model.cardType, "校园卡"), 10.5f, R.color.white).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(context, 10f), Ui.dp(context, 3f), Ui.dp(context, 10f), Ui.dp(context, 3f))
                setBackgroundResource(R.drawable.bg_pill_glass)
            },
        )
        content.addView(top)

        content.addView(
            Ui.text(context, "余额", 11f, R.color.white).apply {
                setTextColor(0x99FFFFFF.toInt())
            },
            Ui.rowParams(context, 20f),
        )
        content.addView(
            Ui.text(
                context,
                if (model.balance.isEmpty()) "¥ --" else "¥ ${model.balance}",
                34f,
                R.color.white,
                bold = true,
                letterSpacing = 0.01f,
            ),
            Ui.rowParams(context, 4f),
        )

        val bottom = Ui.row(context)
        bottom.addView(
            Ui.text(context, Json.or(model.name, "校园卡用户"), 14f, R.color.white, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val status = Ui.row(context)
        status.addView(Ui.statusDot(context, model.statusNormal))
        status.addView(
            Ui.text(context, Json.or(model.status, "状态未知"), 11f, R.color.white).apply {
                setTextColor(0xCCFFFFFF.toInt())
            },
        )
        bottom.addView(status)
        content.addView(bottom, Ui.rowParams(context, 20f))

        body.addView(hero)
    }
}
