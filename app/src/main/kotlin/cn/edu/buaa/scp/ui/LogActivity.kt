package cn.edu.buaa.scp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.buaa.scp.databinding.ActivityLogBinding
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 运行日志：排查登录与接口问题时使用。不记录密码与 Cookie 原文。 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var pollJob: Job? = null
    private var lastSize = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnClear.setOnClickListener {
            LogBus.clear()
            lastSize = -1
            refresh()
        }
        binding.btnCopy.setOnClickListener { copyAll() }
    }

    override fun onStart() {
        super.onStart()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(800)
            }
        }
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        super.onStop()
    }

    private fun refresh() {
        val size = LogBus.size()
        if (size == lastSize) return
        lastSize = size
        binding.tvLog.text = LogBus.dump()
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun copyAll() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = LogBus.dump()
        manager.setPrimaryClip(ClipData.newPlainText("scp-log", text))
        Toast.makeText(this, "日志已复制（${text.length} 字符）", Toast.LENGTH_SHORT).show()
    }
}
