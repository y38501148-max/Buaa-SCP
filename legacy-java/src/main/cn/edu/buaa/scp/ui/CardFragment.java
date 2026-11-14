package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.widget.LinearLayout;

import org.json.JSONObject;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.store.SessionData;
import cn.edu.buaa.scp.util.Json;

/**
 * 卡务页（只读）：卡片档案、卡状态、余额、支付渠道状态。
 *
 * <p>本版本不提供挂失提交、补卡、解除挂失等写操作。
 */
public class CardFragment extends ScpFragment {

    @Override
    protected void load() {
        JSONObject loss = null;
        String lossError = null;
        try {
            loss = app.callWithRecovery(app.api()::reportLossInfo);
        } catch (Exception e) {
            lossError = SelfCheck.describe(e);
        }

        JSONObject pay = null;
        String payError = null;
        try {
            pay = app.callWithRecovery(app.api()::openCardPay);
        } catch (Exception e) {
            payError = SelfCheck.describe(e);
        }

        JSONObject home = null;
        try {
            home = app.api().openHomePageApp(app.openid());
        } catch (Exception ignored) {
            // 卡类型属于补充信息
        }

        if (pay != null) {
            app.markSynced();
        }

        final JSONObject fLoss = loss;
        final JSONObject fPay = pay;
        final JSONObject fHome = home;
        final String fLossError = lossError;
        final String fPayError = payError;
        app.ui(() -> {
            if (!isAlive()) {
                return;
            }
            render(fLoss, fLossError, fPay, fPayError, fHome);
        });
    }

    private void render(JSONObject loss, String lossError, JSONObject pay, String payError,
                        JSONObject home) {
        clearBody();
        Context context = requireContext();

        String idserial = Json.str(loss, "data", "idserial");
        if (idserial.isEmpty()) {
            idserial = Json.str(pay, "data", "cardPay", "idserial");
        }
        String name = Json.str(loss, "data", "username");
        String status = Json.str(loss, "data", "accstatusStr");
        if (status.isEmpty()) {
            status = Json.str(home, "data", "cardinfo", "accstatusStr");
        }
        String balance = Json.str(pay, "data", "cardPay", "cardbal");
        String pcode = Json.str(home, "data", "pcodeCard");
        SessionData session = app.session();

        LinearLayout info = Ui.addSection(body, "卡片信息");
        Ui.addKv(info, "姓名", Json.or(name, "—"));
        Ui.addKv(info, "卡号", Json.or(idserial, "—"));
        Ui.addKv(info, "学号 / 工号", session == null ? "—" : Json.or(session.studentId, "—"));
        Ui.addKv(info, "卡状态", Json.or(status, "未获取"),
                isNormal(status) ? R.color.green : R.color.orange);
        Ui.addKv(info, "卡类型", Json.cardType(pcode));
        Ui.addKv(info, "余额", balance.isEmpty() ? "—" : Json.money(balance),
                R.color.buaa_blue);

        addChannelSection(context, pay);

        LinearLayout tip = Ui.addSection(body, "关于写操作");
        tip.addView(Ui.text(context,
                "本版本为只读客户端：不提供充值、支付、挂失、补卡等写操作，也不会调用任何资金相关接口。\n"
                        + "如需挂失或充值，请使用学校官方渠道（企业微信工作台「校园卡」或 "
                        + "m.icw.buaa.edu.cn 掌上服务大厅）。",
                13f, R.color.text_secondary, false), Ui.rowParams(context, 6));

        if (loss == null && pay == null) {
            addErrorCard(body, "卡务信息加载失败：" + Json.or(payError, lossError), this::refresh);
        } else if (lossError != null) {
            LinearLayout hint = Ui.addSection(body, "提示");
            hint.addView(Ui.text(context, "部分卡务数据获取失败：" + lossError, 12f,
                    R.color.text_hint, false), Ui.rowParams(context, 4));
        }
    }

    private void addChannelSection(Context context, JSONObject pay) {
        JSONObject jsonmsg = Json.obj(pay, "data", "jsonmsg");
        if (jsonmsg == null) {
            return;
        }
        LinearLayout card = Ui.addSection(body, "支付渠道状态（只读）");
        Ui.addKv(card, "微信支付", Json.or(Json.str(jsonmsg, "wechat"), "未开放"));
        Ui.addKv(card, "支付宝", Json.or(Json.str(jsonmsg, "alipay"), "未开放"));
        Ui.addKv(card, "建设银行", Json.or(Json.str(jsonmsg, "abc"), "未开放"));
        String pcode = Json.str(pay, "data", "pcode");
        if (!pcode.isEmpty()) {
            Ui.addKv(card, "支付参数 pcode", pcode);
        }
    }

    private static boolean isNormal(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }
        return status.contains("正常") || status.toLowerCase(java.util.Locale.US).contains("normal");
    }
}
