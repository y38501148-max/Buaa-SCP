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
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityZfwBinding
import cn.edu.buaa.scp.net.ZfwApi
import cn.edu.buaa.scp.net.ZfwHtml
import cn.edu.buaa.scp.util.LogBus
import org.json.JSONObject

/**
 * 校园网自助服务（zfw.buaa.edu.cn）WebView。
 *
 * 两种入口：
 * - 查看/登录：打开 `/home`，若未登录则显示深澜登录页，用户用校园网账号登录；
 * - 网费充值：打开 `/pay/card`，登录后由官方页面处理金额、验证码与扫码支付。
 *
 * 客户端只负责保存登录后的 Cookie；不保存校园网密码，不接触支付。
 */
class ZfwActivity : AppCompatActivity() {

    private lateinit var binding: ActivityZfwBinding
    private lateinit var app: ScpApp
    private var recharge = false
    private var payLoaded = false
    private var loginHintShown = false
    private var hadSessionAtStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityZfwBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)
        recharge = intent.getBooleanExtra(EXTRA_RECHARGE, false)
        hadSessionAtStart = app.hasZfwSession()

        applyEdgeToEdgeInsets()
        binding.toolbar.title = if (recharge) "网费充值" else "校园网"
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reload) {
                reload()
                true
            } else {
                false
            }
        }
        binding.btnStateAction.setOnClickListener { reload() }
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
        injectStoredCookies()
        openInitialPage()
    }

    private fun openInitialPage() {
        binding.stateBox.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        hideHint()
        binding.webView.loadUrl(if (recharge) ZfwApi.PAY_URL else ZfwApi.HOME_URL)
    }

    private fun reload() {
        payLoaded = false
        openInitialPage()
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
            userAgentString = ZfwApi.DESKTOP_UA
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
                view ?: return
                // 官方缴费页用 target=_blank 提交；WebView 里去掉 target，让结果在原页面展示。
                view.evaluateJavascript(REMOVE_TARGET_SCRIPT, null)
                view.evaluateJavascript(LOGIN_STATE_SCRIPT) { value ->
                    handleLoginState(view, url, value)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                LogBus.warn("校园网页加载失败：${error?.description ?: "网络错误"}")
                showError("校园网页加载失败，请检查网络后重试")
            }
        }
    }

    /** 把本地保存的 Cookie 注入 WebView，已登录时可直接进入首页/缴费页。 */
    private fun injectStoredCookies() {
        val session = app.zfwSession ?: return
        val manager = CookieManager.getInstance()
        session.cookie.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { item ->
            manager.setCookie(ORIGIN, "$item; path=/; Secure")
        }
        manager.flush()
    }

    private fun handleLoginState(view: WebView, url: String?, rawValue: String?) {
        val state = runCatching {
            JSONObject((rawValue ?: "").trim().trim('"').replace("\\\"", "\""))
        }.getOrNull()
        val isLogin = state?.optBoolean("login") ?: false
        if (isLogin) {
            payLoaded = false
            showLoginHint()
            return
        }
        hideHint()
        captureCookies()
        val name = state?.optString("name").orEmpty()
        val username = state?.optString("username").orEmpty()
        if (name.isNotEmpty() || username.isNotEmpty()) {
            app.updateZfwProfile(username, name)
        }

        val path = url.orEmpty()
        if (recharge && !payLoaded && !path.contains("/pay/card")) {
            payLoaded = true
            binding.webView.loadUrl(ZfwApi.PAY_URL)
            return
        }
        if (!recharge && !hadSessionAtStart && !loginHintShown) {
            // 从首页登录入口进来且刚刚完成登录：回到首页展示流量数据。
            app.requestHomeRefresh()
            Toast.makeText(this, "校园网登录成功", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            app.requestHomeRefresh()
        }
    }

    /** 保存 WebView 中的校园网 Cookie（包含 HttpOnly 项）。 */
    private fun captureCookies() {
        val manager = CookieManager.getInstance()
        manager.flush()
        val cookie = manager.getCookie(ORIGIN).orEmpty()
        if (cookie.isBlank()) return
        val current = app.zfwSession
        if (current == null || current.cookie != cookie) {
            app.saveZfwSession(
                username = current?.username.orEmpty(),
                name = current?.name.orEmpty(),
                cookie = cookie,
            )
            app.requestHomeRefresh()
        }
    }

    private fun showLoginHint() {
        loginHintShown = true
        binding.tvHint.visibility = View.VISIBLE
        binding.tvHint.text = "使用校园网账号登录（非统一身份认证密码）"
    }

    private fun hideHint() {
        loginHintShown = false
        binding.tvHint.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.stateBox.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.tvState.text = message
        binding.btnStateAction.visibility = View.VISIBLE
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
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.destroy()
        super.onDestroy()
    }

    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            Ui.applyToolbarTopInset(binding.toolbar, bars.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val EXTRA_RECHARGE = "recharge"
        private const val ORIGIN = "https://zfw.buaa.edu.cn"

        /** 去掉表单的 target=_blank，避免 WebView 无法打开新窗口。 */
        private const val REMOVE_TARGET_SCRIPT =
            "(function(){try{document.querySelectorAll('form[target]').forEach(function(f){f.removeAttribute('target')});" +
                "document.querySelectorAll('a[target=_blank]').forEach(function(a){a.removeAttribute('target')});}catch(e){}})()"

        /** 一次性返回登录状态与页面上的账号/姓名，省去多次跨进程调用。 */
        private const val LOGIN_STATE_SCRIPT =
            "(function(){try{var login=!!document.getElementById('login-form');" +
                "var text=(document.body&&document.body.innerText)||'';" +
                "var um=text.match(/用户名\\s*[:：]?\\s*([0-9A-Za-z]+)/);" +
                "var nameEl=document.querySelector('.user-panel .info p');" +
                "var name=nameEl&&nameEl.innerText?nameEl.innerText.trim():'';" +
                "return JSON.stringify({login:login,username:um?um[1]:'',name:name});" +
                "}catch(e){return JSON.stringify({login:false,username:'',name:''})}})()"
    }
}
