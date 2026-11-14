package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.BuildConfig
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.net.DownloadResult
import cn.edu.buaa.scp.net.UpdateInfo
import cn.edu.buaa.scp.net.UpdateManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 应用内更新界面：检查、下载、校验后交给系统安装器。 */
object UpdateUi {

    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var checking = false

    @Volatile
    private var dialogShowing = false

    fun autoCheck(activity: AppCompatActivity) {
        if (ScpApp.get(activity).demoMode) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < DAY_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check(activity, manual = false)
    }

    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (checking) {
            if (manual) toast(activity, "正在检查更新…")
            return
        }
        checking = true
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UpdateManager.fetchLatest() }
            }
            checking = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val info = result.getOrNull()
            if (info == null) {
                if (manual) toast(
                    activity,
                    if (result.isSuccess) "暂无可用版本" else "检查更新失败，请检查网络",
                )
                return@launch
            }
            if (!UpdateManager.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                if (manual) toast(activity, "已是最新版本 v${BuildConfig.VERSION_NAME}")
                return@launch
            }
            showUpdateDialog(activity, info)
        }
    }

    private fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        if (dialogShowing || activity.isFinishing) return
        dialogShowing = true
        val size = if (info.apkSize > 0) {
            String.format(Locale.US, " · %.1f MB", info.apkSize / 1024.0 / 1024.0)
        } else {
            ""
        }
        val notes = info.notes
            .replace("\r", "")
            .take(900)
            .ifBlank { "包含界面与稳定性改进。" }
        MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage("当前 v${BuildConfig.VERSION_NAME}$size\n\n$notes")
            .setNegativeButton("稍后") { _, _ -> dialogShowing = false }
            .setPositiveButton("下载并安装") { _, _ ->
                dialogShowing = false
                downloadAndInstall(activity, info)
            }
            .setOnCancelListener { dialogShowing = false }
            .show()
    }

    private fun downloadAndInstall(activity: AppCompatActivity, info: UpdateInfo) {
        val progress = LinearProgressIndicator(activity).apply {
            isIndeterminate = false
            max = 100
            setProgressCompat(0, false)
        }
        val label = Ui.text(activity, "正在下载…", 13f, R.color.text_secondary).apply {
            gravity = Gravity.CENTER
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(activity, 22f), Ui.dp(activity, 12f), Ui.dp(activity, 22f), 0)
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(activity, 14f) })
        }
        val cancelled = AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载更新")
            .setView(box)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .setCancelable(false)
            .create()
        dialog.show()

        val target = File(activity.cacheDir, "updates/TongCaiHang-v${info.version}.apk")
        activity.lifecycleScope.launch {
            val download = withContext(Dispatchers.IO) {
                runCatching {
                    val result = UpdateManager.download(info, target, { cancelled.get() }) { percent, done, total ->
                        activity.runOnUiThread {
                            if (!activity.isFinishing) {
                                if (percent >= 0) {
                                    progress.setProgressCompat(percent, true)
                                    label.text = "正在下载 $percent%"
                                } else {
                                    label.text = String.format(
                                        Locale.US,
                                        "正在下载 %.1f MB",
                                        done / 1024.0 / 1024.0,
                                    )
                                }
                            }
                        }
                    }
                    if (!UpdateManager.verifyChecksum(info, result.file)) {
                        result.file.delete()
                        error("更新包校验失败")
                    }
                    if (!UpdateManager.verifyPackage(activity, result.file)) {
                        result.file.delete()
                        error("更新包签名不匹配")
                    }
                    result
                }
            }
            dialog.dismiss()
            if (activity.isFinishing || activity.isDestroyed) {
                download.getOrNull()?.file?.delete()
                return@launch
            }
            download.fold(
                onSuccess = { install(activity, it) },
                onFailure = {
                    if (!cancelled.get()) toast(activity, "下载失败：${it.message ?: "网络错误"}")
                },
            )
        }
    }

    private fun install(activity: AppCompatActivity, result: DownloadResult) {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("需要安装权限")
                .setMessage("允许通财航安装更新后，再点一次「检查更新」即可安装已下载的版本。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}"),
                            ),
                        )
                    }
                }
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.updates",
            result.file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, "无法打开安装器：${it.message ?: "未知错误"}") }
    }

    private fun toast(activity: AppCompatActivity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
