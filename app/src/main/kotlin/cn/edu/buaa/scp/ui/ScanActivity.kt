package cn.edu.buaa.scp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.net.ScanOutcome
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ScanActivity : AppCompatActivity() {
    private lateinit var app: ScpApp
    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var torch: com.google.android.material.button.MaterialButton
    private var scanning = false
    private var submitted = false
    private var torchOn = false
    private var web: WebView? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginScan() else showPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ScpApp.get(this)
        if (!app.demoMode) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = Ui.column(this).apply { setBackgroundResource(R.color.bg) }
        val toolbar = MaterialToolbar(this).apply {
            title = "扫一扫"
            setTitleTextAppearance(this@ScanActivity, R.style.TextAppearance_Scp_Toolbar)
            setTitleTextColor(Ui.color(this@ScanActivity, R.color.text_primary))
            setNavigationIcon(R.drawable.ic_back)
            navigationContentDescription = "返回"
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundResource(R.color.surface)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, Ui.dp(this, 56f)))
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            Ui.applyToolbarTopInset(toolbar, bars.top)
            root.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        scanner = DecoratedBarcodeView(this).apply {
            setStatusText("")
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128))
        }
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() = Unit
            override fun previewStopped() = Unit
            override fun cameraClosed() = Unit
            override fun cameraError(error: Exception) {
                if (!isFinishing && scanning) showResult("相机无法启动", "重试") { beginScan() }
            }
        })
        torch = Ui.lightButton(this, "打开手电筒").apply {
            minimumHeight = Ui.dp(this@ScanActivity, 48f)
            setOnClickListener {
                torchOn = !torchOn
                if (torchOn) scanner.setTorchOn() else scanner.setTorchOff()
                text = if (torchOn) "关闭手电筒" else "打开手电筒"
            }
            visibility = if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) View.VISIBLE else View.GONE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 退出即可；不回退到可能重发提交的网页或扫描确认。
                finish()
            }
        })
        submitted = savedInstanceState?.getBoolean("submitted") == true
        if (submitted) showResult("请查看账单确认结果") else if (cameraAllowed()) beginScan()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun cameraAllowed() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun beginScan() {
        if (submitted || !cameraAllowed()) return
        frame.removeAllViews()
        frame.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        frame.addView(torch, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@ScanActivity, 24f)
        })
        scanning = true
        scanner.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (!scanning || submitted) return
                scanning = false
                scanner.pause()
                torchOn = false
                torch.text = "打开手电筒"
                val code = result.text.orEmpty()
                if (code.isBlank() || code.length > 8192) {
                    showResult("二维码内容无效")
                    return
                }
                MaterialAlertDialogBuilder(this@ScanActivity)
                    .setTitle("确认扫码")
                    .setMessage("使用校园卡支付或执行本次扫码操作？")
                    .setNegativeButton("取消") { _, _ -> beginScan() }
                    .setPositiveButton("确认") { _, _ -> submit(code) }
                    .setOnCancelListener { beginScan() }
                    .show()
            }
        })
        scanner.resume()
    }

    private fun showPermission() {
        showResult("相机权限未开启", "去设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun submit(code: String) {
        if (submitted) return
        submitted = true
        showResult("正在提交…", action = null)
        lifecycleScope.launch {
            if (app.demoMode) {
                showResult("示例模式")
                return@launch
            }
            try {
                // 单次提交：不会续期重放、自动重试，也不会在旋转或恢复时重发。
                val result = app.paymentApi.consume(app.openid(), code, System.currentTimeMillis())
                val outcome = ScanOutcome.parse(result, app.openid())
                app.requestHomeRefresh()
                if (outcome.followUp != null) openFollowUp(outcome.followUp) else showResult(outcome.message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showResult("结果未确认，请查看账单")
            }
        }
    }

    private fun showResult(message: String, action: String? = "完成", onAction: () -> Unit = { finish() }) {
        scanning = false
        scanner.pause()
        frame.removeAllViews()
        val column = Ui.column(this).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@ScanActivity, 24f), 0, Ui.dp(this@ScanActivity, 24f), 0)
        }
        column.addView(Ui.text(this, message, 16f, R.color.text_primary).apply { gravity = Gravity.CENTER })
        if (action != null) column.addView(Ui.lightButton(this, action).apply { setOnClickListener { onAction() } }, Ui.rowParams(this, 16f))
        frame.addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openFollowUp(url: String) {
        val sid = app.jar.valueOf("m.icw.buaa.edu.cn", "JSESSIONID").orEmpty()
        if (sid.isBlank()) { showResult("会话已失效"); return }
        val page = WebView(this)
        web = page
        with(page.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = cn.edu.buaa.scp.net.HttpEngine.UA
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return uri.scheme != "https" || uri.host != "m.icw.buaa.edu.cn" ||
                    uri.port !in listOf(-1, 443) || !uri.path.orEmpty().startsWith("/card")
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val route = url?.substringAfter('#', "").orEmpty()
                if (route.startsWith("/pages/common/success/")) {
                    app.requestHomeRefresh()
                    showResult("操作成功")
                } else if (route.startsWith("/pages/common/fail/") || route.startsWith("/pages/common/error/")) {
                    showResult(cn.edu.buaa.scp.net.Html.failPageMessage(url.orEmpty()) ?: "操作未完成")
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) showResult("页面加载失败")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame) showResult("页面加载失败（${errorResponse.statusCode}）")
            }
        }
        CookieManager.getInstance().setCookie("https://m.icw.buaa.edu.cn", "JSESSIONID=$sid; Path=/; Secure; HttpOnly") {
            if (!isDestroyed && !isFinishing) {
                frame.removeAllViews()
                frame.addView(page, FrameLayout.LayoutParams(-1, -1))
                page.loadUrl(url)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!submitted && cameraAllowed()) {
            if (scanning) scanner.resume()
            else if (frame.indexOfChild(scanner) < 0) beginScan()
        }
        web?.onResume()
    }

    override fun onPause() {
        scanner.pause()
        web?.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("submitted", submitted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scanner.pause()
        web?.apply { stopLoading(); destroy() }
        if (web != null) CookieManager.getInstance().setCookie("https://m.icw.buaa.edu.cn", "JSESSIONID=; Max-Age=0; Path=/")
        super.onDestroy()
    }
}
