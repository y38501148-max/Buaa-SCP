package cn.edu.buaa.scp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.ScpApp;
import cn.edu.buaa.scp.net.CasAuth;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.store.SessionData;
import cn.edu.buaa.scp.util.LogBus;

/**
 * 登录页：
 * <ul>
 *   <li>启动时优先用本地会话免密恢复（JSESSIONID / CASTGC）；</li>
 *   <li>失败后提供学号 + 统一身份认证密码登录；</li>
 *   <li>「网络自检」不需要账号即可验证设备到学校服务的连通性。</li>
 * </ul>
 */
public class LoginActivity extends AppCompatActivity {

    private ScpApp app;

    private TextInputLayout layoutStudent;
    private TextInputLayout layoutPassword;
    private TextInputEditText inputStudent;
    private TextInputEditText inputPassword;
    private CheckBox checkRemember;
    private MaterialButton btnLogin;
    private MaterialButton btnSelfCheck;
    private MaterialButton btnLog;
    private LinearProgressIndicator progress;
    private TextView tvStatus;

    private volatile boolean busy;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        app = ScpApp.get(this);

        layoutStudent = findViewById(R.id.layoutStudent);
        layoutPassword = findViewById(R.id.layoutPassword);
        inputStudent = findViewById(R.id.inputStudent);
        inputPassword = findViewById(R.id.inputPassword);
        checkRemember = findViewById(R.id.checkRemember);
        btnLogin = findViewById(R.id.btnLogin);
        btnSelfCheck = findViewById(R.id.btnSelfCheck);
        btnLog = findViewById(R.id.btnLog);
        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tvStatus);

        String remembered = app.store().getRememberedStudentId();
        if (!remembered.isEmpty()) {
            inputStudent.setText(remembered);
            inputStudent.setSelection(remembered.length());
        }

        btnLogin.setOnClickListener(v -> startPasswordLogin());
        btnSelfCheck.setOnClickListener(v -> runSelfCheck());
        btnLog.setOnClickListener(v -> openLog());
        inputPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                startPasswordLogin();
                return true;
            }
            return false;
        });

        bootstrap();
    }

    /** 启动时尝试免密恢复本地会话。 */
    private void bootstrap() {
        SessionData saved = app.session();
        if (saved == null || !saved.hasAnything()) {
            setStatus("请输入统一身份认证账号密码进行首次登录。");
            return;
        }
        setBusy(true);
        setStatus("检测到本地会话，正在尝试免密恢复…");
        app.async(() -> {
            boolean restored = app.recoverSession();
            if (restored) {
                app.markSynced();
                app.ui(() -> goMain("会话已恢复"));
                return;
            }
            app.ui(() -> {
                setBusy(false);
                setStatus("本地会话已失效，请重新输入密码登录。");
                inputPassword.requestFocus();
            });
        });
    }

    private void startPasswordLogin() {
        if (busy) {
            return;
        }
        final String studentId = text(inputStudent);
        final String password = text(inputPassword);

        layoutStudent.setError(null);
        layoutPassword.setError(null);
        if (studentId.isEmpty()) {
            layoutStudent.setError("请输入学号或工号");
            return;
        }
        if (password.isEmpty()) {
            layoutPassword.setError("请输入统一身份认证密码");
            return;
        }

        hideKeyboard();
        setBusy(true);
        setStatus("正在登录：① 获取 CAS 登录页…");
        app.async(() -> {
            try {
                CasAuth.Result result = app.cas().login(studentId, password);
                if (!result.ok) {
                    app.ui(() -> {
                        setBusy(false);
                        setStatus("登录失败：" + result.message);
                    });
                    return;
                }
                app.ui(() -> setStatus("⑤ 校验校园卡业务会话…"));
                if (!app.validateSession()) {
                    app.ui(() -> {
                        setBusy(false);
                        setStatus("已取得票据但业务会话不可用。请点「运行日志」查看细节，或稍后重试。");
                    });
                    return;
                }
                app.rememberSession(studentId, result.openid);
                app.store().setRememberedStudentId(checkRemember.isChecked() ? studentId : "");
                app.markSynced();
                app.ui(() -> goMain("登录成功"));
            } catch (Exception e) {
                LogBus.error("登录异常：" + SelfCheck.describe(e));
                app.ui(() -> {
                    setBusy(false);
                    setStatus("登录异常：" + SelfCheck.describe(e));
                });
            }
        });
    }

    /** 网络自检：不依赖账号，验证设备到学校服务的链路。 */
    private void runSelfCheck() {
        if (busy) {
            return;
        }
        btnSelfCheck.setEnabled(false);
        setStatus("正在执行网络自检…");
        app.async(() -> {
            List<SelfCheck.Item> items;
            try {
                items = SelfCheck.run();
            } catch (Exception e) {
                app.ui(() -> {
                    btnSelfCheck.setEnabled(true);
                    setStatus("自检异常：" + SelfCheck.describe(e));
                });
                return;
            }
            StringBuilder message = new StringBuilder();
            boolean allOk = true;
            for (SelfCheck.Item item : items) {
                message.append(item.line()).append("\n\n");
                allOk &= item.ok;
            }
            final boolean ok = allOk;
            final String content = message.toString().trim();
            app.ui(() -> {
                btnSelfCheck.setEnabled(true);
                setStatus(ok ? "网络自检全部通过，可以登录。" : "自检存在失败项，请查看详情或日志。");
                new MaterialAlertDialogBuilder(this)
                        .setTitle(ok ? "网络自检通过" : "网络自检有问题")
                        .setMessage(content.isEmpty() ? "没有返回结果" : content)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        });
    }

    private void goMain(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void openLog() {
        startActivity(new Intent(this, LogActivity.class));
    }

    private void setBusy(boolean value) {
        busy = value;
        runOnUiThread(() -> {
            btnLogin.setEnabled(!value);
            btnLogin.setText(value ? "正在登录…" : getString(R.string.login_button));
            progress.setVisibility(value ? View.VISIBLE : View.GONE);
            inputStudent.setEnabled(!value);
            inputPassword.setEnabled(!value);
            checkRemember.setEnabled(!value);
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> tvStatus.setText(text));
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        }
    }

    private static String text(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
