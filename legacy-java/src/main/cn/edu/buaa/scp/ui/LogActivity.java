package cn.edu.buaa.scp.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.util.LogBus;

/** 运行日志：排查登录与接口问题时使用。不记录密码与 Cookie 原文。 */
public class LogActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvLog;
    private ScrollView scroll;
    private int lastSize = -1;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 800);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        scroll = findViewById(R.id.scroll);
        tvLog = findViewById(R.id.tvLog);

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            LogBus.clear();
            refresh();
        });
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyAll());

        refresh();
        handler.postDelayed(poller, 800);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(poller);
    }

    private void refresh() {
        int size = LogBus.size();
        if (size == lastSize) {
            return;
        }
        lastSize = size;
        tvLog.setText(LogBus.dump());
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void copyAll() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        String text = LogBus.dump();
        manager.setPrimaryClip(ClipData.newPlainText("scp-log", text));
        Toast.makeText(this, "日志已复制（" + text.length() + " 字符）", Toast.LENGTH_SHORT).show();
    }
}
