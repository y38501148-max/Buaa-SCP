package cn.edu.buaa.scp.ui

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.net.ScanOutcome
import cn.edu.buaa.scp.net.PaymentCode
import cn.edu.buaa.scp.model.HomeModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页独立取码，不等待余额、网费等慢接口。离开首页立即清码并停止轮询。 */
class PaymentCodePanel(
    private val fragment: Fragment,
    private val app: ScpApp,
    private val onPayment: () -> Unit,
) {
    private val context = fragment.requireContext()
    val view = MaterialCardView(context).apply {
        radius = Ui.dp(context, 20f).toFloat()
        cardElevation = 0f
        strokeWidth = Ui.dp(context, 1f)
        strokeColor = Ui.color(context, R.color.divider)
        setCardBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 14f) }
    }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val loading = CircularProgressIndicator(context)
    private val message = Ui.text(context, "", 14f, R.color.text_secondary).apply {
        gravity = Gravity.CENTER
        setPadding(Ui.dp(context, 16f), 0, Ui.dp(context, 16f), 0)
    }
    private val status = Ui.text(context, "", 12f, R.color.text_secondary)
    private val balance = Ui.text(context, "—", 24f, R.color.brand_navy, bold = true)
    private val cardStatus = Ui.text(context, "", 12f, R.color.text_secondary)
    private val codeArea = FrameLayout(context).apply {
        foreground = Ui.selectableForeground(context)
        contentDescription = "支付二维码，点击刷新"
        isFocusable = true
        setOnClickListener { refresh() }
    }
    private var job: Job? = null
    private var expiryJob: Job? = null
    private var active = false
    private var fetching = false
    private var generation = 0

    init {
        val column = Ui.column(context).apply {
            setPadding(Ui.dp(context, 16f), Ui.dp(context, 10f), Ui.dp(context, 16f), Ui.dp(context, 8f))
        }
        column.addView(Ui.text(context, "支付二维码", 16f, R.color.text_primary, bold = true).apply {
            gravity = Gravity.CENTER
            minimumHeight = Ui.dp(context, 40f)
        }, LinearLayout.LayoutParams(-1, -2))
        // 自适应窄屏，限制高度以便首屏还能看到校园卡。
        codeArea.addView(image, FrameLayout.LayoutParams(-1, -1))
        codeArea.addView(message, FrameLayout.LayoutParams(-1, -1))
        codeArea.addView(loading, FrameLayout.LayoutParams(Ui.dp(context, 36f), Ui.dp(context, 36f), Gravity.CENTER))
        column.addView(codeArea, LinearLayout.LayoutParams(-1, Ui.dp(context, 204f)))
        status.gravity = Gravity.CENTER
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(context, 8f); bottomMargin = Ui.dp(context, 6f)
        })
        Ui.divider(column)
        val footer = Ui.row(context)
        val label = Ui.column(context)
        label.addView(Ui.text(context, "校园卡余额", 12f, R.color.text_secondary))
        label.addView(cardStatus, Ui.rowParams(context, 3f))
        footer.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(balance)
        column.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 8f) })
        view.addView(column)
    }

    fun updateBalance(model: HomeModel) {
        balance.text = model.balance.takeIf { it.isNotBlank() }?.let { "¥$it" } ?: "—"
        cardStatus.text = model.status
        cardStatus.setTextColor(Ui.color(context, if (model.statusNormal) R.color.green else R.color.orange))
        cardStatus.visibility = if (model.status.isBlank()) View.GONE else View.VISIBLE
    }

    fun start() {
        if (active) return
        active = true
        refresh()
    }

    fun stop() {
        active = false
        generation++
        job?.cancel()
        job = null
        expiryJob?.cancel()
        expiryJob = null
        fetching = false
        codeArea.isEnabled = false
        clearCode()
        loading.visibility = View.GONE
        status.text = ""
    }

    private fun clearCode() {
        image.setImageDrawable(null)
    }

    fun refresh() {
        if (!active || fetching) return
        val run = ++generation
        job?.cancel()
        expiryJob?.cancel()
        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                fetching = true
                codeArea.isEnabled = false
                clearCode()
                message.text = ""
                loading.visibility = View.VISIBLE
                status.text = "正在刷新…"
                val requestedAt = SystemClock.elapsedRealtime()
                try {
                    val code = if (app.demoMode) PaymentCode("BUAA-SCP-DEMO:${SystemClock.elapsedRealtime()}") else {
                        app.withRecovery { app.paymentApi.paymentCode(app.openid()) }
                    }
                    val bitmap = withContext(Dispatchers.Default) {
                        val hints = mutableMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 4)
                        if (!code.binary) hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
                        BarcodeEncoder().encodeBitmap(code.qrContent(), BarcodeFormat.QR_CODE, 640, 640, hints)
                    }
                    if (code.balance.isNotBlank()) balance.text = "¥${code.balance}"
                    // 网络耗时计入刷新周期；慢响应不能重新获得完整的展示时间。
                    val expiresAt = requestedAt + PaymentCode.REFRESH_MS
                    if (SystemClock.elapsedRealtime() >= expiresAt) {
                        throw IllegalStateException("取码超时，请刷新")
                    }
                    image.setImageBitmap(bitmap)
                    expiryJob?.cancel()
                    expiryJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                        delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                        if (run == generation) {
                            clearCode()
                            status.text = "正在刷新…"
                        }
                    }
                    loading.visibility = View.GONE
                    fetching = false
                    codeArea.isEnabled = true
                    var nextPoll = SystemClock.elapsedRealtime() + 3_000L
                    while (isActive && SystemClock.elapsedRealtime() < expiresAt) {
                        val left = ((expiresAt - SystemClock.elapsedRealtime() + 999) / 1_000).coerceAtLeast(1)
                        status.text = if (app.demoMode) "示例 · ${left}秒后刷新" else "${left}秒后刷新"
                        if (!app.demoMode && SystemClock.elapsedRealtime() >= nextPoll) {
                            // 轮询独立超时不能延长二维码展示窗口。
                            val remaining = expiresAt - SystemClock.elapsedRealtime()
                            val order = kotlinx.coroutines.withTimeoutOrNull(remaining.coerceAtLeast(1)) {
                                try { app.paymentApi.orderStatus(app.openid(), code.value) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) { null }
                            }
                            nextPoll = SystemClock.elapsedRealtime() + 3_000L
                            val outcome = order?.let { ScanOutcome.order(it, app.openid()) }
                            if (outcome != null) {
                                expiryJob?.cancel()
                                clearCode()
                                status.text = ""
                                message.text = outcome.message
                                if (outcome.followUp != null) {
                                    // 先停止旧码循环，再把同一响应交给官方页面，避免重复打开或刷新付款码。
                                    active = false
                                    app.requestHomeRefresh()
                                    fragment.startActivity(Intent(context, ScanActivity::class.java)
                                        .putExtra(ScanActivity.EXTRA_FOLLOW_UP, outcome.followUp))
                                } else {
                                    onPayment()
                                }
                                return@launch
                            }
                        }
                        delay(200)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    expiryJob?.cancel()
                    clearCode()
                    loading.visibility = View.GONE
                    message.text = e.message ?: "支付码加载失败"
                    status.text = ""
                    // 失败后由用户手动刷新，避免持续请求失效会话。
                    return@launch
                } finally {
                    if (run == generation) {
                        fetching = false
                        codeArea.isEnabled = active
                    }
                }
            }
        }
    }
}
