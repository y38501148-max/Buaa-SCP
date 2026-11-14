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
import cn.edu.buaa.scp.net.CasAuth
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRechargeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)

        applyEdgeToEdgeInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnReload.setOnClickListener { openPage() }
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft, bars.top,
                binding.toolbar.paddingRight, binding.toolbar.paddingBottom,
            )
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
                if (url != null && url.contains("/pages/common/fail/fail")) {
                    showState("充值页会话已失效，请返回重新登录后再试", action = true)
                }
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
            openTarget(openid)
        }
    }

    private fun openTarget(openid: String) {
        val jsessionid = app.session?.jsessionid.orEmpty()
        if (jsessionid.isEmpty()) {
            showState("缺少校园卡会话，请返回重新登录", action = false)
            return
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
        if (!isFinishing) openPage()
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
}
