package cn.edu.buaa.scp.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.util.Json;

/**
 * 极简视图构造工具。第一版界面以「信息密度 + 可读性」为先，不做花哨动效。
 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int color(Context context, int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }

    public static TextView text(Context context, CharSequence content, float sp, int colorRes,
                                boolean bold) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color(context, colorRes));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static ViewGroup.MarginLayoutParams matchWrap() {
        return new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static void margins(View view, Context context, float left, float top, float right,
                               float bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margin = (ViewGroup.MarginLayoutParams) params;
            margin.setMargins(dp(context, left), dp(context, top), dp(context, right),
                    dp(context, bottom));
            view.setLayoutParams(margin);
        }
    }

    /** 添加一张白底圆角卡片，返回其内容容器。 */
    public static LinearLayout addCard(LinearLayout parent) {
        Context context = parent.getContext();
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(color(context, R.color.surface));
        card.setRadius(dp(context, 18));
        card.setCardElevation(0);
        card.setStrokeWidth(dp(context, 1));
        card.setStrokeColor(color(context, R.color.divider));
        LinearLayout body = column(context);
        body.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        card.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 12);
        parent.addView(card, params);
        return body;
    }

    /** 添加带标题的卡片，返回内容容器（标题已加好）。 */
    public static LinearLayout addSection(LinearLayout parent, String title) {
        LinearLayout body = addCard(parent);
        if (title != null && !title.isEmpty()) {
            TextView header = text(parent.getContext(), title, 15f,
                    R.color.text_primary, true);
            body.addView(header);
        }
        return body;
    }

    public static void gap(LinearLayout parent, float dp) {
        View view = new View(parent.getContext());
        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(parent.getContext(), dp)));
    }

    public static void divider(LinearLayout parent) {
        Context context = parent.getContext();
        View view = new View(context);
        view.setBackgroundColor(color(context, R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.6f)));
        params.topMargin = dp(context, 8);
        params.bottomMargin = dp(context, 8);
        parent.addView(view, params);
    }

    /** 一行「左标签 / 右值」。 */
    public static void addKv(LinearLayout parent, String label, String value) {
        addKv(parent, label, value, R.color.text_primary);
    }

    public static void addKv(LinearLayout parent, String label, String value, int valueColor) {
        Context context = parent.getContext();
        LinearLayout row = row(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 7);
        row.setLayoutParams(params);

        TextView key = text(context, label, 13f, R.color.text_secondary, false);
        row.addView(key, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = text(context, Json.or(value, "—"), 13f, valueColor, false);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    /** 选择芯片（类型筛选 / 月份筛选）。 */
    public static TextView chip(Context context, String title, boolean selected,
                                View.OnClickListener listener) {
        TextView chip = new TextView(context);
        chip.setText(title);
        chip.setTextSize(13f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(color(context, selected ? R.color.white : R.color.buaa_blue));
        chip.setOnClickListener(listener);
        return chip;
    }

    /** 横向占比条：左标题、右金额，下方进度条。 */
    public static View barRow(Context context, String label, String valueText, float fraction,
                              int barColorRes) {
        LinearLayout wrapper = column(context);
        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapperParams.topMargin = dp(context, 10);
        wrapper.setLayoutParams(wrapperParams);

        LinearLayout head = row(context);
        head.addView(text(context, label, 13f, R.color.text_primary, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView amount = text(context, valueText, 13f, R.color.text_secondary, false);
        head.addView(amount);
        wrapper.addView(head);

        FrameLayout track = new FrameLayout(context);
        track.setBackgroundResource(R.drawable.bg_bar_track);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6));
        trackParams.topMargin = dp(context, 6);
        track.setLayoutParams(trackParams);

        View fill = new View(context);
        fill.setBackgroundResource(R.drawable.bg_bar_fill);
        fill.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                color(context, barColorRes)));
        int percent = Math.max(0, Math.min(100, Math.round(fraction * 100)));
        FrameLayout.LayoutParams fillParams = new FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT);
        fillParams.width = 0;
        track.addView(fill, fillParams);
        track.post(() -> {
            int width = track.getWidth();
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) fill.getLayoutParams();
            params.width = Math.max(dp(context, 2), width * percent / 100);
            fill.setLayoutParams(params);
        });
        wrapper.addView(track);
        return wrapper;
    }

    /** 竖向柱状图的一根柱子（用于近半年趋势）。 */
    public static View columnBar(Context context, String monthLabel, String amount,
                                 float fraction, int barHeightDp) {
        LinearLayout column = column(context);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView amountView = text(context, amount, 11f, R.color.text_secondary, false);
        amountView.setGravity(Gravity.CENTER);
        column.addView(amountView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout barArea = new FrameLayout(context);
        LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(
                dp(context, 22), dp(context, barHeightDp));
        areaParams.topMargin = dp(context, 4);
        barArea.setLayoutParams(areaParams);

        View bar = new View(context);
        bar.setBackgroundResource(R.drawable.bg_bar_fill);
        bar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                color(context, R.color.buaa_blue)));
        int percent = Math.max(0, Math.min(100, Math.round(fraction * 100)));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM);
        barArea.addView(bar, barParams);
        barArea.post(() -> {
            int height = barArea.getHeight();
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
            params.height = Math.max(dp(context, 2), height * percent / 100);
            bar.setLayoutParams(params);
        });
        column.addView(barArea);

        TextView label = text(context, monthLabel, 10f, R.color.text_hint, false);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(context, 4);
        column.addView(label, labelParams);
        return column;
    }

    public static TextView statusDot(Context context, boolean normal) {
        TextView dot = new TextView(context);
        dot.setBackgroundResource(normal ? R.drawable.bg_dot_ok : R.drawable.bg_dot_warn);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(context, 8), dp(context, 8));
        params.setMargins(0, 0, dp(context, 6), 0);
        dot.setLayoutParams(params);
        return dot;
    }

    public static LinearLayout.LayoutParams rowParams(Context context, float topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        return params;
    }
}
