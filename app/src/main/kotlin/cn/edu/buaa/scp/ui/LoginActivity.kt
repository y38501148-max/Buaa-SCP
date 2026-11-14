package cn.edu.buaa.scp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.BuildConfig
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityLoginBinding
import cn.edu.buaa.scp.net.CasAuth
import cn.edu.buaa.scp.net.Html
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.util.LogBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页：会话免密恢复 → 账号密码登录 → 必要时补图形验证码。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var app: ScpApp
    private var busy = false

    /** CAS 失败页快照；带验证码时必须沿用同一个 execution 重新提交。 */
    private var pendingPage: CasAuth.LoginPage? = null
    private var captchaJob: Job? = null

    /** 只处理已证实的图片验证码；滑块等未知类型交给 CasAuth 给出提示。 */
    private val imageCaptcha: Html.Captcha?
        get() = pendingPage?.captcha?.takeIf { it.type == "image" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)

        applyEdgeToEdgeInsets()
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        binding.inputStudent.setText(app.store.rememberedStudentId())

        binding.btnLogin.setOnClickListener { startPasswordLogin() }
        binding.btnSelfCheck.setOnClickListener { runSelfCheck() }
        binding.captchaImageBox.setOnClickListener { refreshCaptcha() }
        binding.imgCaptcha.setOnClickListener { refreshCaptcha() }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startPasswordLogin()
                true
            } else {
                false
            }
        }
        binding.inputCaptcha.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startPasswordLogin()
                true
            } else {
                false
            }
        }

        if (BuildConfig.DEBUG) {
            addDemoEntry()
            if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
                app.enterDemoMode()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
        }
        bootstrap()
        UpdateUi.autoCheck(this)
    }

    /** Debug 构建专用：用离线示例数据预览主界面，不建立任何网络会话。 */
    private fun addDemoEntry() {
        val demo = Ui.lightButton(this, "界面预览（调试）").apply {
            setOnClickListener {
                app.enterDemoMode()
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            }
        }
        (binding.btnSelfCheck.parent as? LinearLayout)?.addView(
            demo,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = Ui.dp(this@LoginActivity, 4f)
                marginStart = Ui.dp(this@LoginActivity, 24f)
                marginEnd = Ui.dp(this@LoginActivity, 24f)
            },
        )
    }

    /** 处理 Android 15 强制 edge-to-edge 的系统栏 insets（含软键盘）。 */
    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    /** 启动时尝试免密恢复本地会话。 */
    private fun bootstrap() {
        val saved = app.session
        if (saved == null || !saved.hasAnything) {
            setStatus("请输入学号与统一身份认证密码")
            return
        }
        setBusy(true)
        setStatus("正在恢复会话…")
        lifecycleScope.launch {
            val restored = try {
                app.recoverSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.warn("会话恢复异常：${SelfCheck.describe(e)}")
                false
            }
            if (restored) {
                app.markSynced()
                goMain("会话已恢复")
            } else {
                setBusy(false)
                setStatus("登录已过期，请重新登录")
                binding.inputPassword.requestFocus()
            }
        }
    }

    private fun startPasswordLogin() {
        if (busy) return
        val studentId = binding.inputStudent.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        val captchaCode = binding.inputCaptcha.text?.toString()?.trim().orEmpty()

        binding.layoutStudent.error = null
        binding.layoutPassword.error = null
        binding.layoutCaptcha.error = null
        if (studentId.isEmpty()) {
            binding.layoutStudent.error = "请输入学号或工号"
            return
        }
        if (password.isEmpty()) {
            binding.layoutPassword.error = "请输入密码"
            return
        }
        if (imageCaptcha != null && captchaCode.isEmpty()) {
            binding.layoutCaptcha.error = "请输入验证码"
            refreshCaptcha()
            return
        }

        hideKeyboard()
        setBusy(true)
        setStatus("正在登录…")
        lifecycleScope.launch {
            try {
                val result = app.cas.login(studentId, password, pendingPage, captchaCode)
                when (result) {
                    is CasAuth.Result.Failure -> onLoginFailure(result, captchaCode.isNotEmpty())
                    is CasAuth.Result.Success -> {
                        setStatus("正在校验会话…")
                        if (!app.validateSession()) {
                            setBusy(false)
                            setStatus("已通过认证，但校园卡会话不可用，请稍后重试")
                            return@launch
                        }
                        app.rememberSession(studentId, result.openid)
                        app.store.setRememberedStudentId(
                            if (binding.checkRemember.isChecked) studentId else "",
                        )
                        app.markSynced()
                        goMain("登录成功")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.error("登录异常：${SelfCheck.describe(e)}")
                setBusy(false)
                setStatus("登录异常：${SelfCheck.describe(e)}")
            }
        }
    }

    private fun onLoginFailure(result: CasAuth.Result.Failure, captchaSubmitted: Boolean) {
        setBusy(false)
        pendingPage = result.page
        val captcha = imageCaptcha
        if (captcha != null) {
            showCaptcha()
            binding.inputCaptcha.setText("")
            loadCaptcha(captcha.id)
            val message = when {
                result.message == "请输入验证码" -> "请输入验证码后继续登录"
                captchaSubmitted && result.message.contains("账号或密码") ->
                    "账号、密码或验证码错误，请重试"
                else -> result.message
            }
            setStatus(message)
            binding.inputCaptcha.requestFocus()
        } else {
            hideCaptcha()
            setStatus("登录失败：${result.message}")
        }
    }

    private fun showCaptcha() {
        binding.captchaRow.visibility = View.VISIBLE
        binding.tvCaptchaTip.visibility = View.VISIBLE
    }

    private fun hideCaptcha() {
        binding.captchaRow.visibility = View.GONE
        binding.tvCaptchaTip.visibility = View.GONE
        binding.inputCaptcha.setText("")
    }

    private fun refreshCaptcha() {
        if (busy) return
        val id = imageCaptcha?.id ?: return
        loadCaptcha(id)
    }

    /** 验证码图片由 CAS 按 captchaId 生成；点击图片可原地刷新。 */
    private fun loadCaptcha(captchaId: String) {
        captchaJob?.cancel()
        binding.imgCaptcha.setImageDrawable(null)
        binding.tvCaptchaLoad.text = getString(R.string.login_captcha_loading)
        binding.tvCaptchaLoad.visibility = View.VISIBLE
        captchaJob = lifecycleScope.launch {
            val bytes = try {
                app.cas.captchaImage(captchaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.warn("验证码加载失败：${SelfCheck.describe(e)}")
                if (isFinishing) return@launch
                binding.tvCaptchaLoad.text = "加载失败，点击重试"
                binding.tvCaptchaLoad.visibility = View.VISIBLE
                return@launch
            }
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            }
            if (isFinishing) return@launch
            if (bitmap == null) {
                binding.tvCaptchaLoad.text = "加载失败，点击重试"
                binding.tvCaptchaLoad.visibility = View.VISIBLE
            } else {
                binding.imgCaptcha.setImageBitmap(bitmap)
                binding.tvCaptchaLoad.visibility = View.GONE
            }
        }
    }

    /** 网络自检：不依赖账号，验证设备到学校服务的链路。 */
    private fun runSelfCheck() {
        if (busy) return
        binding.btnSelfCheck.isEnabled = false
        setStatus("正在自检…")
        lifecycleScope.launch {
            val items = (try {
                SelfCheck.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus("自检异常：${SelfCheck.describe(e)}")
                null
            }) ?: return@launch
            binding.btnSelfCheck.isEnabled = true
            val allOk = items.all { it.ok }
            setStatus(if (allOk) "网络正常" else "自检存在失败项")
            MaterialAlertDialogBuilder(this@LoginActivity)
                .setTitle(if (allOk) "网络自检通过" else "网络自检未通过")
                .setMessage(items.joinToString("\n\n") { it.line() }.ifEmpty { "没有返回结果" })
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun goMain(toast: String) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnLogin.isEnabled = !value
        binding.btnLogin.text = if (value) "正在登录…" else getString(R.string.login_button)
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.inputStudent.isEnabled = !value
        binding.inputPassword.isEnabled = !value
        binding.inputCaptcha.isEnabled = !value
        binding.captchaImageBox.isEnabled = !value
        binding.checkRemember.isEnabled = !value
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    private companion object {
        /** 仅 debug 构建：`adb shell am start ... -e demo true` 直接进入离线预览。 */
        const val EXTRA_DEMO = "demo"
    }
}
