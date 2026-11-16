package cn.edu.buaa.scp.ui

import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.edu.buaa.scp.net.ScanOutcome
import org.json.JSONObject

/** 在官方 H5 初始化完成前隐藏表单，避免密码提交读到空 openid。 */
open class CardFollowUpClient(
    private val openid: String,
    private val onReady: () -> Unit,
    private val onResult: (String) -> Unit,
) : WebViewClient() {
    private var generation = 0
    private var closed = false

    fun close() { closed = true; generation++ }

    private fun finish(view: WebView, message: String) {
        if (closed) return
        close()
        view.visibility = View.INVISIBLE
        view.stopLoading()
        onResult(message)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !ScanOutcome.isTrustedPage(request.url.toString())

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !ScanOutcome.isTrustedPage(url)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        generation++
        view.visibility = View.INVISIBLE
        if (!ScanOutcome.isTrustedPage(url.orEmpty())) finish(view, "页面地址不受支持")
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        ScanOutcome.terminalMessage(url.orEmpty())?.let { finish(view, it) }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (closed || !ScanOutcome.isTrustedPage(url.orEmpty())) return
        val run = ++generation
        initialize(view, run, 0)
    }

    private fun initialize(view: WebView, run: Int, attempt: Int) {
        if (closed || run != generation || !ScanOutcome.isTrustedPage(view.url.orEmpty())) return
        view.evaluateJavascript(bootstrap(openid)) { result ->
            if (closed || run != generation) return@evaluateJavascript
            if (result == "true") {
                view.visibility = View.VISIBLE
                onReady()
            } else if (attempt < 100) {
                view.postDelayed({ initialize(view, run, attempt + 1) }, 100)
            } else {
                finish(view, "页面身份初始化失败，请返回查看账单")
            }
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) finish(view, "页面加载失败，请查看账单确认结果")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) finish(view, "页面加载失败（${response.statusCode}），请查看账单确认结果")
    }

    companion object {
        fun bootstrap(openid: String): String {
            // JSON 字符串转义包含引号、反斜线、换行，额外兼容旧 JS 的行分隔符。
            val value = JSONObject.quote(openid).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            return """
                (function() {
                    try {
                        var id = $value;
                        if (!id || typeof getApp !== 'function' || typeof uni === 'undefined') return false;
                        var app = getApp();
                        if (!app || !app.globalData) return false;
                        // 官方工具模块的 globalData 独立于 getApp().globalData。
                        // 按导出特征定位已加载模块，不依赖构建生成的模块编号。
                        if (!window.__scpCardRuntime) {
                            if (!window.webpackJsonp || typeof window.webpackJsonp.push !== 'function') return false;
                            window.webpackJsonp.push([[], {scpCardIdentity: function(module, exports, runtime) {
                                window.__scpCardRuntime = runtime;
                            }}, [['scpCardIdentity']]]);
                        }
                        var runtime = window.__scpCardRuntime;
                        if (!runtime || !runtime.c) return false;
                        var found = false;
                        Object.keys(runtime.c).forEach(function(key) {
                            var api = runtime.c[key].exports;
                            if (api && api.baseUrl === location.origin + '/card' &&
                                api.globalData && typeof api.post === 'function') {
                                api.globalData.openid = id;
                                found = api.globalData.openid === id;
                            }
                        });
                        if (!found) return false;
                        app.globalData.openid = id;
                        uni.setStorageSync('openid', id);
                        return app.globalData.openid === id && uni.getStorageSync('openid') === id;
                    } catch (e) { return false; }
                })()
            """.trimIndent()
        }
    }
}
