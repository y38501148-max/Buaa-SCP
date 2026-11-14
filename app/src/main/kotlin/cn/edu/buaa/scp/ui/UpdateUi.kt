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
    private const val KEY_CHECKED_VERSION = "checked_version"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var checking = false

    @Volatile
    private var dialogShowing = false

    /** 启动时自动检查：每个应用版本首次启动 + 之后每天一次；检查失败不占用当次机会。 */
    fun autoCheck(activity: AppCompatActivity) {
        if (ScpApp.get(activity).demoMode) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val sameVersion = prefs.getString(KEY_CHECKED_VERSION, "") == BuildConfig.VERSION_NAME
        if (sameVersion && now - prefs.getLong(KEY_LAST_CHECK, 0L) < DAY_MS) return
        check(activity, manual = false)
    }

    /** 设置页展示的待更新版本；没有则返回 null。 */
    fun pendingVersion(context: Context): String? {
        val version = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_VERSION, null)
            ?: return null
        return version.takeIf { UpdateManager.isNewer(it, BuildConfig.VERSION_NAME) }
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
            if (result.isFailure) {
                if (manual) toast(activity, "检查更新失败，请检查网络")
                return@launch
            }
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                .putString(KEY_CHECKED_VERSION, BuildConfig.VERSION_NAME)
                .apply()
            val info = result.getOrNull()
            if (info == null) {
                prefs.edit().remove(KEY_PENDING_VERSION).apply()
                if (manual) toast(activity, "暂无可用版本")
                return@launch
            }
            if (!UpdateManager.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                prefs.edit().remove(KEY_PENDING_VERSION).apply()
                if (manual) toast(activity, "已是最新版本 v${BuildConfig.VERSION_NAME}")
                return@launch
            }
            prefs.edit().putString(KEY_PENDING_VERSION, info.version).apply()
            val skipped = prefs.getString(KEY_SKIPPED_VERSION, null)
            if (!manual && skipped == info.version) return@launch
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
        val notes = info.notes.replace("\r", "").take(900)
        val message = "当前 v${BuildConfig.VERSION_NAME}$size" + if (notes.isBlank()) "" else "\n\n$notes"
        MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(message)
            .setNegativeButton("稍后") { _, _ -> dialogShowing = false }
            .setNeutralButton("跳过此版本") { _, _ ->
                dialogShowing = false
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SKIPPED_VERSION, info.version)
                    .remove(KEY_PENDING_VERSION)
                    .apply()
            }
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
                    if (!cancelled.get()) showDownloadError(activity, info, it)
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

    private fun showDownloadError(activity: AppCompatActivity, info: UpdateInfo, error: Throwable) {
        if (activity.isFinishing) return
        val raw = error.message.orEmpty()
        val friendly = if (raw.contains("CertPath", ignoreCase = true) ||
            raw.contains("SSL", ignoreCase = true) ||
            raw.contains("Trust anchor", ignoreCase = true)
        ) {
            "系统证书校验失败，无法直接下载。可以打开 Release 页面手动下载安装。"
        } else {
            "下载失败：${raw.ifBlank { "网络错误" }}"
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("更新下载失败")
            .setMessage(friendly)
            .setNegativeButton("关闭", null)
            .setPositiveButton("浏览器打开") { _, _ -> openRelease(activity, info) }
            .show()
    }

    private fun openRelease(activity: AppCompatActivity, info: UpdateInfo) {
        val url = info.htmlUrl.ifBlank {
            "https://github.com/y38501148-max/Buaa-SCP/releases"
        }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast(activity, "无法打开浏览器")
        }
    }

    private fun toast(activity: AppCompatActivity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }
}
