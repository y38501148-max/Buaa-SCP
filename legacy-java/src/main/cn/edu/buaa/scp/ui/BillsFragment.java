package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.util.Json;

/** 账单页：月度分类消费、按月充值、近半年支出趋势。全部为只读统计。 */
public class BillsFragment extends ScpFragment {

    private String month = Json.currentMonth();

    @Override
    protected void load() {
        final String m = month;
        final String openid = app.openid();
        final String start6 = Json.shiftMonth(m, -5);

        JSONObject bill = null;
        String billError = null;
        try {
            bill = app.callWithRecovery(() -> app.api().monthlyBill(m, openid));
        } catch (Exception e) {
            billError = SelfCheck.describe(e);
        }

        JSONObject recharge = null;
        String rechargeError = null;
        try {
            recharge = app.callWithRecovery(() -> app.api().rechargeByMonth(start6, m));
        } catch (Exception e) {
            rechargeError = SelfCheck.describe(e);
        }

        JSONObject half = null;
        String halfError = null;
        try {
            half = app.callWithRecovery(() -> app.api().halfYear(start6, m, months(start6, m)));
        } catch (Exception e) {
            halfError = SelfCheck.describe(e);
        }

        if (bill != null || recharge != null || half != null) {
            app.markSynced();
        }

        final JSONObject fBill = bill;
        final JSONObject fRecharge = recharge;
        final JSONObject fHalf = half;
        final String fBillError = billError;
        final String fRechargeError = rechargeError;
        final String fHalfError = halfError;
        app.ui(() -> {
            if (!isAlive()) {
                return;
            }
            render(fBill, fBillError, fRecharge, fRechargeError, fHalf, fHalfError);
        });
    }

    private void render(JSONObject bill, String billError, JSONObject recharge, String rechargeError,
                        JSONObject half, String halfError) {
        clearBody();
        Context context = requireContext();
        addMonthHeader(context);
        addMonthlyBillCard(context, bill, billError);
        addRechargeCard(context, recharge, rechargeError);
        addHalfYearCard(context, half, halfError);
    }

    private void addMonthHeader(Context context) {
        LinearLayout card = Ui.addCard(body);
        LinearLayout row = Ui.row(context);
        TextView prev = Ui.chip(context, "‹ 上月", false, v -> shiftMonth(-1));
        row.addView(prev);

        TextView label = Ui.text(context, Json.monthLabel(month), 17f, R.color.text_primary, true);
        label.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, labelParams);

        boolean canNext = !month.equals(Json.currentMonth());
        TextView next = Ui.chip(context, "下月 ›", false, v -> shiftMonth(1));
        next.setEnabled(canNext);
        next.setAlpha(canNext ? 1f : 0.35f);
        row.addView(next);
        card.addView(row);
    }

    private void shiftMonth(int delta) {
        String target = Json.shiftMonth(month, delta);
        if (target.compareTo(Json.currentMonth()) > 0) {
            return;
        }
        month = target;
        refresh();
    }

    private void addMonthlyBillCard(Context context, JSONObject bill, String error) {
        LinearLayout card = Ui.addSection(body, "本月消费");
        if (error != null) {
            card.addView(Ui.text(context, "加载失败：" + error, 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        String sum = Json.str(bill, "resultData", "sumamt");
        JSONArray list = Json.arr(bill, "resultData", "billList");
        double total = Json.number(sum);

        LinearLayout head = Ui.row(context);
        head.addView(Ui.text(context, "合计", 13f, R.color.text_secondary, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(context, Json.money(sum), 18f, R.color.buaa_blue, true));
        card.addView(head, Ui.rowParams(context, 8));

        if (list == null || list.length() == 0) {
            card.addView(Ui.text(context, "本月暂无消费记录", 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String rawName = Json.str(row, "name");
            String value = Json.str(row, "value");
            String label = rawName;
            int colon = rawName.indexOf(':');
            if (colon > 0) {
                label = rawName.substring(0, colon);
            }
            double amount = Json.number(value);
            float fraction = total > 0 ? (float) (amount / total) : 0f;
            card.addView(Ui.barRow(context, label, Json.money(value), fraction, R.color.buaa_blue));
        }
    }

    private void addRechargeCard(Context context, JSONObject recharge, String error) {
        LinearLayout card = Ui.addSection(body, "充值记录（近半年）");
        if (error != null) {
            card.addView(Ui.text(context, "加载失败：" + error, 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        JSONArray list = Json.arr(recharge, "resultData", "billList");
        if (list == null || list.length() == 0) {
            card.addView(Ui.text(context, "近半年暂无充值记录", 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        double max = 0;
        for (int i = 0; i < list.length(); i++) {
            max = Math.max(max, Json.number(Json.fenToYuan(Json.str(list.optJSONObject(i), "txamt"))));
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String date = Json.str(row, "txdate");
            String amountText = Json.fenToYuan(Json.str(row, "txamt"));
            String count = Json.str(row, "countnum");
            String label = date + (count.isEmpty() ? "" : "（" + count + " 笔）");
            double amount = Json.number(amountText);
            card.addView(Ui.barRow(context, label, Json.money(amountText),
                    max > 0 ? (float) (amount / max) : 0f, R.color.green));
        }
    }

    private void addHalfYearCard(Context context, JSONObject half, String error) {
        LinearLayout card = Ui.addSection(body, "近半年支出趋势");
        if (error != null) {
            card.addView(Ui.text(context, "加载失败：" + error, 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        String totalAmount = Json.fenToYuan(Json.str(half, "resultData", "totalamt"));
        String totalCount = Json.str(half, "resultData", "totalcount");
        card.addView(Ui.text(context,
                "合计 " + Json.money(totalAmount)
                        + (totalCount.isEmpty() ? "" : "，共 " + totalCount + " 笔"),
                13f, R.color.text_secondary, false), Ui.rowParams(context, 6));

        JSONArray list = Json.arr(half, "resultData", "billList");
        if (list == null || list.length() == 0) {
            card.addView(Ui.text(context, "暂无月度数据", 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        double max = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            max = Math.max(max, Json.number(Json.fenToYuan(Json.str(row, "txamt"))));
        }
        LinearLayout chart = Ui.row(context);
        chart.setGravity(android.view.Gravity.BOTTOM);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chartParams.topMargin = Ui.dp(context, 14);
        chart.setLayoutParams(chartParams);

        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null) {
                continue;
            }
            String date = Json.str(row, "txdate");
            String shortMonth = date.length() >= 7 ? date.substring(5) + "月" : date;
            String amountText = Json.fenToYuan(Json.str(row, "txamt"));
            double amount = Json.number(amountText);
            LinearLayout column = (LinearLayout) Ui.columnBar(context, shortMonth,
                    Json.yuan(amountText), max > 0 ? (float) (amount / max) : 0f, 96);
            chart.addView(column, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(chart);
    }

    /** 生成 startMonth..endMonth 的月份数组，供近半年接口使用。 */
    private static JSONArray months(String startMonth, String endMonth) {
        JSONArray array = new JSONArray();
        String cursor = startMonth;
        for (int i = 0; i < 12; i++) {
            array.put(cursor);
            if (cursor.equals(endMonth)) {
                break;
            }
            cursor = Json.shiftMonth(cursor, 1);
        }
        return array;
    }

    static String formatPercent(double fraction) {
        return String.format(Locale.US, "%.0f%%", fraction * 100);
    }
}
