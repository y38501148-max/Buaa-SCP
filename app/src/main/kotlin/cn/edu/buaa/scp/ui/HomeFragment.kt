package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.model.HomeModel
import cn.edu.buaa.scp.model.NetworkModel
import cn.edu.buaa.scp.model.ZfwState
import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.net.ApiResult
import cn.edu.buaa.scp.util.Json
import com.google.android.material.card.MaterialCardView

/**
 * 首页：
 * 1. 支付二维码（默认显示，自动 / 手动刷新）
 * 2. 网费
 * 3. 快捷入口与账户信息
 */
class HomeFragment : ScpFragment() {
    private var paymentPanel: PaymentCodePanel? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        clearBody()
        paymentPanel = PaymentCodePanel(this, app) { refresh() }.also { body.addView(it.view) }
        return root
    }

    override suspend fun load() {
        if (app.demoMode) {
            render(
                Demo.home(),
                NetworkModel(
                    balance = "103.79",
                    balanceError = null,
                    zfw = ZfwState.LoggedIn(Demo.zfwHome()),
                ),
            )
            return
        }
        val openid = app.openid()
        val home = resultOf { app.withRecovery { app.api.openHomePageApp(openid) } }
        val pay = resultOf { app.withRecovery { app.api.openCardPay() } }
        val loss = resultOf { app.api.reportLossInfo() }
        val balance = resultOf { app.withRecovery { app.portalApi.netFeeBalance() } }
        val zfw = loadZfw()

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
        render(model, NetworkModel(balance.value, balance.error, zfw))
    }

    /** 拉取校园网首页：未登录 / 失效 / 已登录三态。 */
    private suspend fun loadZfw(): ZfwState {
        if (!app.hasZfwSession()) return ZfwState.NotLoggedIn()
        return try {
            val home = app.zfwApi.fetchHome()
            app.refreshZfwCookie(app.zfwApi.cookieHeader())
            app.updateZfwProfile(home.username, home.name)
            app.markZfwSynced()
            ZfwState.LoggedIn(home)
        } catch (e: ApiException) {
            if (e.authExpired) {
                app.clearZfwSession()
                ZfwState.NotLoggedIn(expired = true)
            } else {
                ZfwState.Failed(e.message ?: "网费数据加载失败")
            }
        } catch (e: Exception) {
            ZfwState.Failed(e.message ?: "网费数据加载失败")
        }
    }

    private fun render(model: HomeModel, network: NetworkModel) {
        clearBody()
        val context = requireContext()

        paymentPanel?.let {
            it.updateBalance(model)
            body.addView(it.view)
        }
        addNetworkCard(context, network)
        addQuickActions(context)
        addAccountSection(context, model)

        if (model.notices.isNotEmpty()) addNotices(context, model)

        if (model.bothFailed) {
            addErrorCard("余额加载失败：${Json.or(model.payError, model.homeError ?: "未知错误")}") {
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) paymentPanel?.start()
        if (isAdded && view != null && app.consumeHomeRefresh()) {
            refresh()
        }
    }

    override fun onPause() {
        paymentPanel?.stop()
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) paymentPanel?.stop() else if (isResumed) paymentPanel?.start()
    }

    override fun onDestroyView() {
        paymentPanel?.stop()
        paymentPanel = null
        super.onDestroyView()
    }

    // ---------------- 网费显示 ----------------

    private fun addNetworkCard(context: Context, network: NetworkModel) {
        val state = network.zfw
        val card = MaterialCardView(context).apply {
            radius = Ui.dp(context, 20f).toFloat()
            cardElevation = Ui.dp(context, 1f).toFloat()
            strokeWidth = Ui.dp(context, 1f)
            strokeColor = Ui.color(context, R.color.brand_blue_soft)
            setCardBackgroundColor(Ui.color(context, R.color.surface))
            rippleColor = Ui.tint(Ui.color(context, R.color.brand_blue_soft))
            isClickable = true
            isFocusable = true
            setOnClickListener { openZfw(recharge = false) }
        }
        val content = Ui.column(context).apply {
            setPadding(Ui.dp(context, 18f), Ui.dp(context, 16f), Ui.dp(context, 18f), Ui.dp(context, 14f))
        }
        card.addView(content)

        val header = Ui.row(context)
        header.addView(Ui.iconTile(context, R.drawable.ic_wifi, R.color.brand_blue, R.color.brand_blue_soft, 38))
        header.addView(
            Ui.text(context, "校园网", 15f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 10f)
            },
        )
        header.addView(
            Ui.pill(
                context,
                when (state) {
                    is ZfwState.LoggedIn -> "已登录"
                    is ZfwState.Failed -> "加载失败"
                    else -> "未登录"
                },
                if (state is ZfwState.LoggedIn) R.drawable.bg_pill_soft else R.drawable.bg_pill_gold_soft,
                if (state is ZfwState.LoggedIn) R.color.green else R.color.orange,
            ).apply { gravity = Gravity.CENTER },
        )
        content.addView(header)
        Ui.divider(content)

        val row = Ui.row(context)
        val loggedIn = state as? ZfwState.LoggedIn
        addNetworkMetric(
            context, row, "网费余额",
            network.balance?.let { "¥$it" } ?: "—",
            R.color.brand_gold_deep,
        )
        addNetworkMetric(
            context, row, "剩余免费流量",
            loggedIn?.home?.freeRemainText ?: "—",
            R.color.brand_blue,
        )
        addNetworkMetric(
            context, row, "剩余计费流量",
            loggedIn?.home?.billedRemainText ?: "—",
            R.color.brand_blue,
        )
        content.addView(row, Ui.rowParams(context, 2f))

        val footer = Ui.row(context)
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val action = Ui.goldButton(
            context,
            if (loggedIn != null) "网费充值" else "登录校园网",
        ).apply {
            minWidth = Ui.dp(context, 96f)
            minimumHeight = Ui.dp(context, 38f)
            insetTop = 0
            insetBottom = 0
            textSize = 12.5f
            setPadding(Ui.dp(context, 16f), 0, Ui.dp(context, 16f), 0)
        }
        action.setOnClickListener { openZfw(recharge = loggedIn != null) }
        footer.addView(action)
        content.addView(footer, Ui.rowParams(context, 14f))

        body.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = Ui.dp(context, 16f) },
        )
    }

    private fun addNetworkMetric(
        context: Context,
        parent: LinearLayout,
        label: String,
        value: String,
        colorRes: Int,
    ) {
        val column = Ui.column(context)
        column.addView(Ui.text(context, label, 10.5f, R.color.text_hint))
        column.addView(
            Ui.text(context, value, 18f, colorRes, bold = true),
            Ui.rowParams(context, 4f),
        )
        parent.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun openZfw(recharge: Boolean) {
        val intent = if (app.hasZfwSession()) {
            Intent(requireContext(), ZfwActivity::class.java)
                .putExtra(ZfwActivity.EXTRA_RECHARGE, recharge)
        } else {
            Intent(requireContext(), ZfwLoginActivity::class.java)
                .putExtra(ZfwLoginActivity.EXTRA_RECHARGE, recharge)
        }
        startActivity(intent)
    }

    // ---------------- 快捷入口 ----------------

    private fun addQuickActions(context: Context) {
        Ui.sectionHeader(body, "快捷入口")
        val row = Ui.row(context)
        val entries = listOf(
            QuickEntry("账单", R.drawable.ic_bill, R.color.brand_blue, R.color.brand_blue_soft) {
                (activity as? MainActivity)?.switchTab(R.id.nav_bills)
            },
            QuickEntry("财务", R.drawable.ic_finance, R.color.brand_teal, R.color.brand_teal_soft) {
                (activity as? MainActivity)?.switchTab(R.id.nav_finance)
            },
            QuickEntry("充值", R.drawable.ic_recharge, R.color.brand_gold_deep, R.color.brand_gold_soft) {
                startActivity(Intent(requireContext(), RechargeActivity::class.java))
            },
            QuickEntry("其他", R.drawable.ic_more, R.color.brand_navy, R.color.brand_navy_soft) {
                startActivity(Intent(requireContext(), OtherActivity::class.java))
            },
        )
        entries.forEachIndexed { index, entry ->
            val tile = quickTile(context, entry)
            row.addView(
                tile,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = Ui.dp(context, 8f)
                },
            )
        }
        body.addView(row, Ui.lp(context = context, bottom = 16f))
    }

    private fun quickTile(context: Context, entry: QuickEntry): MaterialCardView {
        val card = MaterialCardView(context).apply {
            radius = Ui.dp(context, 18f).toFloat()
            cardElevation = Ui.dp(context, 1f).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Ui.color(context, R.color.surface))
            rippleColor = Ui.tint(Ui.color(context, R.color.brand_blue_soft))
            isClickable = true
            isFocusable = true
            setOnClickListener { entry.onClick() }
        }
        val column = Ui.column(context).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 8f), Ui.dp(context, 14f), Ui.dp(context, 8f), Ui.dp(context, 12f))
        }
        val icon = Ui.iconTile(context, entry.icon, entry.tint, entry.bg, 38)
        column.addView(icon)
        column.addView(
            Ui.text(context, entry.label, 12f, R.color.text_primary, bold = true).apply {
                gravity = Gravity.CENTER
            },
            Ui.rowParams(context, 8f),
        )
        card.addView(column)
        return card
    }

    // ---------------- 账户信息 ----------------

    private fun addAccountSection(context: Context, model: HomeModel) {
        val info = Ui.addSection(body, "账户信息")
        val profile = Ui.row(context)
        profile.addView(Ui.avatar(context, model.name))
        val names = Ui.column(context)
        names.addView(
            Ui.text(context, Json.or(model.name, "校园卡用户"), 16f, R.color.text_primary, bold = true),
        )
        names.addView(
            Ui.text(
                context,
                if (model.studentId.isEmpty()) "学号 —" else "学号 ${model.studentId}",
                12f,
                R.color.text_hint,
            ),
            Ui.rowParams(context, 3f),
        )
        profile.addView(
            names,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 12f)
            },
        )
        info.addView(profile)

        Ui.divider(info)
        Ui.addKv(info, "卡类型", model.cardType)
        Ui.addKv(
            info,
            "卡状态",
            Json.or(model.status, "—"),
            if (model.statusNormal) R.color.green else R.color.orange,
        )
    }

    private fun addNotices(context: Context, model: HomeModel) {
        val notices = Ui.addSection(body, "消息通知")
        model.notices.forEachIndexed { index, notice ->
            val line = Ui.row(context)
            line.addView(
                View(context).apply {
                    setBackgroundResource(R.drawable.bg_dot_warn)
                },
                LinearLayout.LayoutParams(Ui.dp(context, 6f), Ui.dp(context, 6f)).apply {
                    marginEnd = Ui.dp(context, 10f)
                },
            )
            line.addView(
                Ui.text(context, notice, 13f, R.color.text_primary),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            notices.addView(line, Ui.rowParams(context, if (index == 0) 8f else 10f))
        }
    }

    private data class QuickEntry(
        val label: String,
        val icon: Int,
        val tint: Int,
        val bg: Int,
        val onClick: () -> Unit,
    )
}
