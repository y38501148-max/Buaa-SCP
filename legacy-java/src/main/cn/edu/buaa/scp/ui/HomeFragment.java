package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.store.SessionData;
import cn.edu.buaa.scp.util.Json;

/** 首页：余额、卡状态、账户信息、通知、快捷入口。 */
public class HomeFragment extends ScpFragment {

    @Override
    protected void load() {
        final String openid = app.openid();

        JSONObject home = null;
        String homeError = null;
        try {
            home = app.callWithRecovery(() -> app.api().openHomePageApp(openid));
        } catch (Exception e) {
            homeError = SelfCheck.describe(e);
        }

        JSONObject pay = null;
        String payError = null;
        try {
            pay = app.callWithRecovery(app.api()::openCardPay);
        } catch (Exception e) {
            payError = SelfCheck.describe(e);
        }

        JSONObject loss = null;
        try {
            loss = app.api().reportLossInfo();
        } catch (Exception ignored) {
            // 卡务信息属于锦上添花，失败不影响首页
        }

        if (pay != null) {
            app.markSynced();
        }

        final JSONObject fHome = home;
        final JSONObject fPay = pay;
        final JSONObject fLoss = loss;
        final String fHomeError = homeError;
        final String fPayError = payError;
        app.ui(() -> {
            if (!isAlive()) {
                return;
            }
            render(fHome, fHomeError, fPay, fPayError, fLoss);
        });
    }

    private void render(JSONObject home, String homeError, JSONObject pay, String payError,
                        JSONObject loss) {
        clearBody();
        Context context = requireContext();

        String balance = Json.str(home, "data", "cardinfo", "cardbal");
        if (balance.isEmpty()) {
            balance = Json.str(pay, "data", "cardPay", "cardbal");
        }
        String status = Json.str(home, "data", "cardinfo", "accstatusStr");
        if (status.isEmpty()) {
            status = Json.str(loss, "data", "accstatusStr");
        }
        String idserial = Json.str(home, "data", "cardinfo", "idserial");
        if (idserial.isEmpty()) {
            idserial = Json.str(pay, "data", "cardPay", "idserial");
        }
        if (idserial.isEmpty()) {
            idserial = Json.str(loss, "data", "idserial");
        }
        String name = Json.str(loss, "data", "username");
        String pcode = Json.str(home, "data", "pcodeCard");
        String cardType = Json.cardType(pcode);

        addBalanceCard(context, balance, status, idserial);
        addQuickActions(context);

        LinearLayout info = Ui.addSection(body, "账户信息");
        Ui.addKv(info, "姓名", name);
        Ui.addKv(info, "学号 / 工号", studentId());
        Ui.addKv(info, "卡号", idserial);
        Ui.addKv(info, "卡状态", Json.or(status, "未获取"));
        Ui.addKv(info, "卡类型", cardType);

        addNoticeSection(home);
        addPaymentChannelSection(pay);

        if (home == null && pay == null) {
            addErrorCard(body, "余额加载失败：" + Json.or(payError, homeError), this::refresh);
        } else if (homeError != null) {
            LinearLayout hint = Ui.addSection(body, "提示");
            hint.addView(Ui.text(context,
                    "首页聚合数据暂不可用（" + homeError + "），余额已通过备用接口获取。",
                    12f, R.color.text_hint, false), Ui.rowParams(context, 4));
        }
    }

