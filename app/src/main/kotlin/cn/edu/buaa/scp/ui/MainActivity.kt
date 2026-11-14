package cn.edu.buaa.scp.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 主界面：底部四个标签页（首页 / 账单 / 财务 / 设置）。卡务功能已合并进首页「其他」。 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: ScpApp
    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = ScpApp.get(this)

        applyEdgeToEdgeInsets()
        binding.toolbar.setOnMenuItemClickListener { item -> onMenuClick(item) }
        binding.bottomNav.setOnItemSelectedListener { item ->
            val index = TAB_IDS.indexOf(item.itemId)
            if (index >= 0 && index != currentIndex) {
                selectTab(index, syncBottomNav = false)
            }
            true
        }

        val start = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0
        selectTab(start.coerceIn(0, TAB_IDS.lastIndex), syncBottomNav = true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentIndex != 0) {
                    selectTab(0, syncBottomNav = true)
                } else {
                    finish()
                }
            }
        })
    }

    /**
     * Android 15（API 35）起对 targetSdk 35 的应用强制 edge-to-edge：
     * 顶栏会画到状态栏下面，必须自己消费系统栏 insets，否则标题被状态栏压住。
     */
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
            binding.bottomNav.setPadding(
                binding.bottomNav.paddingLeft, binding.bottomNav.paddingTop,
                binding.bottomNav.paddingRight, bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onResume() {
        super.onResume()
        if (!app.hasSession()) {
            goLogin()
            return
        }
        if (app.consumeHomeRequest()) selectTab(0, syncBottomNav = true)
        UpdateUi.autoCheck(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentIndex.coerceAtLeast(0))
    }

    private fun onMenuClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_logout -> {
            confirmLogout()
            true
        }

        else -> false
    }

    /** 供各 Fragment 内部跳转标签页（如首页的快捷入口）。 */
    fun switchTab(tabId: Int) {
        binding.bottomNav.selectedItemId = tabId
    }

    private fun selectTab(index: Int, syncBottomNav: Boolean) {
        val manager = supportFragmentManager
        val transaction: FragmentTransaction = manager.beginTransaction()
        TAGS.forEachIndexed { i, tag ->
            manager.findFragmentByTag(tag)?.let { fragment ->
                if (i != index) transaction.hide(fragment)
            }
        }
        val existing = manager.findFragmentByTag(TAGS[index])
        if (existing == null) {
            transaction.add(R.id.container, createFragment(index), TAGS[index])
        } else {
            transaction.show(existing)
        }
        transaction.commitAllowingStateLoss()
        currentIndex = index
        binding.toolbar.setTitle(titleOf(index))
        if (syncBottomNav) {
            binding.bottomNav.menu.findItem(TAB_IDS[index]).isChecked = true
        }
    }

    fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("退出登录")
            .setMessage("清除本机登录凭据后需重新登录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                app.logout()
                goLogin()
            }
            .show()
    }

    private fun goLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    companion object {

        private const val KEY_TAB = "tab"
        private val TAGS = arrayOf("home", "bills", "finance", "settings")
        private val TAB_IDS = intArrayOf(
            R.id.nav_home, R.id.nav_bills, R.id.nav_finance, R.id.nav_settings,
        )

        private fun createFragment(index: Int): Fragment = when (index) {
            0 -> HomeFragment()
            1 -> BillsFragment()
            2 -> FinanceFragment()
            else -> SettingsFragment()
        }

        private fun titleOf(index: Int): String = when (index) {
            0 -> "通财航"
            1 -> "账单"
            2 -> "财务"
            else -> "设置"
        }
    }
}
