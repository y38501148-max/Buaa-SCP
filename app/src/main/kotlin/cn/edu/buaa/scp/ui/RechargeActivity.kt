package cn.edu.buaa.scp.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityRechargeBinding
import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.RechargeFailure
import cn.edu.buaa.scp.net.RechargeFailureKind
import cn.edu.buaa.scp.net.RechargeFailureParser
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 官方充值页（WebView）。
 *
 * 不自建支付：把当前业务的 JSESSIONID 注入 WebView，再打开学校掌上服务大厅的充值页，
 * 选金额、选支付方式、跳第三方支付都由官方页面完成，客户端不接触支付密码与银行信息。
 */
class RechargeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRechargeBinding
    private lateinit var app: ScpApp
    private var loaded = false
    private var targetUrl = ""
    private var successHandled = false
    private var successAttempts = 0
    private var paymentStarted = false
    private var leftForPayment = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRechargeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)

        applyEdgeToEdgeInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reload) {
                openPage()
                true
            } else {
                false
            }
        }
        binding.btnStateAction.setOnClickListener { openPage() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        configureWebView()
        prepareSession()
    }

    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            Ui.applyToolbarTopInset(binding.toolbar, bars.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
        }
        binding.webView.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://") ||
                    url.startsWith("about:") || url.startsWith("javascript:")
                ) {
                    return false
                }
                return openExternal(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.visibility = View.GONE
                if (successHandled) return
                val failure = RechargeFailureParser.fromPageUrl(url) ?: return
                // 缴费时段外重试无意义，只说明原因；会话失效/未知失败保留「重试」按钮。
                if (failure.kind == RechargeFailureKind.NOT_IN_WINDOW) {
                    LogBus.step("充值页提示：${failure.message}")
                } else {
                    LogBus.warn("充值页失败（${failure.kind}）：${failure.message}")
                }
                showState(
                    failure.message,
                    action = failure.kind != RechargeFailureKind.NOT_IN_WINDOW,
                )
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (isPaymentHost(url)) paymentStarted = true
                if (paymentStarted && !successHandled) detectSuccess(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                LogBus.warn("充值页加载失败：${error?.description ?: "网络错误"}")
                showState("充值页加载失败，请检查网络后重试", action = true)
            }
        }
    }

    private fun prepareSession() {
        showState("正在准备会话…")
        lifecycleScope.launch {
            val data = app.session
            if (data == null || !data.hasAnything) {
                showState("登录已过期，请重新登录", action = false)
                return@launch
            }
            var openid = data.openid
            if (openid.isEmpty() || !data.hasJsessionid) {
                val ok = try {
                    app.recoverSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LogBus.warn("进入充值页前恢复会话失败：${SelfCheck.describe(e)}")
                    false
                }
                openid = app.openid()
                if (!ok || openid.isEmpty()) {
                    showState("登录已过期，请返回重新登录", action = false)
                    return@launch
                }
            }
            openWithPrecheck(openid)
        }
    }

    /**
     * 打开 WebView 前先用 openCardPay 预检：它既校验会话，也会在缴费时段外返回业务失败。
     *
     * - 成功 / 其它业务错误 / 非 ApiException（网络异常等）：回退打开官方页面（网页可能仍可用）；
     * - 不在缴费时段：直接展示准确原因，不加载 WebView，会话保持正常；
     * - 会话失效：免密续期后只重试一次，仍失效才提示重新登录。
     */
    private suspend fun openWithPrecheck(openid: String) {
        if (app.demoMode) {
            // debug 演示模式不连网，按原行为直接展示页面。
            openTarget(openid)
            return
        }
        val failure = precheckOpenCardPay()
        if (failure == null) {
            openTarget(app.openid().ifEmpty { openid })
            return
        }
        when (failure.kind) {
            RechargeFailureKind.NOT_IN_WINDOW -> explainFailure(failure, openid)

            RechargeFailureKind.SESSION_EXPIRED -> recoverAndRetry(failure, openid)

            RechargeFailureKind.OTHER -> {
                LogBus.warn("充值预检返回业务错误（${failure.message}），回退打开官方充值页")
                openTarget(openid)
            }
        }
    }

    /** 预检判定会话失效时，免密续期一次并重新预检；不会无限重试。 */
    private suspend fun recoverAndRetry(failure: RechargeFailure, openid: String) {
        LogBus.warn("充值预检判定会话失效（${failure.message}），尝试免密续期")
        val recovered = try {
            app.recoverSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBus.warn("充值前恢复会话失败：${SelfCheck.describe(e)}")
            false
        }
        val refreshed = app.openid().ifEmpty { openid }
        if (!recovered) {
            explainFailure(failure, refreshed)
            return
        }
        val retry = precheckOpenCardPay()
        if (retry == null) {
            openTarget(refreshed)
            return
        }
        when (retry.kind) {
            RechargeFailureKind.NOT_IN_WINDOW -> explainFailure(retry, refreshed)
            RechargeFailureKind.SESSION_EXPIRED -> explainFailure(retry, refreshed)
            RechargeFailureKind.OTHER -> {
                LogBus.warn("续期后充值预检仍返回业务错误（${retry.message}），回退打开官方充值页")
                openTarget(refreshed)
            }
        }
    }

    /** 展示失败原因但不加载网页；Cookie/目标地址仍准备好，工具栏「刷新」可强制打开官方页。 */
    private fun explainFailure(failure: RechargeFailure, openid: String) {
        if (failure.kind == RechargeFailureKind.NOT_IN_WINDOW) {
            LogBus.step("充值预检：${failure.message}，不加载充值页")
        } else {
            LogBus.warn("充值预检：${failure.message}")
        }
        if (prepareTarget(openid)) {
            showState(failure.message, action = false)
        }
    }

    /**
     * 预检：openCardPay 成功返回 null；ApiException 交给 [RechargeFailureParser] 分类；
     * 其它异常（网络等）记录日志后返回 null，由调用方回退打开官方页面。
     */
    private suspend fun precheckOpenCardPay(): RechargeFailure? = try {
        app.api.openCardPay()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        RechargeFailureParser.fromOpenCardPay(e)
    } catch (e: Exception) {
        LogBus.warn("充值预检异常（${SelfCheck.describe(e)}），回退打开官方充值页")
        null
    }

    private fun openTarget(openid: String) {
        if (!prepareTarget(openid)) return
        if (!isFinishing) openPage()
    }

    /** 注入 WebView Cookie 并确定目标地址；返回 false 表示缺少校园卡会话。 */
    private fun prepareTarget(openid: String): Boolean {
        val jsessionid = app.session?.jsessionid.orEmpty()
        if (jsessionid.isEmpty()) {
            showState("缺少校园卡会话，请返回重新登录", action = false)
            return false
        }
        targetUrl = RechargePage.url(openid)
        val manager = CookieManager.getInstance()
        val cookie = "JSESSIONID=$jsessionid; path=/"
        manager.setCookie(RechargePage.ORIGIN, cookie)
        manager.setCookie(RechargePage.ORIGIN + "/card/", cookie)
        // 航财通/校园付等后续页面会再走一次统一身份认证；预置 CASTGC 让这一步静默完成。
        val castgc = app.session?.castgc.orEmpty()
        if (castgc.isNotEmpty() && castgc.none { it == ';' || it == '\n' || it == '\r' }) {
            manager.setCookie(
                "https://${CasAuth.SSO_HOST}",
                "CASTGC=$castgc; path=/; Secure; HttpOnly",
            )
        }
        manager.flush()
        // 已向 WebView 注入会话 Cookie：即使最终没加载页面，销毁时也必须清理。
        loaded = true
        return true
    }

    private fun openPage() {
        if (targetUrl.isEmpty() || isFinishing) return
        binding.stateBox.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        loaded = true
        binding.webView.loadUrl(targetUrl)
    }

    private fun showState(message: String, action: Boolean = false) {
        if (isFinishing) return
        binding.stateBox.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.tvState.text = message
        binding.btnStateAction.visibility = if (action) View.VISIBLE else View.GONE
    }

    private fun openExternal(url: String): Boolean {
        if (isPaymentUrl(url)) leftForPayment = true
        val intent = if (url.startsWith("intent://")) {
            runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull()
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        if (intent == null) {
            Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show()
            return true
        }
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未安装可处理该链接的应用", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /**
     * 官方收银台/航财通支付完成后的落地页，命中后直接回首页。
     *
     * 只有进入过支付流程（跳转支付 App 或进入收银台域名）才开始判断，
     * 避免刚打开充值页时把页面里的模板文案误判成支付成功。
     */
    private fun detectSuccess(url: String?) {
        if (!paymentStarted || successHandled || url.isNullOrEmpty()) return
        if (SUCCESS_URL.containsMatchIn(url)) onSuccess()
    }

    /**
     * 官方页面是 uni-app / SPA，支付完成可能只做前端路由切换，不一定触发 onPageFinished。
     * 这里在页面加载后做短时轮询，读取页面可见文本判断是否出现「支付成功」。
     */
    private fun scheduleSuccessCheck() {
        if (!paymentStarted || successHandled || isFinishing) return
        binding.webView.postDelayed({
            if (successHandled || isFinishing) return@postDelayed
            binding.webView.evaluateJavascript(SUCCESS_SCRIPT) { value ->
                if (successHandled || isFinishing) return@evaluateJavascript
                if (value == "\"1\"") {
                    onSuccess()
                } else if (successAttempts++ < 12) {
                    scheduleSuccessCheck()
                }
            }
        }, 900)
    }

    private fun onSuccess() {
        if (successHandled) return
        successHandled = true
        app.requestHome()
        Toast.makeText(this, "充值完成", Toast.LENGTH_SHORT).show()
        binding.webView.postDelayed({
            if (!isFinishing) finish()
        }, 900)
    }

    private fun isPaymentHost(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return PAYMENT_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun isPaymentUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("weixin://") ||
                lower.startsWith("alipays://") ||
                lower.startsWith("alipay://") ||
                lower.contains("tenpay") ||
                lower.contains("wxpay") ||
                lower.contains("cc-pay") ||
                lower.contains("weixin") ||
                lower.contains("alipay") ||
                lower.contains("ccbpay")
    }

    override fun onResume() {
        super.onResume()
        if (leftForPayment) {
            leftForPayment = false
            paymentStarted = true
            successAttempts = 0
            scheduleSuccessCheck()
        }
    }

    override fun onDestroy() {
        if (loaded) {
            val manager = CookieManager.getInstance()
            manager.setCookie(RechargePage.ORIGIN, "JSESSIONID=; Max-Age=0; path=/")
            manager.setCookie(
                "https://${CasAuth.SSO_HOST}",
                "CASTGC=; Max-Age=0; path=/",
            )
            manager.flush()
        }
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.destroy()
        super.onDestroy()
    }

    private companion object {
        val SUCCESS_URL = Regex(
            "(?i)(paysuccess|pay-success|payresult|pay-result|pay_result|" +
                "rechargesuccess|recharge-success|payment/success|order/success|" +
                "result=success|paystatus=success)",
        )
        val PAYMENT_HOSTS = listOf(
            "cc-pay.cn",
            "pass.cc-pay.cn",
            "tenpay.com",
            "wx.tenpay.com",
            "alipay.com",
            "weixin.qq.com",
        )
        const val SUCCESS_SCRIPT =
            "(function(){try{var t=(document.body&&document.body.innerText)||'';" +
                "return /支付成功|充值成功|付款成功|交易成功/.test(t)?'1':'0'}catch(e){return '0'}})()"
    }
}
