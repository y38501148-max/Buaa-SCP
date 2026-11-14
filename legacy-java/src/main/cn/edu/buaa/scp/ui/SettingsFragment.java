package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import cn.edu.buaa.scp.BuildConfig;
import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.store.SessionData;
import cn.edu.buaa.scp.util.Json;

/** 设置页：账号、会话状态、网络自检、运行日志、退出登录、关于。 */
public class SettingsFragment extends ScpFragment {

    @Override
    protected void load() {
        app.ui(() -> {
            if (!isAlive()) {
                return;
            }
            render();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isAdded() && body != null) {
            refresh();
        }
    }

    private void render() {
        clearBody();
        Context context = requireContext();
        SessionData session = app.session();

        LinearLayout account = Ui.addSection(body, "账号");
        Ui.addKv(account, "学号 / 工号", session == null ? "—" : Json.or(session.studentId, "—"));
        Ui.addKv(account, "openid", session == null ? "—" : Json.or(Json.mask(session.openid), "—"));

        LinearLayout sessionCard = Ui.addSection(body, "会话状态");
        Ui.addKv(sessionCard, "业务会话 JSESSIONID",
                session != null && session.hasJsessionid()
                        ? "已保存（" + session.jsessionid.length() + " 字符）" : "无",
                session != null && session.hasJsessionid() ? R.color.green : R.color.orange);
        Ui.addKv(sessionCard, "统一认证 CASTGC",
                session != null && session.hasCastgc()
                        ? "已保存（" + session.castgc.length() + " 字符）" : "无",
                session != null && session.hasCastgc() ? R.color.green : R.color.orange);
        Ui.addKv(sessionCard, "登录时间", session == null ? "—"
                : Json.or(Json.time(session.savedAt), "—"));
        Ui.addKv(sessionCard, "最近刷新", session == null ? "—"
                : Json.or(Json.time(session.lastSyncAt), "—"));
        sessionCard.addView(Ui.text(context,
                "CASTGC 可在有效期内免密换新会话；失效后需要重新输入密码。",
                12f, R.color.text_hint, false), Ui.rowParams(context, 8));

        LinearLayout actions = Ui.addSection(body, "工具");
        addAction(actions, "网络自检（不需要账号）", this::runSelfCheck);
        addAction(actions, "手动续期会话", this::refreshSession);
        addAction(actions, "查看运行日志", this::openLog);
        addAction(actions, "退出登录", this::logout);

        LinearLayout about = Ui.addSection(body, "关于");
        Ui.addKv(about, "版本", BuildConfig.VERSION_NAME);
        Ui.addKv(about, "数据来源", "m.icw.buaa.edu.cn");
        about.addView(Ui.text(context,
                "非官方客户端，仅用于本人账号的只读查询。密码只用于统一身份认证表单提交，不会保存；"
                        + "会话凭据经 Android Keystore 加密后保存在本机。本版本不含充值、支付、挂失等写操作。",
                12f, R.color.text_hint, false), Ui.rowParams(context, 10));
    }

    private void addAction(LinearLayout parent, String text, Runnable action) {
        Context context = parent.getContext();
        MaterialButton button = new MaterialButton(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(context, R.color.buaa_blue_light)));
        button.setTextColor(Ui.color(context, R.color.buaa_blue));
        button.setOnClickListener(v -> action.run());
        parent.addView(button, Ui.rowParams(context, 6));
    }

    private void runSelfCheck() {
        Context context = requireContext();
        Toast.makeText(context, "正在自检…", Toast.LENGTH_SHORT).show();
        app.async(() -> {
            List<SelfCheck.Item> items = SelfCheck.run();
            StringBuilder message = new StringBuilder();
            boolean allOk = true;
            for (SelfCheck.Item item : items) {
                message.append(item.line()).append("\n\n");
                allOk &= item.ok;
            }
            final boolean ok = allOk;
            final String content = message.toString().trim();
            app.ui(() -> {
                if (!isAdded()) {
                    return;
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(ok ? "网络自检通过" : "网络自检有问题")
                        .setMessage(content)
                        .setPositiveButton("知道了", null)
                        .show();
            });
        });
    }

    private void refreshSession() {
        Context context = requireContext();
        Toast.makeText(context, "正在刷新会话…", Toast.LENGTH_SHORT).show();
        app.async(() -> {
            boolean ok = app.recoverSession();
            app.ui(() -> {
                if (!isAdded()) {
                    return;
                }
                Toast.makeText(requireContext(),
                        ok ? "会话已更新" : "续期失败，请退出后重新登录",
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        });
    }

    private void openLog() {
        startActivity(new Intent(requireContext(), LogActivity.class));
    }

    private void logout() {
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).confirmLogout();
        }
    }
}
