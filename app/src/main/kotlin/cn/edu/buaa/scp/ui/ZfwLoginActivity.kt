package cn.edu.buaa.scp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
 * 只提交给 zfw；客户端不保存密码。若需要图形验证码或短信验证码，会在本页展开对应输入框。
 * 异常/风控场景可一键回退到学校官方网页登录。
 */
class ZfwLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityZfwLoginBinding
    private lateinit var app: ScpApp

    private var page: ZfwAuth.LoginPage? = null
    private var smsStage = false
    private var busy = false
    private var recharge = false
    private var captchaJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityZfwLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)
        recharge = intent.getBooleanExtra(EXTRA_RECHARGE, false)

        applyEdgeToEdgeInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.inputUsername.setText(app.store.rememberedZfwUsername())
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "登录页加载失败：${SelfCheck.describe(e)}")
                LogBus.warn("校园网登录页加载失败：${SelfCheck.describe(e)}")
            }
        }
    }

    private fun submit() {
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

        setBusy(true, if (smsStage) "正在校验短信验证码…" else "正在登录校园网…")
        lifecycleScope.launch {
            try {
                if (smsStage) {
                    val smsOk = app.zfwAuth.validateSmsCode(loginPage, username, sms)
                    if (!smsOk) {
                        setBusy(false, "短信验证码错误，请重新输入")
                        return@launch
                    }
                    finishLogin(loginPage, username, password, captcha, sms)
                    return@launch
                }

                val result = app.zfwAuth.validateUser(loginPage, username, password, captcha)
                if (!result.success) {
                    if (result.captchaRequired) {
                        showCaptcha()
                        setBusy(false, "请输入图形验证码")
                        loadCaptcha()
                    } else {
                        setBusy(false, result.message.ifBlank { "账号或密码错误" })
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
                    return@launch
                }
                finishLogin(loginPage, username, password, captcha, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setBusy(false, "登录失败：${SelfCheck.describe(e)}")
                LogBus.warn("校园网原生登录失败：${SelfCheck.describe(e)}")
            }
        }
    }

    private suspend fun finishLogin(
        loginPage: ZfwAuth.LoginPage,
        username: String,
        password: String,
        captcha: String,
        sms: String,
    ) {
        app.zfwAuth.submitLogin(loginPage, username, password, captcha, sms)
        app.store.setRememberedZfwUsername(username)
        val cookie = app.zfwAuth.cookieHeader()
        val home = runCatching { app.zfwApi.fetchHome() }.getOrNull()
        if (home == null) {
            throw IllegalStateException("登录成功但网费首页不可用，请稍后重试")
        }
        app.saveZfwSession(username, home.name, cookie)
        binding.inputPassword.text?.clear()
        app.requestHome()
        if (!isFinishing) {
            MaterialAlertDialogBuilder(this)
                .setTitle("校园网登录成功")
                .setMessage("已保存会话，下次打开网费时无需重复登录。")
                .setPositiveButton("继续") { _, _ -> goNext() }
                .setCancelable(false)
                .show()
        }
        setBusy(false, "登录成功")
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
        loadCaptcha()
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
    }
}
