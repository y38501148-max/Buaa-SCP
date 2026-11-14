package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.util.Json;

/**
 * 流水页：按月份 + 交易类型分页拉取 {@code /selftrade/queryCardSelfTradeList}。
 *
 * <p>tradetype 取值来自官方前端：-1 全部、1 消费、2 充值、3 补助、4 转账。
 */
public class TradesFragment extends ScpFragment {

    private static final int PAGE_SIZE = 10;

    private static final int TYPE_ALL = -1;
    private static final int TYPE_CONSUME = 1;
    private static final int TYPE_RECHARGE = 2;
    private static final int TYPE_SUBSIDY = 3;
    private static final int TYPE_TRANSFER = 4;

    private String month = Json.currentMonth();
    private int tradetype = TYPE_ALL;

    private final List<JSONObject> rows = new ArrayList<>();
    private int page = 0;
    private int totalPage = -1;
    private boolean loadingMore;
    private String summary = "";

    @Override
    protected void load() {
        final String m = month;
        final int type = tradetype;
        final String[] range = Json.monthRange(m);

        JSONObject data = null;
        String error = null;
        try {
            data = app.callWithRecovery(() -> app.api().trades(
                    range[0], range[1], type, 0, PAGE_SIZE));
        } catch (Exception e) {
            error = SelfCheck.describe(e);
        }

        String summaryText = "";
        try {
            summaryText = loadSummary(m, type);
        } catch (Exception e) {
            summaryText = "汇总额度暂不可用：" + SelfCheck.describe(e);
        }

        final List<JSONObject> firstPage = parseRows(data);
        final int total = parseTotalPage(data);
        final String finalSummary = summaryText;
        final String finalError = error;
        app.markSynced();

        app.ui(() -> {
            if (!isAlive()) {
                return;
            }
            rows.clear();
            rows.addAll(firstPage);
            page = 0;
            totalPage = total;
            summary = finalSummary;
            render(finalError);
        });
    }

    private void render(String error) {
        clearBody();
        Context context = requireContext();
        addHeader(context);
        addTypeChips(context);
        addSummary(context);
        addList(context, error);
        addFooter(context);
    }

    private void addHeader(Context context) {
        LinearLayout card = Ui.addCard(body);
        LinearLayout row = Ui.row(context);
        TextView prev = Ui.chip(context, "‹ 上月", false, v -> shiftMonth(-1));
        row.addView(prev);

        TextView label = Ui.text(context, Json.monthLabel(month), 17f, R.color.text_primary, true);
        label.setGravity(Gravity.CENTER);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        boolean canNext = !month.equals(Json.currentMonth());
        TextView next = Ui.chip(context, "下月 ›", false, v -> shiftMonth(1));
        next.setEnabled(canNext);
        next.setAlpha(canNext ? 1f : 0.35f);
        row.addView(next);
        card.addView(row);
    }

    private void addTypeChips(Context context) {
        LinearLayout container = Ui.addCard(body);
        container.addView(Ui.text(context, "交易类型", 13f, R.color.text_secondary, false));

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = Ui.row(context);
        scroll.addView(chipRow);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = Ui.dp(context, 8);

        int[] types = {TYPE_ALL, TYPE_CONSUME, TYPE_RECHARGE, TYPE_SUBSIDY, TYPE_TRANSFER};
        String[] names = {"全部", "消费", "充值", "补助", "转账"};
        for (int i = 0; i < types.length; i++) {
            final int type = types[i];
            TextView chip = Ui.chip(context, names[i], tradetype == type,
                    v -> selectType(type));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.rightMargin = Ui.dp(context, 8);
            chipRow.addView(chip, params);
        }
        container.addView(scroll, scrollParams);
    }

    private void addSummary(Context context) {
        if (summary == null || summary.isEmpty()) {
            return;
        }
        LinearLayout card = Ui.addCard(body);
        card.addView(Ui.text(context, summary, 13f, R.color.text_primary, false));
    }

