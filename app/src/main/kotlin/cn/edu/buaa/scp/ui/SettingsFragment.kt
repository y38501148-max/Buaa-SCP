package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.BuildConfig
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.store.SessionData
import cn.edu.buaa.scp.util.Json
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** 设置页：账号、会话状态、工具、关于。运行日志入口隐藏（连点版本号 7 次）。 */
class SettingsFragment : ScpFragment() {

    private var versionTaps = 0
    private var lastVersionTapAt = 0L

    override suspend fun load() {
        if (isAlive) render()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && view != null) refresh()
    }

    private fun render() {
        clearBody()
        val context = requireContext()
        val session = app.session

        addProfile(context, session)
        addSessionSection(context, session)
        addZfwSection(context)
        addActions(context)
        addAbout(context)
    }

    private fun addProfile(context: Context, session: SessionData?) {
        val card = Ui.addCard(body)
        val row = Ui.row(context)
        row.addView(Ui.avatar(context, "通"))
        val info = Ui.column(context)
        info.addView(Ui.text(context, "校园卡账号", 16f, R.color.text_primary, bold = true))
        info.addView(
            Ui.text(
                context,
                if (session?.studentId.isNullOrBlank()) "未登录" else "学号 ${session?.studentId}",
                12f,
                R.color.text_hint,
            ),
            Ui.rowParams(context, 3f),
        )
        row.addView(
            info,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 12f)
            },
        )
        val active = session?.hasJsessionid == true
        row.addView(
            Ui.pill(
                context,
                if (active) "会话有效" else "需登录",
                if (active) R.drawable.bg_pill_soft else R.drawable.bg_pill_gold_soft,
                if (active) R.color.green else R.color.orange,
            ).apply { gravity = Gravity.CENTER },
        )
        card.addView(row)

        Ui.divider(card)
        Ui.addKv(card, "openid", Json.or(Json.mask(session?.openid), "—"))
    }

    private fun addSessionSection(context: Context, session: SessionData?) {
        val card = Ui.addSection(body, "会话")
        Ui.addKv(
            card,
            "业务会话",
            if (session?.hasJsessionid == true) "已保存" else "无",
            if (session?.hasJsessionid == true) R.color.green else R.color.orange,
        )
        Ui.addKv(
            card,
            "统一认证凭据",
            if (session?.hasCastgc == true) "已保存" else "无",
            if (session?.hasCastgc == true) R.color.green else R.color.orange,
        )
        Ui.addKv(card, "登录时间", Json.or(Json.time(session?.savedAt ?: 0L), "—"))
        Ui.addKv(card, "最近刷新", Json.or(Json.time(session?.lastSyncAt ?: 0L), "—"))
    }


    /** 校园网会话：与统一身份认证独立，首次使用需在 WebView 里单独登录。 */
    private fun addZfwSection(context: Context) {
        val zfw = app.zfwSession
        val card = Ui.addSection(body, "校园网")
        Ui.addKv(
            card,
            "登录状态",
            if (zfw != null) "已登录" else "未登录",
            if (zfw != null) R.color.green else R.color.orange,
        )
        if (zfw != null) {
            Ui.addKv(card, "账号", Json.or(zfw.username, "—"))
            Ui.addKv(card, "最近同步", Json.or(Json.time(zfw.lastSyncAt), "—"))
        } else {
            card.addView(
                Ui.text(
                    context,
                    "校园网密码与统一身份认证密码不同，首次使用需要单独登录。",
                    11.5f,
                    R.color.text_hint,
                ),
                Ui.rowParams(context, 4f),
            )
        }
        Ui.divider(card, 50f)
        Ui.actionRow(
            card,
            R.drawable.ic_wifi,
            if (zfw == null) "登录校园网" else "打开校园网",
        ) {
            val target = if (zfw == null) ZfwLoginActivity::class.java else ZfwActivity::class.java
            startActivity(Intent(requireContext(), target))
        }
        if (zfw != null) {
            Ui.divider(card, 50f)
            Ui.actionRow(card, R.drawable.ic_logout, "退出校园网", danger = true) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("退出校园网")
                    .setMessage("清除本机保存的校园网会话，下次查看网费需重新登录。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出") { _, _ ->
                        app.clearZfwSession()
                        app.requestHomeRefresh()
                        refresh()
                    }
                    .show()
            }
        }
    }

    private fun addActions(context: Context) {
        val actions = Ui.addSection(body, "工具")
        Ui.actionRow(actions, R.drawable.ic_shield, "网络自检") { runSelfCheck() }
        Ui.divider(actions, 50f)
        Ui.actionRow(actions, R.drawable.ic_refresh, "续期会话") { refreshSession() }
        Ui.divider(actions, 50f)
        Ui.actionRow(actions, R.drawable.ic_recharge, "校园卡充值") { openRecharge() }
        Ui.divider(actions, 50f)
        Ui.actionRow(actions, R.drawable.ic_logout, "退出登录", danger = true) {
            (activity as? MainActivity)?.confirmLogout()
        }
    }

    private fun addAbout(context: Context) {
        val about = Ui.addSection(body, "关于")
        val version = Ui.addKv(about, "版本", BuildConfig.VERSION_NAME)
        version.setOnClickListener { onVersionTap() }
        Ui.addKv(about, "数据来源", "m.icw / zfw / it.buaa.edu.cn")
        Ui.divider(about, 50f)
        Ui.actionRow(about, R.drawable.ic_refresh, "检查更新") {
            (activity as? AppCompatActivity)?.let { UpdateUi.check(it, manual = true) }
        }
        about.addView(
            Ui.text(context, "非官方客户端，仅用于本人账号。", 11.5f, R.color.text_hint),
            Ui.rowParams(context, 12f),
        )
    }

    /** 隐藏入口：3 秒内连点版本号 7 次打开运行日志。 */
    private fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVersionTapAt > 3000) versionTaps = 0
        lastVersionTapAt = now
        versionTaps++
        if (versionTaps >= 7) {
            versionTaps = 0
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }
    }

    private fun openRecharge() {
        startActivity(Intent(requireContext(), RechargeActivity::class.java))
    }

    private fun runSelfCheck() {
        Toast.makeText(requireContext(), "正在自检…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val items = resultOf { SelfCheck.run() }
            if (!isAlive) return@launch
            val list = items.value
            if (list == null) {
                Toast.makeText(requireContext(), "自检失败：${items.error}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val allOk = list.all { it.ok }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (allOk) "网络自检通过" else "网络自检未通过")
                .setMessage(list.joinToString("\n\n") { it.line() })
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun refreshSession() {
        Toast.makeText(requireContext(), "正在续期…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = app.recoverSession()
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                if (ok) "会话已更新" else "续期失败，请重新登录",
                Toast.LENGTH_SHORT,
            ).show()
            refresh()
        }
    }
}
