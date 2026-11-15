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
import cn.edu.buaa.scp.store.LoginPolicy
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

    /** 每次进入本页最多自动尝试一次，失败后交给用户手动完成。 */
    private var autoLoginAttempted = false

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
        // 学号默认记住；旧版本未开启「记住学号」时回填已保存会话里的学号。
        binding.inputStudent.setText(
            app.store.rememberedStudentId().ifBlank { app.session?.studentId.orEmpty() },
        )
        setupCredentialOptions()
        // 旋转/重建时不要重复自动提交密码。
        autoLoginAttempted = savedInstanceState?.getBoolean(KEY_AUTO_ATTEMPTED, false) ?: false

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

    /**
     * 「记住密码 / 自动登录」：学号总是记住，密码与自动登录由用户显式开启。
     *
     * 自动登录依赖记住密码；关闭记住密码会立即清除已保存的密码并关闭自动登录。
     */
    private fun setupCredentialOptions() {
        val remember = app.store.casRememberPassword()
        binding.checkRememberPassword.isChecked = remember
        binding.checkAutoLogin.isChecked = app.store.casAutoLogin() && remember
        if (remember) {
            app.store.loadCasPassword()?.takeIf { it.isNotEmpty() }?.let {
                binding.inputPassword.setText(it)
            }
        }

        binding.checkRememberPassword.setOnCheckedChangeListener { _, checked ->
            app.store.setCasRememberPassword(checked)
            if (!checked && binding.checkAutoLogin.isChecked) {
                binding.checkAutoLogin.isChecked = false
            }
        }
        binding.checkAutoLogin.setOnCheckedChangeListener { _, checked ->
            if (checked && !binding.checkRememberPassword.isChecked) {
                binding.checkRememberPassword.isChecked = true
            }
            app.store.setCasAutoLogin(checked)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_ATTEMPTED, autoLoginAttempted)
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

    /** 启动时先尝试免密恢复本地会话；失败且已开启自动登录时再用保存的密码登录。 */
    private fun bootstrap() {
        val saved = app.session
        if (saved == null || !saved.hasAnything) {
            if (autoLoginReady() && !autoLoginAttempted) {
                lifecycleScope.launch { tryAutoLogin() }
            } else {
                setStatus("请输入学号与统一身份认证密码")
            }
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
            } else if (!tryAutoLogin()) {
                setBusy(false)
                setStatus("登录已过期，请重新登录")
                binding.inputPassword.requestFocus()
            }
        }
    }

    private fun autoLoginReady(): Boolean = LoginPolicy.canAutoLogin(
        rememberPassword = app.store.casRememberPassword(),
        autoLogin = app.store.casAutoLogin(),
        account = binding.inputStudent.text?.toString()?.trim()
            .orEmpty()
            .ifEmpty { app.store.rememberedStudentId() },
        password = app.store.loadCasPassword(),
    )

    /**
     * 用本机保存的统一认证密码自动登录。
     *
     * @return 是否发起了自动登录（无论成功失败）；false 表示条件不满足，调用方继续展示手动登录。
     */
    private suspend fun tryAutoLogin(): Boolean {
        if (autoLoginAttempted) return false
        val studentId = binding.inputStudent.text?.toString()?.trim()
            .orEmpty()
            .ifEmpty { app.store.rememberedStudentId() }
        val password = app.store.loadCasPassword() ?: return false
        if (!LoginPolicy.canAutoLogin(
                app.store.casRememberPassword(),
                app.store.casAutoLogin(),
                studentId,
                password,
            )
        ) {
            return false
        }

        autoLoginAttempted = true
        binding.inputStudent.setText(studentId)
        binding.inputPassword.setText(password)
        hideCaptcha()
        setBusy(true)
        setStatus("正在自动登录…")
        LogBus.step("使用本机保存的密码自动登录统一身份认证")
        try {
            when (val result = app.cas.login(studentId, password, null, "")) {
                is CasAuth.Result.Success -> {
                    setStatus("正在校验会话…")
                    if (!app.validateSession()) {
                        setBusy(false)
                        setStatus("已通过认证，但校园卡会话不可用，请稍后重试")
                        return true
                    }
                    app.rememberSession(studentId, result.openid)
                    app.store.setRememberedStudentId(studentId)
                    app.markSynced()
                    goMain("已自动登录")
                }

                is CasAuth.Result.Failure -> onAutoLoginFailure(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络等临时异常保留设置，下次启动仍可自动尝试。
            LogBus.warn("自动登录异常：${SelfCheck.describe(e)}")
            setBusy(false)
            setStatus("自动登录失败：${SelfCheck.describe(e)}，请手动登录")
        }
        return true
    }

    /**
     * 自动登录失败：一律关闭自动登录，避免每次启动重复提交。
     *
     * 服务端明确判定密码错误时清除保存的密码；验证码等需要用户介入的场景保留密码，
     * 用户补齐验证码后可以直接继续登录。
     */
    private fun onAutoLoginFailure(result: CasAuth.Result.Failure) {
        setBusy(false)
        pendingPage = result.page
        val captcha = imageCaptcha
        val interaction = captcha != null || LoginPolicy.requiresInteraction(result.message)
        val rejected = LoginPolicy.shouldForgetPassword(result.message, interaction)

        app.store.setCasAutoLogin(false)
        binding.checkAutoLogin.isChecked = false
        if (rejected) {
            app.store.setCasRememberPassword(false)
            binding.checkRememberPassword.isChecked = false
            binding.inputPassword.text?.clear()
        }

        if (captcha != null) {
            showCaptcha()
            binding.inputCaptcha.setText("")
            loadCaptcha(captcha.id)
            // 有验证码时上面不会判定为密码错误，密码保持可用，补齐验证码即可继续。
            setStatus("已填入保存的账号密码，请输入验证码完成登录")
            binding.inputCaptcha.requestFocus()
        } else {
            setStatus(
                if (rejected) {
                    "自动登录失败：${result.message}，已清除保存的密码，请手动登录"
                } else {
                    "自动登录失败：${result.message}，请手动登录"
                },
            )
            binding.inputPassword.requestFocus()
        }
        // 软键盘可能把状态文字顶出屏幕，再用 Toast 明确告知自动登录已降级。
        Toast.makeText(this, binding.tvStatus.text, Toast.LENGTH_LONG).show()
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
                        if (binding.checkRememberPassword.isChecked) {
                            app.store.saveCasPassword(password)
                        } else {
                            app.store.clearCasPassword()
                        }
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
        binding.checkRememberPassword.isEnabled = !value
        binding.checkAutoLogin.isEnabled = !value
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
        const val KEY_AUTO_ATTEMPTED = "auto_login_attempted"
    }
}
