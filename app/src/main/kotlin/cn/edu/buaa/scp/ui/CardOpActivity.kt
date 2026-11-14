package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityCardOpBinding
import cn.edu.buaa.scp.databinding.FormFieldBinding
import cn.edu.buaa.scp.net.ApiException
import cn.edu.buaa.scp.net.CardOps
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.net.resultOf
import cn.edu.buaa.scp.util.Json
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 卡务写操作表单页：挂失 / 解挂 / 改密 / 改限额 / 绑卡 / 解绑。
 *
 * 安全约束：
 * - 校园卡密码只存在于输入框内存中，提交后立即清空，不落盘、不写日志；
 * - 每次提交前都有明确的二次确认；
 * - 失败不自动重试，避免写操作重复执行。
 */
class CardOpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardOpBinding
    private lateinit var app: ScpApp
    private lateinit var op: CardOp

    private var initData: JSONObject? = null
    private var loadJob: Job? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardOpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)
        op = runCatching {
            CardOp.valueOf(intent.getStringExtra(EXTRA_OP).orEmpty())
        }.getOrDefault(CardOp.LOSS)

        applyEdgeToEdgeInsets()
        binding.toolbar.title = op.title
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { loadInit() }
        loadInit()
    }

    // ---------------- 初始化 ----------------

    private fun loadInit() {
        loadJob?.cancel()
        showLoading()
        loadJob = lifecycleScope.launch {
            val result = if (app.demoMode) {
                resultOf { demoInit() }
            } else {
                resultOf {
                    app.withRecovery {
                        when (op) {
                            CardOp.LOSS -> app.api.reportLossInfo()
                            CardOp.UNLOSS -> app.api.solutionHangInfo()
                            CardOp.PASSWORD -> app.api.passwordModifyInfo()
                            CardOp.QUOTA -> app.api.quotaModifyInfo()
                            CardOp.BIND_BANK, CardOp.UNBIND_BANK -> app.api.bankCardInfo()
                        }
                    }
                }
            }
            if (result.value == null) {
                showError(result.error ?: "读取卡片信息失败")
                return@launch
            }
            // 业务接口统一返回 {"data": {...}, "success": true}，这里取内层数据供表单使用。
            val payload = result.value.optJSONObject("data") ?: result.value
            initData = payload
            render(payload)
        }
    }

    private fun demoInit(): JSONObject = JSONObject().apply {
        put(
            "data",
            JSONObject().apply {
                put("idserial", "20250001")
                put("username", "示例同学")
                put("accstatusStr", "正常")
                put("haveCard", "1")
                put("haveQrcode", "1")
                put("cardid", "DEMO-CARD")
                put("qrcodeid", "DEMO-QRCODE")
                put("cardmaxconsamt", "50.00")
                put("cardmaxconstolamt", "200.00")
                put("qrcodemaxconsamt", "20.00")
                put("qrcodemaxconstolamt", "100.00")
                put("bankcardno", "6222020200001234567")
            },
        )
        put("success", true)
    }

    // ---------------- 渲染 ----------------

    private fun render(data: JSONObject) {
        binding.loadingBox.visibility = View.GONE
        binding.errorBox.visibility = View.GONE
        binding.scroll.visibility = View.VISIBLE
        val body = binding.body
        body.removeAllViews()
        val context = this

        when (op) {
            CardOp.LOSS, CardOp.UNLOSS -> renderPasswordConfirm(context, body, data)
            CardOp.PASSWORD -> renderPasswordModify(context, body, data)
            CardOp.QUOTA -> renderQuota(context, body, data)
            CardOp.BIND_BANK -> renderBindBank(context, body, data)
            CardOp.UNBIND_BANK -> renderUnbindBank(context, body, data)
        }

    }

    private fun addCardInfo(context: Context, body: LinearLayout, data: JSONObject) {
        val section = Ui.addSection(body, "卡片信息")
        Ui.addKv(section, "姓名", Json.or(data.optString("username"), "—"))
        Ui.addKv(section, "学号 / 工号", Json.or(data.optString("idserial"), "—"))
        val status = data.optString("accstatusStr")
        if (status.isNotEmpty()) {
            Ui.addKv(
                section,
                "卡状态",
                status,
                if (status.contains("正常")) R.color.green else R.color.orange,
            )
        }
    }

    // ---------------- 挂失 / 解挂 ----------------

    private fun renderPasswordConfirm(context: Context, body: LinearLayout, data: JSONObject) {
        addCardInfo(context, body, data)
        val section = Ui.addSection(body, "身份验证")
        val pwd = addPasswordField(context, section, "校园卡密码（6 位数字）")
        addSubmitButton(context, body, if (op.danger) "确认挂失" else "确认解挂", danger = op.danger) {
            val password = textOf(pwd)
            if (!CardOps.isCardPassword(password)) {
                pwd.error = "请输入 6 位数字校园卡密码"
                return@addSubmitButton
            }
            confirm(
                title = op.title,
                message = if (op == CardOp.LOSS) {
                    "是否挂失您的校园卡？挂失后卡片将立即冻结。"
                } else {
                    "是否解挂您的校园卡？解挂后卡片恢复正常使用。"
                },
            ) {
                submit { cardApiCall(data, password) }
            }
        }
    }

    private suspend fun cardApiCall(data: JSONObject, password: String): JSONObject {
        val openid = requireOpenid()
        val username = data.optString("username")
        val idserial = data.optString("idserial")
        return when (op) {
            CardOp.LOSS -> app.api.reportLoss(openid, username, idserial, password)
            CardOp.UNLOSS -> app.api.solutionHang(openid, username, idserial, password)
            else -> throw ApiException("未知操作")
        }
    }

    // ---------------- 密码修改 ----------------

    private fun renderPasswordModify(context: Context, body: LinearLayout, data: JSONObject) {
        addCardInfo(context, body, data)
        val section = Ui.addSection(body, "修改支付密码")
        val oldField = addPasswordField(context, section, "原密码（6 位数字）")
        val newField = addPasswordField(context, section, "新密码（6 位数字）")
        val confirmField = addPasswordField(context, section, "确认新密码")
        addSubmitButton(context, body, "确认修改") {
            val old = textOf(oldField)
            val new = textOf(newField)
            val again = textOf(confirmField)
            var ok = true
            if (!CardOps.isCardPassword(old)) {
                oldField.error = "原密码为 6 位数字"
                ok = false
            }
            if (!CardOps.isCardPassword(new)) {
                newField.error = "新密码为 6 位数字"
                ok = false
            }
            if (new != again) {
                confirmField.error = "两次输入的新密码不一致"
                ok = false
            }
            if (!ok) return@addSubmitButton
            confirm("修改密码", "确认将校园卡支付密码修改为新密码？") {
                val idserial = data.optString("idserial")
                val openid = requireOpenid()
                submit {
                    app.api.modifyPassword(idserial, openid, old, new, again)
                }
            }
        }
    }

    // ---------------- 限额修改 ----------------

    private fun renderQuota(context: Context, body: LinearLayout, data: JSONObject) {
        addCardInfo(context, body, data)
        val haveCard = data.optString("haveCard").ifBlank { "1" } != "0"
        val haveQrcode = data.optString("haveQrcode").ifBlank { "1" } != "0" &&
            data.optString("qrcodeid").isNotBlank()

        if (!haveCard && !haveQrcode) {
            addEmpty("暂未开通校园卡或校园码，无法修改限额")
            return
        }

        if (haveCard) {
            val section = Ui.addSection(body, "校园卡限额")
            Ui.addKv(section, "当前单笔限额", quotaText(data.optString("cardmaxconsamt")))
            Ui.addKv(section, "当前单日限额", quotaText(data.optString("cardmaxconstolamt")))
            val perTime = addAmountField(context, section, "新单笔限额（元）")
            val perDay = addAmountField(context, section, "新单日限额（元）")
            val pwd = addPasswordField(context, section, "校园卡密码（6 位数字）")
            addSubmitButton(context, body, "保存校园卡限额") {
                val perTimeYuan = textOf(perTime)
                val perDayYuan = textOf(perDay)
                val password = textOf(pwd)
                if (!validateQuota(perTime, perDay, pwd, perTimeYuan, perDayYuan, password)) {
                    return@addSubmitButton
                }
                confirm("修改校园卡限额", "单笔 ${perTimeYuan} 元，单日 ${perDayYuan} 元，确认保存？") {
                    submit {
                        app.api.modifyCardQuota(
                            openid = requireOpenid(),
                            idserial = data.optString("idserial"),
                            cardId = data.optString("cardid"),
                            maxConsAmtFen = CardOps.yuanToFen(perTimeYuan),
                            maxConsTolAmtFen = CardOps.yuanToFen(perDayYuan),
                            cardPassword = password,
                        )
                    }
                }
            }
        }

        if (haveQrcode) {
            val section = Ui.addSection(body, "校园码限额")
            Ui.addKv(section, "当前单笔限额", quotaText(data.optString("qrcodemaxconsamt")))
            Ui.addKv(section, "当前单日限额", quotaText(data.optString("qrcodemaxconstolamt")))
            val perTime = addAmountField(context, section, "新单笔限额（元）")
            val perDay = addAmountField(context, section, "新单日限额（元）")
            val pwd = addPasswordField(context, section, "校园卡密码（6 位数字）")
            addSubmitButton(context, body, "保存校园码限额") {
                val perTimeYuan = textOf(perTime)
                val perDayYuan = textOf(perDay)
                val password = textOf(pwd)
                if (!validateQuota(perTime, perDay, pwd, perTimeYuan, perDayYuan, password)) {
                    return@addSubmitButton
                }
                confirm("修改校园码限额", "单笔 ${perTimeYuan} 元，单日 ${perDayYuan} 元，确认保存？") {
                    submit {
                        app.api.modifyQrcodeQuota(
                            openid = requireOpenid(),
                            idserial = data.optString("idserial"),
                            qrcodeId = data.optString("qrcodeid"),
                            maxConsAmtFen = CardOps.yuanToFen(perTimeYuan),
                            maxConsTolAmtFen = CardOps.yuanToFen(perDayYuan),
                            cardPassword = password,
                        )
                    }
                }
            }
        }
    }

    private fun quotaText(raw: String): String {
        if (raw.isBlank()) return "—"
        return Json.yuan(raw) + " 元"
    }

    private fun validateQuota(
        perTimeField: TextInputLayout,
        perDayField: TextInputLayout,
        pwdField: TextInputLayout,
        perTime: String,
        perDay: String,
        password: String,
    ): Boolean {
        var ok = true
        val time = perTime.toDoubleOrNull()
        val day = perDay.toDoubleOrNull()
        if (time == null || time <= 0) {
            perTimeField.error = "请输入大于 0 的金额"
            ok = false
        } else if (time > MAX_QUOTA_YUAN) {
            perTimeField.error = "单笔限额不能超过 2,000 元"
            ok = false
        }
        if (day == null || day <= 0) {
            perDayField.error = "请输入大于 0 的金额"
            ok = false
        } else if (day > MAX_QUOTA_YUAN) {
            perDayField.error = "单日限额不能超过 2,000 元"
            ok = false
        }
        if (!CardOps.isCardPassword(password)) {
            pwdField.error = "请输入 6 位数字校园卡密码"
            ok = false
        }
        return ok
    }

    // ---------------- 绑定银行卡 ----------------

    private fun renderBindBank(context: Context, body: LinearLayout, data: JSONObject) {
        val current = data.optString("bankcardno")
        val section = Ui.addSection(body, "已绑定银行卡")
        if (current.isBlank()) {
            section.addView(
                Ui.text(context, "当前未绑定银行卡。", 13f, R.color.text_secondary),
                Ui.rowParams(context, 4f),
            )
        } else {
            Ui.addKv(section, "当前卡号", CardOps.maskBankCardNo(current))
        }
        val form = Ui.addSection(body, "绑定新银行卡")
        val number = addBankCardField(context, form, "银行卡号")
        val confirmNumber = addBankCardField(context, form, "确认银行卡号")
        addSubmitButton(context, body, if (current.isBlank()) "确认绑定" else "确认更换") {
            val no = digitsOf(textOf(number))
            val again = digitsOf(textOf(confirmNumber))
            var ok = true
            if (!CardOps.isBankCardNo(no)) {
                number.error = "请输入有效的 16–19 位银行卡号"
                ok = false
            }
            if (no != again) {
                confirmNumber.error = "两次输入的银行卡号不一致"
                ok = false
            }
            if (!ok) return@addSubmitButton
            confirm("绑定银行卡", "确认将银行卡 ${CardOps.maskBankCardNo(no)} 绑定到校园卡？") {
                submit {
                    app.api.bindBankCard(requireOpenid(), data.optString("idserial"), no)
                }
            }
        }
    }

    // ---------------- 解绑银行卡 ----------------

    private fun renderUnbindBank(context: Context, body: LinearLayout, data: JSONObject) {
        val current = data.optString("bankcardno")
        val section = Ui.addSection(body, "已绑定银行卡")
        if (current.isBlank()) {
            section.addView(
                Ui.text(context, "未查询到绑定的银行卡，无需解绑。", 13f, R.color.text_secondary),
                Ui.rowParams(context, 4f),
            )
            return
        }
        Ui.addKv(section, "银行卡号", CardOps.maskBankCardNo(current))
        val pwd = addPasswordField(context, section, "校园卡密码（6 位数字）")
        addSubmitButton(context, body, "确认解绑", danger = true) {
            val password = textOf(pwd)
            if (!CardOps.isCardPassword(password)) {
                pwd.error = "请输入 6 位数字校园卡密码"
                return@addSubmitButton
            }
            confirm(
                "解绑银行卡",
                "解绑会影响家属卡的充值和使用，确认要解绑该银行卡吗？",
            ) {
                submit {
                    app.api.unbindBankCard(
                        requireOpenid(),
                        data.optString("idserial"),
                        current,
                        password,
                    )
                }
            }
        }
    }

    // ---------------- 提交 ----------------

    private fun submit(block: suspend () -> JSONObject) {
        if (busy) return
        if (app.demoMode) {
            MaterialAlertDialogBuilder(this)
                .setTitle("演示模式")
                .setMessage("当前是离线界面预览，不会提交任何真实卡务操作。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        busy = true
        hideKeyboard()
        val button = lastSubmitButton
        val originalText = lastSubmitText
        button?.apply {
            isEnabled = false
            text = "提交中…"
        }
        lifecycleScope.launch {
            try {
                block()
                if (isFinishing) return@launch
                app.requestHomeRefresh()
                MaterialAlertDialogBuilder(this@CardOpActivity)
                    .setTitle("操作成功")
                    .setMessage("${op.title}已提交成功。")
                    .setPositiveButton("完成") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFinishing) return@launch
                val message = if (e is ApiException) e.message else SelfCheck.describe(e)
                MaterialAlertDialogBuilder(this@CardOpActivity)
                    .setTitle("操作失败")
                    .setMessage(message ?: "未知错误，请稍后重试")
                    .setPositiveButton("知道了", null)
                    .show()
            } finally {
                busy = false
                button?.apply {
                    isEnabled = true
                    text = originalText
                }
            }
        }
    }

    private var lastSubmitButton: MaterialButton? = null
    private var lastSubmitText: String = ""

    /**
     * 添加主操作按钮。按钮文案在提交期间改写成「提交中…」，结束后恢复。
     */
    private fun addSubmitButton(
        context: Context,
        body: LinearLayout,
        text: String,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        val button = Ui.primaryButton(context, text).apply {
            if (danger) {
                backgroundTintList = Ui.tint(Ui.color(context, R.color.red))
            }
            setOnClickListener {
                lastSubmitButton = this
                lastSubmitText = text
                onClick()
            }
        }
        body.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(context, 4f) },
        )
    }

    private fun confirm(title: String, message: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ -> onConfirm() }
            .show()
    }

    // ---------------- 输入控件 ----------------

    private fun addPasswordField(context: Context, parent: LinearLayout, hint: String): TextInputLayout =
        addField(context, parent, hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)

    private fun addAmountField(context: Context, parent: LinearLayout, hint: String): TextInputLayout =
        addField(
            context,
            parent,
            hint,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            maxLength = 9,
        )

    private fun addBankCardField(context: Context, parent: LinearLayout, hint: String): TextInputLayout =
        addField(context, parent, hint, InputType.TYPE_CLASS_NUMBER, maxLength = 23)

    private fun addField(
        context: Context,
        parent: LinearLayout,
        hint: String,
        inputType: Int,
        maxLength: Int = 6,
    ): TextInputLayout {
        val field = FormFieldBinding.inflate(layoutInflater)
        field.root.hint = hint
        val edit = field.input
        edit.inputType = inputType
        edit.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        edit.typeface = ResourcesCompat.getFont(context, R.font.noto_sans_sc_regular)
        edit.setTextSize(15f)
        parent.addView(
            field.root,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return field.root
    }

    private fun textOf(field: TextInputLayout): String =
        field.editText?.text?.toString()?.trim().orEmpty()

    private fun digitsOf(value: String): String = value.replace(" ", "").trim()

    private fun requireOpenid(): String {
        val openid = app.openid()
        if (openid.isEmpty()) throw ApiException("登录状态异常，请重新登录", authExpired = true)
        return openid
    }

    // ---------------- 状态页 ----------------

    private fun showLoading() {
        binding.scroll.visibility = View.GONE
        binding.errorBox.visibility = View.GONE
        binding.loadingBox.visibility = View.VISIBLE
        binding.tvLoading.text = "正在读取卡片信息…"
    }

    private fun showError(message: String) {
        binding.loadingBox.visibility = View.GONE
        binding.scroll.visibility = View.GONE
        binding.errorBox.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun addEmpty(message: String) {
        val card = Ui.addCard(binding.body)
        card.addView(
            Ui.text(this, message, 13f, R.color.text_hint),
            Ui.rowParams(this, 4f),
        )
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(focus.windowToken, 0)
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
            binding.scroll.setPadding(
                binding.scroll.paddingLeft,
                binding.scroll.paddingTop,
                binding.scroll.paddingRight,
                maxOf(bars.bottom, ime.bottom),
            )
            view.setPadding(0, 0, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    companion object {
        const val EXTRA_OP = "op"
        private const val MAX_QUOTA_YUAN = 2_000.0

        fun start(context: Context, op: CardOp) {
            context.startActivity(
                Intent(context, CardOpActivity::class.java).putExtra(EXTRA_OP, op.name),
            )
        }
    }
}
