package cn.edu.buaa.scp.net

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import cn.edu.buaa.scp.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** GitHub Release 上的一个可安装版本。 */
data class UpdateInfo(
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String?,
    val htmlUrl: String,
)

data class DownloadResult(val file: File, val sha256: String)

/**
 * 应用内更新：读取 GitHub Release、下载 APK、校验 SHA-256 与签名。
 *
 * 只负责网络与校验；弹窗、下载进度和系统安装由 [cn.edu.buaa.scp.ui.UpdateUi] 处理。
 */
object UpdateManager {

    private const val API =
        "https://api.github.com/repos/y38501148-max/Buaa-SCP/releases/latest"

    /** 返回 null 表示仓库还没有正式 Release。网络错误会抛出异常。 */
    fun fetchLatest(): UpdateInfo? {
        val conn = open(API)
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (conn.responseCode !in 200..299) {
                throw IOException("GitHub HTTP ${conn.responseCode}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(JSONObject(text))
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(json: JSONObject): UpdateInfo? {
        if (json.optBoolean("draft", false)) return null
        val tag = json.optString("tag_name").trim()
        val name = json.optString("name").trim()
        val version = tag.removePrefix("v").removePrefix("V").ifBlank {
            name.removePrefix("v").removePrefix("V")
        }.substringBefore("-").substringBefore("+")
        if (version.isBlank()) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var apkSize = 0L
        var checksumUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val assetName = asset.optString("name")
            val url = assetDownloadUrl(asset)
            when {
                assetName.endsWith(".apk", ignoreCase = true) -> {
                    apkUrl = url
                    apkSize = asset.optLong("size", 0L)
                }

                assetName.endsWith(".apk.sha256", ignoreCase = true) ||
                        assetName.equals("sha256sum.txt", ignoreCase = true) -> {
                    checksumUrl = url
                }
            }
        }
        if (apkUrl.isEmpty()) return null
        return UpdateInfo(
            version = version,
            tag = tag.ifBlank { "v$version" },
            notes = json.optString("body").trim(),
            apkUrl = apkUrl,
            apkSize = apkSize,
            checksumUrl = checksumUrl,
            htmlUrl = json.optString("html_url"),
        )
    }

    internal fun assetDownloadUrl(asset: JSONObject): String {
        val apiUrl = asset.optString("url")
        // 官方资源 API 可直接转到下载 CDN，避免依赖 github.com 的网页下载入口。
        return apiUrl.takeIf {
            Regex("^https://api\\.github\\.com/repos/y38501148-max/Buaa-SCP/releases/assets/[0-9]+$").matches(it)
        } ?: asset.optString("browser_download_url")
    }

    /** 语义化版本比较：返回 latest 是否比 current 新。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val clean = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        val result = parts.map { it.toIntOrNull() ?: return null }
        return result.ifEmpty { null }
    }

    fun download(
        info: UpdateInfo,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (percent: Int, downloaded: Long, total: Long) -> Unit,
    ): DownloadResult {
        val conn = open(info.apkUrl, "application/octet-stream")
        var tmp: File? = null
        var done = false
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("下载失败 HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            target.parentFile?.mkdirs()
            tmp = File(target.parentFile, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("已取消")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0) {
                            (downloaded * 100 / total).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent, downloaded, total)
                        }
                    }
                }
            }
            if (total > 0 && downloaded != total) throw IOException("下载不完整")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            done = true
            return DownloadResult(target, digest.digest().toHex())
        } finally {
            conn.disconnect()
            if (!done) tmp?.delete()
        }
    }

    fun verifyChecksum(info: UpdateInfo, file: File): Boolean {
        val url = info.checksumUrl ?: return true
        val conn = open(url, "application/octet-stream")
        val text = try {
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val expected = Regex("([a-fA-F0-9]{64})").find(text)?.groupValues?.get(1) ?: return false
        return expected.equals(file.sha256(), ignoreCase = true)
    }

    /** 校验包名与签名，避免把被替换过的 APK 装进来。 */
    fun verifyPackage(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val a = signerDigests(archive)
        val b = signerDigests(installed)
        return a.isNotEmpty() && a == b
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        return signatures.map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }.toSet()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        String.format(Locale.US, "%02x", it)
    }

    private fun open(url: String, accept: String = "application/vnd.github+json"): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "TongCaiHang/${BuildConfig.VERSION_NAME} (Android)")
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Accept-Encoding", "identity")
        return conn
    }
}
