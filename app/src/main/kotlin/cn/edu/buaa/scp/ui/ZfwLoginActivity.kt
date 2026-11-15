package cn.edu.buaa.scp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityZfwLoginBinding
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.net.ZfwAuth
import cn.edu.buaa.scp.store.LoginPolicy
import cn.edu.buaa.scp.util.LogBus
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校园网原生登录页。
 *
 * 与「统一身份认证」登录独立：使用校园网账号/密码，密码经官方登录页随机 RSA 公钥加密后
 * 只提交给 zfw。默认不保存密码；用户显式开启「记住密码」后，密码经系统密钥库加密保存，
 * 「自动登录」会在打开本页时自动提交一次。图形/短信验证码等必须由用户完成的步骤会
 * 自动降级为手动输入。
 */
class ZfwLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityZfwLoginBinding
    private lateinit var app: ScpApp

    private var page: ZfwAuth.LoginPage? = null
    private var smsStage = false
    private var busy = false
    private var recharge = false
    private var captchaJob: Job? = null

    /** 每次进入本页最多自动尝试一次，失败后交给用户手动完成。 */
    private var autoLoginAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityZfwLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)
        recharge = intent.getBooleanExtra(EXTRA_RECHARGE, false)

        applyEdgeToEdgeInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        // 校园网账号默认记住；老会话里已有账号时回填。
        binding.inputUsername.setText(
            app.store.rememberedZfwUsername().ifBlank { app.zfwSession?.username.orEmpty() },
        )
        setupCredentialOptions()
        // 旋转/重建时不要重复自动提交密码。
        autoLoginAttempted = savedInstanceState?.getBoolean(KEY_AUTO_ATTEMPTED, false) ?: false
        binding.btnLogin.setOnClickListener { submit() }
        binding.captchaImageBox.setOnClickListener { loadCaptcha() }
        binding.btnWebLogin.setOnClickListener { openWebLogin() }
        binding.inputPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        // 已有会话且仍可用时直接进入目标页，避免让用户重复登录。
        if (app.hasZfwSession()) {
            lifecycleScope.launch {
                val result = runCatching { app.zfwApi.fetchHome() }
                if (result.isSuccess) {
                    goNext()
                } else {
                    // 只有明确会话失效才清本地 Cookie；网络异常时保留，避免误删。
                    val error = result.exceptionOrNull()
                    if (error is cn.edu.buaa.scp.net.ApiException && error.authExpired) {
                        app.clearZfwSession()
                    }
                    loadPage()
                }
            }
        } else {
            loadPage()
        }
    }

    /**
     * 「记住密码 / 自动登录」：账号总是记住，密码与自动登录由用户显式开启。
     * 自动登录依赖记住密码；关闭记住密码会立即清除已保存的密码并关闭自动登录。
     */
    private fun setupCredentialOptions() {
        val remember = app.store.zfwRememberPassword()
        binding.checkRememberPassword.isChecked = remember
        binding.checkAutoLogin.isChecked = app.store.zfwAutoLogin() && remember
        if (remember) {
            app.store.loadZfwPassword()?.takeIf { it.isNotEmpty() }?.let {
                binding.inputPassword.setText(it)
            }
        }

        binding.checkRememberPassword.setOnCheckedChangeListener { _, checked ->
            app.store.setZfwRememberPassword(checked)
            if (!checked && binding.checkAutoLogin.isChecked) {
                binding.checkAutoLogin.isChecked = false
            }
        }
        binding.checkAutoLogin.setOnCheckedChangeListener { _, checked ->
            if (checked && !binding.checkRememberPassword.isChecked) {
                binding.checkRememberPassword.isChecked = true
            }
            app.store.setZfwAutoLogin(checked)
        }
    }

    private fun loadPage() {
        setBusy(true, "正在加载登录页…")
        lifecycleScope.launch {
            try {
                val loaded = app.zfwAuth.loadLoginPage()
                if (loaded == null) {
                    // Cookie 仍是登录态（例如刚在网页里登录过）。
                    app.saveZfwSession(
                        username = binding.inputUsername.text?.toString()?.trim().orEmpty(),
                        name = "",
                        cookie = app.zfwAuth.cookieHeader(),
                    )
                    goNext()
                    return@launch
                }
                page = loaded
                setBusy(false, "请输入校园网账号与密码")
                maybeAutoLogin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "登录页加载失败：${SelfCheck.describe(e)}")
                LogBus.warn("校园网登录页加载失败：${SelfCheck.describe(e)}")
            }
        }
    }

    /** 登录页就绪后，若已开启自动登录则直接提交一次保存的账号密码。 */
    private fun maybeAutoLogin() {
        if (autoLoginAttempted) return
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = app.store.loadZfwPassword().orEmpty()
        // 用户在登录页加载失败后手工输入了新密码：不要用保存的旧密码覆盖并提交。
        val typedPassword = binding.inputPassword.text?.toString().orEmpty()
        if (typedPassword.isNotEmpty() && typedPassword != password) return
        val remembered = app.store.rememberedZfwUsername()
        if (remembered.isNotBlank() && username != remembered) return
        if (!LoginPolicy.canAutoLogin(
                app.store.zfwRememberPassword(),
                app.store.zfwAutoLogin(),
                username,
                password,
            )
        ) {
            return
        }
        autoLoginAttempted = true
        binding.inputPassword.setText(password)
        submit(auto = true)
    }

    private fun submit(auto: Boolean = false) {
        if (busy) return
        val loginPage = page
        if (loginPage == null) {
            loadPage()
            return
        }
        val username = binding.inputUsername.text?.toString()?.trim().orEmpty()
        val password = binding.inputPassword.text?.toString().orEmpty()
        val captcha = binding.inputCaptcha.text?.toString()?.trim().orEmpty()
        val sms = binding.inputSms.text?.toString()?.trim().orEmpty()

        binding.layoutUsername.error = null
        binding.layoutPassword.error = null
        binding.layoutCaptcha.error = null
        binding.layoutSms.error = null
        if (username.isEmpty()) {
            binding.layoutUsername.error = "请输入校园网账号"
            return
        }
        if (password.isEmpty()) {
            binding.layoutPassword.error = "请输入校园网密码"
            return
        }
        if (binding.captchaRow.visibility == View.VISIBLE && captcha.isEmpty()) {
            binding.layoutCaptcha.error = "请输入图形验证码"
            loadCaptcha()
            return
        }
        if (smsStage && sms.isEmpty()) {
            binding.layoutSms.error = "请输入短信验证码"
            return
        }

        setBusy(
            true,
            when {
                auto -> "正在自动登录校园网…"
                smsStage -> "正在校验短信验证码…"
                else -> "正在登录校园网…"
            },
        )
        lifecycleScope.launch {
            try {
                if (smsStage) {
                    val smsOk = app.zfwAuth.validateSmsCode(loginPage, username, sms)
                    if (!smsOk) {
                        setBusy(false, "短信验证码错误，请重新输入")
                        return@launch
                    }
                    finishLogin(loginPage, username, password, captcha, sms, auto)
                    return@launch
                }

                val result = app.zfwAuth.validateUser(loginPage, username, password, captcha)
                if (!result.success) {
                    if (result.captchaRequired) {
                        showCaptcha()
                        setBusy(
                            false,
                            if (auto) "自动登录需要图形验证码，请输入后继续" else "请输入图形验证码",
                        )
                        loadCaptcha()
                        if (auto) disableAutoLogin()
                    } else {
                        setBusy(false, result.message.ifBlank { "账号或密码错误" })
                        if (auto) onAutoLoginRejected(result.message)
                    }
                    return@launch
                }
                if (result.inputSms) {
                    smsStage = true
                    binding.layoutSms.visibility = View.VISIBLE
                    binding.inputSms.requestFocus()
                    setBusy(
                        false,
                        result.message.ifBlank {
                            "短信验证码已发送，请填写后再次点击登录"
                        },
                    )
                    if (auto) {
                        disableAutoLogin()
                        binding.tvStatus.text = "已发送短信验证码，请填写后继续登录"
                        binding.inputSms.requestFocus()
                    }
                    return@launch
                }
                finishLogin(loginPage, username, password, captcha, "", auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "登录失败：${SelfCheck.describe(e)}")
                if (auto) {
                    // 自动提交阶段的服务端异常（CSRF 过期、风控、首页接口故障等）
                    // 也视为本次自动登录失败，避免下次打开再次提交。
                    disableAutoLogin()
                    Toast.makeText(
                        this@ZfwLoginActivity,
                        "自动登录未完成，已关闭自动登录，请手动登录",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                LogBus.warn("校园网原生登录失败：${SelfCheck.describe(e)}")
            }
        }
    }

    /**
     * 自动登录失败：关闭自动登录，避免每次打开都重复提交。
     * 服务端明确判定密码错误时清除保存的密码；验证码/短信场景保留密码。
     */
    private fun onAutoLoginRejected(message: String) {
        val interaction = LoginPolicy.requiresInteraction(message)
        val rejected = LoginPolicy.shouldForgetPassword(message, interaction)
        disableAutoLogin()
        if (rejected) {
            app.store.setZfwRememberPassword(false)
            binding.checkRememberPassword.isChecked = false
            binding.inputPassword.text?.clear()
            binding.tvStatus.text =
                "自动登录失败：${message.ifBlank { "账号或密码错误" }}，已清除保存的密码，请手动登录"
        } else {
            binding.tvStatus.text = "自动登录失败：${message.ifBlank { "请手动登录" }}"
        }
        Toast.makeText(this, binding.tvStatus.text, Toast.LENGTH_LONG).show()
    }

    private fun disableAutoLogin() {
        app.store.setZfwAutoLogin(false)
        if (binding.checkAutoLogin.isChecked) binding.checkAutoLogin.isChecked = false
    }

    private suspend fun finishLogin(
        loginPage: ZfwAuth.LoginPage,
        username: String,
        password: String,
        captcha: String,
        sms: String,
        auto: Boolean,
    ) {
        app.zfwAuth.submitLogin(loginPage, username, password, captcha, sms)
        app.store.setRememberedZfwUsername(username)
        val cookie = app.zfwAuth.cookieHeader()
        val home = runCatching { app.zfwApi.fetchHome() }.getOrNull()
        if (home == null) {
            throw IllegalStateException("登录成功但网费首页不可用，请稍后重试")
        }
        app.saveZfwSession(username, home.name, cookie)
        if (binding.checkRememberPassword.isChecked) {
            app.store.saveZfwPassword(password)
        } else {
            app.store.clearZfwPassword()
        }
        binding.inputPassword.text?.clear()
        app.requestHome()
        setBusy(false, "登录成功")
        if (!isFinishing) {
            if (auto) {
                Toast.makeText(this, "校园网已自动登录", Toast.LENGTH_SHORT).show()
                goNext()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle("校园网登录成功")
                    .setMessage("已保存会话，下次打开网费时无需重复登录。")
                    .setPositiveButton("继续") { _, _ -> goNext() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun goNext() {
        if (recharge) {
            startActivity(
                Intent(this, ZfwActivity::class.java)
                    .putExtra(ZfwActivity.EXTRA_RECHARGE, true),
            )
        }
        finish()
    }

    private fun openWebLogin() {
        startActivity(
            Intent(this, ZfwActivity::class.java)
                .putExtra(ZfwActivity.EXTRA_RECHARGE, recharge),
        )
        finish()
    }

    private fun showCaptcha() {
        binding.captchaRow.visibility = View.VISIBLE
    }

    private fun loadCaptcha() {
        if (busy) return
        captchaJob?.cancel()
        binding.imgCaptcha.setImageDrawable(null)
        binding.tvCaptchaLoad.text = "加载中…"
        binding.tvCaptchaLoad.visibility = View.VISIBLE
        captchaJob = lifecycleScope.launch {
            val bytes = try {
                app.zfwAuth.captchaImage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFinishing) {
                    binding.tvCaptchaLoad.text = "加载失败，点击重试"
                    binding.tvCaptchaLoad.visibility = View.VISIBLE
                }
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

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !value
        binding.btnLogin.text = if (value) "请稍候…" else if (smsStage) "验证并登录" else "登录"
        binding.tvStatus.text = status
        binding.inputUsername.isEnabled = !value
        binding.inputPassword.isEnabled = !value
        binding.inputCaptcha.isEnabled = !value
        binding.inputSms.isEnabled = !value
        binding.captchaImageBox.isEnabled = !value
        binding.btnWebLogin.isEnabled = !value
        binding.checkRememberPassword.isEnabled = !value
        binding.checkAutoLogin.isEnabled = !value
        if (!value) hideKeyboard()
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    override fun onDestroy() {
        captchaJob?.cancel()
        binding.inputPassword.text?.clear()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_ATTEMPTED, autoLoginAttempted)
    }

    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            Ui.applyToolbarTopInset(binding.toolbar, bars.top)
            view.setPadding(0, 0, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val EXTRA_RECHARGE = "recharge"
        const val KEY_AUTO_ATTEMPTED = "zfw_auto_login_attempted"
    }
}
