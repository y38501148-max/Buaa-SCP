package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
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

        val account = Ui.addSection(body, "账号")
        Ui.addKv(account, "学号 / 工号", Json.or(session?.studentId, "—"))
        Ui.addKv(account, "openid", Json.or(Json.mask(session?.openid), "—"))

        addSessionSection(context, session)
        addActions(context)
        addAbout(context)
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

    private fun addActions(context: Context) {
        val actions = Ui.addSection(body, "工具")
        addAction(actions, "网络自检") { runSelfCheck() }
        addAction(actions, "续期会话") { refreshSession() }
        addAction(actions, "校园卡充值") { openRecharge() }
        addAction(actions, "退出登录") {
            (activity as? MainActivity)?.confirmLogout()
        }
    }

    private fun addAbout(context: Context) {
        val about = Ui.addSection(body, "关于")
        val version = Ui.addKv(about, "版本", BuildConfig.VERSION_NAME)
        version.setOnClickListener { onVersionTap() }
        Ui.addKv(about, "数据来源", "m.icw.buaa.edu.cn")
        about.addView(
            Ui.text(context, "非官方客户端，仅为本人账号提供查询入口。", 12f, R.color.text_hint),
            Ui.rowParams(context, 6f),
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

    private fun addAction(parent: android.widget.LinearLayout, title: String, action: () -> Unit) {
        val button = Ui.lightButton(parent.context, title)
        button.setOnClickListener { action() }
        parent.addView(button, Ui.rowParams(parent.context, 6f))
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
