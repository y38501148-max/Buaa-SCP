package cn.edu.buaa.scp.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import cn.edu.buaa.scp.R;
import cn.edu.buaa.scp.ScpApp;
import cn.edu.buaa.scp.net.SelfCheck;
import cn.edu.buaa.scp.util.LogBus;

/**
 * 各标签页的公共骨架：下拉刷新 + 滚动内容区 + 统一错误卡片。
 *
 * <p>{@link #load()} 在后台线程执行，界面更新请用 {@link ScpApp#ui(Runnable)}。
 */
public abstract class ScpFragment extends Fragment {

    protected ScpApp app;
    protected SwipeRefreshLayout swipe;
    protected LinearLayout body;

    private boolean loadedOnce;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = ScpApp.get(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_body, container, false);
        swipe = root.findViewById(R.id.swipe);
        body = root.findViewById(R.id.body);
        swipe.setColorSchemeResources(R.color.buaa_blue);
        swipe.setOnRefreshListener(this::refresh);
        body.addView(Ui.text(requireContext(), "正在加载…", 13f, R.color.text_hint, false));
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!loadedOnce) {
            loadedOnce = true;
            refresh();
        }
    }

    /** 触发一次后台加载；结束时自动收起下拉指示器。 */
    protected final void refresh() {
        if (!isAdded()) {
            return;
        }
        if (swipe != null) {
            swipe.setRefreshing(true);
        }
        app.async(() -> {
            try {
                load();
            } catch (Exception e) {
                LogBus.error("页面加载失败：" + SelfCheck.describe(e));
                app.ui(() -> {
                    if (!isAlive()) {
                        return;
                    }
                    clearBody();
                    addErrorCard(body, SelfCheck.describe(e), this::refresh);
                });
            } finally {
                app.ui(() -> {
                    if (isAdded() && swipe != null) {
                        swipe.setRefreshing(false);
                    }
                });
            }
        });
    }

    /** 在后台线程执行数据加载；界面更新通过 {@link ScpApp#ui(Runnable)} 回到主线程。 */
    protected abstract void load() throws Exception;

    protected final boolean isAlive() {
        return isAdded() && getView() != null && body != null;
    }

    protected final void clearBody() {
        if (body != null) {
            body.removeAllViews();
        }
    }

    protected final void addErrorCard(LinearLayout parent, String message, @Nullable Runnable retry) {
        LinearLayout card = Ui.addSection(parent, "加载失败");
        TextView detail = Ui.text(parent.getContext(), message == null ? "未知错误" : message,
                13f, R.color.text_secondary, false);
        card.addView(detail, Ui.rowParams(parent.getContext(), 6));
        TextView hint = Ui.text(parent.getContext(),
                "可下拉刷新重试；若持续失败，请到「运行日志」查看网络细节。",
                12f, R.color.text_hint, false);
        card.addView(hint, Ui.rowParams(parent.getContext(), 6));
        if (retry != null) {
            MaterialButton button = new MaterialButton(parent.getContext());
            button.setText("重试");
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    Ui.color(parent.getContext(), R.color.buaa_blue_light)));
            button.setTextColor(Ui.color(parent.getContext(), R.color.buaa_blue));
            button.setOnClickListener(v -> retry.run());
            card.addView(button, Ui.rowParams(parent.getContext(), 4));
        }
    }

    protected final void addEmptyCard(LinearLayout parent, String title, String detail) {
        LinearLayout card = Ui.addSection(parent, title);
        card.addView(Ui.text(parent.getContext(), detail, 13f, R.color.text_hint, false),
                Ui.rowParams(parent.getContext(), 6));
    }
}
