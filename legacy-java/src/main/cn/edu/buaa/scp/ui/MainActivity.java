package cn.edu.buaa.scp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.ScpApp;

/** 主界面：底部五个标签页（首页 / 账单 / 流水 / 卡务 / 设置）。 */
public class MainActivity extends AppCompatActivity {

    private static final String[] TAGS = {"home", "bills", "trades", "card", "settings"};
    private static final int[] TAB_IDS = {
            R.id.nav_home, R.id.nav_bills, R.id.nav_trades, R.id.nav_card, R.id.nav_settings
    };

    private ScpApp app;
    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNav;
    private int currentIndex = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = ScpApp.get(this);

        toolbar = findViewById(R.id.toolbar);
        bottomNav = findViewById(R.id.bottomNav);

        toolbar.setOnMenuItemClickListener(this::onMenuClick);
        bottomNav.setOnItemSelectedListener(this::onTabSelected);

        int start = savedInstanceState == null ? 0
                : savedInstanceState.getInt("tab", 0);
        selectTab(Math.max(0, Math.min(TAB_IDS.length - 1, start)), false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!app.hasSession()) {
            goLogin();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", Math.max(0, currentIndex));
    }

    @Override
    public void onBackPressed() {
        if (currentIndex != 0) {
            selectTab(0, true);
            return;
        }
        super.onBackPressed();
    }

    private boolean onTabSelected(@NonNull MenuItem item) {
        int index = indexOf(item.getItemId());
        if (index < 0 || index == currentIndex) {
            return true;
        }
        selectTab(index, false);
        return true;
    }

    private boolean onMenuClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_log) {
            startActivity(new Intent(this, LogActivity.class));
            return true;
        }
        if (id == R.id.action_logout) {
            confirmLogout();
            return true;
        }
        return false;
    }

    /** 供各 Fragment 内部跳转标签页（如首页的快捷入口）。 */
    public void switchTab(int tabId) {
        bottomNav.setSelectedItemId(tabId);
    }

    private void selectTab(int index, boolean syncBottomNav) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (int i = 0; i < TAGS.length; i++) {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(TAGS[i]);
            if (existing != null && i != index) {
                transaction.hide(existing);
            }
        }
        Fragment target = getSupportFragmentManager().findFragmentByTag(TAGS[index]);
        if (target == null) {
            target = createFragment(index);
            transaction.add(R.id.container, target, TAGS[index]);
        } else {
            transaction.show(target);
        }
        transaction.commitAllowingStateLoss();
        currentIndex = index;
        toolbar.setTitle(titleOf(index));
        if (syncBottomNav) {
            bottomNav.getMenu().findItem(TAB_IDS[index]).setChecked(true);
        }
    }

    private static Fragment createFragment(int index) {
        switch (index) {
            case 0:
                return new HomeFragment();
            case 1:
                return new BillsFragment();
            case 2:
                return new TradesFragment();
            case 3:
                return new CardFragment();
            default:
                return new SettingsFragment();
        }
    }

    private static String titleOf(int index) {
        switch (index) {
            case 0:
                return "北航校园卡";
            case 1:
                return "账单";
            case 2:
                return "消费流水";
            case 3:
                return "卡务";
            default:
                return "设置";
        }
    }

    private static int indexOf(int tabId) {
        for (int i = 0; i < TAB_IDS.length; i++) {
            if (TAB_IDS[i] == tabId) {
                return i;
            }
        }
        return -1;
    }

    public void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("退出登录")
                .setMessage("将清除本机保存的登录凭据，需要重新输入账号密码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (dialog, which) -> {
                    app.logout();
                    goLogin();
                })
                .show();
    }

    private void goLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