    private void addList(Context context, String error) {
        LinearLayout card = Ui.addSection(body, "流水明细");
        if (error != null) {
            card.addView(Ui.text(context, "加载失败：" + error, 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        if (rows.isEmpty()) {
            card.addView(Ui.text(context, "未查询到记录", 12f, R.color.text_hint, false),
                    Ui.rowParams(context, 6));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.get(i);
            addTradeRow(context, card, row);
            if (i < rows.size() - 1) {
                Ui.divider(card);
            }
        }
    }

    private void addTradeRow(Context context, LinearLayout parent, JSONObject row) {
        String merchant = Json.or(Json.str(row, "mername"),
                Json.or(Json.str(row, "txname"), Json.str(row, "summary")));
        String time = Json.or(Json.str(row, "txdate"), Json.str(row, "txtime"));

        LinearLayout line = Ui.row(context);
        LinearLayout left = Ui.column(context);

        left.addView(Ui.text(context, Json.or(merchant, "未知商户"), 14f,
                R.color.text_primary, false));
        left.addView(Ui.text(context, time, 11f, R.color.text_hint, false),
                Ui.rowParams(context, 3));
        line.addView(left, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String raw = Json.str(row, "txamt");
        String yuan = Json.fenToYuan(raw);
        boolean negative = yuan.startsWith("-");
        String abs = negative ? yuan.substring(1) : yuan;
        String display = (negative ? "-¥" : "+¥") + abs;
        int color = negative ? R.color.red : R.color.green;
        TextView amount = Ui.text(context, display, 15f, color, true);
        amount.setGravity(Gravity.END);
        line.addView(amount);
        parent.addView(line, Ui.rowParams(context, 8));
    }

    private void addFooter(Context context) {
        if (rows.isEmpty()) {
            return;
        }
        boolean hasMore = totalPage > 0 && page < totalPage - 1;
        if (hasMore) {
            com.google.android.material.button.MaterialButton more =
                    new com.google.android.material.button.MaterialButton(context);
            more.setText(loadingMore ? "正在加载…" : "加载更多（" + (page + 1) + "/" + totalPage + "）");
            more.setEnabled(!loadingMore);
            more.setOnClickListener(v -> loadMore());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(context, 4);
            body.addView(more, params);
        } else {
            TextView end = Ui.text(context, "没有更多数据了", 12f, R.color.text_hint, false);
            end.setGravity(Gravity.CENTER);
            body.addView(end, Ui.rowParams(context, 8));
        }
    }

    private void loadMore() {
        if (loadingMore) {
            return;
        }
        final int currentPage = page;
        final int currentTotal = totalPage;
        if (currentTotal <= 0 || currentPage >= currentTotal - 1) {
            return;
        }
        loadingMore = true;
        final int next = currentPage + 1;
        final String m = month;
        final int type = tradetype;
        final String[] range = Json.monthRange(m);
        render(null);
        app.async(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = app.callWithRecovery(() -> app.api().trades(
                        range[0], range[1], type, next, PAGE_SIZE));
            } catch (Exception e) {
                error = SelfCheck.describe(e);
            }
            final List<JSONObject> more = parseRows(data);
            final int total = parseTotalPage(data) >= 0 ? parseTotalPage(data) : currentTotal;
            final String finalError = error;
            app.ui(() -> {
                loadingMore = false;
                if (!isAlive()) {
                    return;
                }
                if (finalError != null) {
                    render(finalError);
                    return;
                }
                rows.addAll(more);
                page = next;
                totalPage = total;
                render(null);
            });
        });
    }

    private void shiftMonth(int delta) {
        String target = Json.shiftMonth(month, delta);
        if (target.compareTo(Json.currentMonth()) > 0) {
            return;
        }
        month = target;
        rows.clear();
        page = 0;
        totalPage = -1;
        refresh();
    }

    private void selectType(int type) {
        if (tradetype == type) {
            return;
        }
        tradetype = type;
        rows.clear();
        page = 0;
        totalPage = -1;
        summary = "";
        refresh();
    }

    /** 官方前端对「消费 / 充值」额外取本月汇总；其他类型不展示汇总。 */
    private String loadSummary(String m, int type) throws Exception {
        if (type == TYPE_CONSUME) {
            JSONObject half = app.callWithRecovery(() -> app.api().halfYear(m, m, null));
            String amount = Json.fenToYuan(Json.str(half, "resultData", "totalamt"));
            String count = Json.str(half, "resultData", "totalcount");
            return "本月消费 " + Json.money(amount)
                    + (count.isEmpty() ? "" : "，共 " + count + " 笔");
        }
        if (type == TYPE_RECHARGE) {
            JSONObject recharge = app.callWithRecovery(() -> app.api().rechargeByMonth(m, m));
            JSONArray list = Json.arr(recharge, "resultData", "billList");
            JSONObject first = list == null || list.length() == 0 ? null : list.optJSONObject(0);
            if (first == null) {
                return "本月暂无充值记录";
            }
            return "本月充值 " + Json.money(Json.fenToYuan(Json.str(first, "txamt")))
                    + "，共 " + Json.or(Json.str(first, "countnum"), "0") + " 笔";
        }
        return "";
    }

    private static List<JSONObject> parseRows(JSONObject data) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray array = Json.arr(data, "resultData", "rows");
        if (array == null) {
            return list;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null) {
                list.add(row);
            }
        }
        return list;
    }

    private static int parseTotalPage(JSONObject data) {
        String text = Json.str(data, "resultData", "totalpage");
        if (text.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