    private void addBalanceCard(Context context, String balance, String status, String idserial) {
        LinearLayout card = Ui.column(context);
        card.setBackgroundResource(R.drawable.bg_balance_card);
        int pad = Ui.dp(context, 20);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(context, 12);
        body.addView(card, params);

        TextView label = Ui.text(context, "校园卡余额", 13f, R.color.white, false);
        label.setTextColor(0xCCFFFFFF);
        card.addView(label);

        TextView amount = Ui.text(context,
                balance.isEmpty() ? "¥ --" : "¥ " + Json.yuan(balance),
                36f, R.color.white, true);
        amount.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(amount, Ui.rowParams(context, 2));

        LinearLayout statusRow = Ui.row(context);
        TextView dot = Ui.statusDot(context, isNormal(status));
        statusRow.addView(dot);
        TextView statusText = Ui.text(context,
                "状态：" + Json.or(status, "未知"), 13f, R.color.white, false);
        statusText.setTextColor(0xE6FFFFFF);
        statusRow.addView(statusText);
        card.addView(statusRow, Ui.rowParams(context, 10));

        TextView cardNo = Ui.text(context,
                "卡号 " + Json.or(idserial, "—"), 12f, R.color.white, false);
        cardNo.setTextColor(0xB3FFFFFF);
        card.addView(cardNo, Ui.rowParams(context, 4));

        SessionData session = app.session();
        long lastSync = session == null ? 0L : session.lastSyncAt;
        if (lastSync > 0) {
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date(lastSync));
            TextView updated = Ui.text(context, "更新于 " + time, 11f, R.color.white, false);
            updated.setTextColor(0x99FFFFFF);
            card.addView(updated, Ui.rowParams(context, 6));
        }
    }

    private void addQuickActions(Context context) {
        LinearLayout card = Ui.addCard(body);
        card.addView(Ui.text(context, "快捷入口", 15f, R.color.text_primary, true));
        LinearLayout row = Ui.row(context);
        String[] titles = {"账单", "流水", "卡务"};
        int[] tabs = {R.id.nav_bills, R.id.nav_trades, R.id.nav_card};
        for (int i = 0; i < titles.length; i++) {
            final int tab = tabs[i];
            com.google.android.material.button.MaterialButton button =
                    new com.google.android.material.button.MaterialButton(context);
            button.setText(titles[i]);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    Ui.color(context, R.color.buaa_blue_light)));
            button.setTextColor(Ui.color(context, R.color.buaa_blue));
            button.setOnClickListener(v -> {
                if (requireActivity() instanceof MainActivity) {
                    ((MainActivity) requireActivity()).switchTab(tab);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.topMargin = Ui.dp(context, 10);
            if (i > 0) {
                params.leftMargin = Ui.dp(context, 8);
            }
            row.addView(button, params);
        }
        card.addView(row);
    }

    private void addNoticeSection(JSONObject home) {
        JSONArray notices = Json.arr(home, "data", "messagenotice");
        if (notices == null || notices.length() == 0) {
            return;
        }
        Context context = requireContext();
        LinearLayout card = Ui.addSection(body, "消息通知");
        int shown = 0;
        for (int i = 0; i < notices.length() && shown < 5; i++) {
            JSONObject item = notices.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String text = Json.or(Json.str(item, "title"),
                    Json.or(Json.str(item, "content"),
                            Json.or(Json.str(item, "name"), Json.str(item, "message"))));
            if (text.isEmpty()) {
                continue;
            }
            Ui.addKv(card, "·", text);
            shown++;
        }
        if (shown == 0) {
            card.addView(Ui.text(context, "有新的通知，但格式暂不支持展示。", 12f,
                    R.color.text_hint, false), Ui.rowParams(context, 4));
        }
    }

    private void addPaymentChannelSection(JSONObject pay) {
        JSONObject jsonmsg = Json.obj(pay, "data", "jsonmsg");
        if (jsonmsg == null) {
            return;
        }
        Context context = requireContext();
        LinearLayout card = Ui.addSection(body, "支付渠道状态（只读）");
        String[][] fields = {
                {"微信支付", "wechat"},
                {"支付宝", "alipay"},
                {"建设银行", "abc"},
        };
        for (String[] field : fields) {
            String value = Json.str(jsonmsg, field[1]);
            Ui.addKv(card, field[0], Json.or(value, "未开放"));
        }
        card.addView(Ui.text(context, "本版本不含充值/支付，此处仅展示官方返回的渠道状态。",
                12f, R.color.text_hint, false), Ui.rowParams(context, 8));
    }

    private String studentId() {
        SessionData session = app.session();
        return session == null ? "" : session.studentId;
    }

    private static boolean isNormal(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }
        return status.contains("正常") || status.toLowerCase(Locale.US).contains("normal");
    }

}
