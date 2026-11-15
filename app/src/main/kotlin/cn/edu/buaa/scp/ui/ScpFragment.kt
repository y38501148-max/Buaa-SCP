package cn.edu.buaa.scp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.ScpApp
import cn.edu.buaa.scp.databinding.FragmentBodyBinding
import cn.edu.buaa.scp.net.SelfCheck
import cn.edu.buaa.scp.util.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 各标签页的公共骨架：下拉刷新 + 滚动内容区 + 统一错误卡片。
 *
 * [load] 在 `viewLifecycleOwner.lifecycleScope` 中执行（主线程协程），
 * 网络调用由 [cn.edu.buaa.scp.net.CardApi] 内部切到 IO 线程。
 */
abstract class ScpFragment : Fragment() {

    protected lateinit var app: ScpApp
    protected lateinit var swipe: SwipeRefreshLayout
    protected lateinit var body: LinearLayout

    private var binding: FragmentBodyBinding? = null
    private var loadedOnce = false
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ScpApp.get(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val inflated = FragmentBodyBinding.inflate(inflater, container, false)
        binding = inflated
        swipe = inflated.root.findViewById(R.id.swipe)
        body = inflated.root.findViewById(R.id.body)
        swipe.setColorSchemeResources(R.color.brand_blue, R.color.brand_gold)
        swipe.setOnRefreshListener { refresh() }
        body.addView(
            Ui.text(requireContext(), "正在加载…", 13f, R.color.text_hint).apply {
                setPadding(0, Ui.dp(requireContext(), 24f), 0, 0)
            },
        )
        return inflated.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!loadedOnce) {
            loadedOnce = true
            refresh()
        }
    }

    override fun onDestroyView() {
        loadedOnce = false
        job?.cancel()
        job = null
        binding = null
        super.onDestroyView()
    }

    /** 触发一次加载；结束时自动收起下拉指示器。 */
    protected fun refresh() {
        if (!isAdded || binding == null) return
        swipe.isRefreshing = true
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogBus.error("页面加载失败：${SelfCheck.describe(e)}")
                if (isAlive) {
                    clearBody()
                    addErrorCard(SelfCheck.describe(e)) { refresh() }
                }
            } finally {
                if (isAdded) swipe.isRefreshing = false
            }
        }
    }

    /** 数据加载（主线程协程）；界面更新可直接操作 View。 */
    protected abstract suspend fun load()

    protected val isAlive: Boolean
        get() = isAdded && view != null && binding != null

    protected fun clearBody() {
        binding?.body?.removeAllViews()
    }

    protected fun addErrorCard(message: String?, retry: (() -> Unit)? = null) {
        if (!isAlive) return
        val context = requireContext()
        val card = Ui.addSection(body, "加载失败")
        card.addView(
            Ui.text(context, message ?: "未知错误", 13f, R.color.text_secondary),
            Ui.rowParams(context, 6f),
        )
        if (retry != null) {
            val button = Ui.lightButton(requireContext(), "重试")
            button.setOnClickListener { retry() }
            card.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 10f) },
            )
        }
    }

    protected fun addEmptyCard(title: String, detail: String) {
        if (!isAlive) return
        val context = requireContext()
        val card = Ui.addSection(body, title)
        card.addView(
            Ui.text(context, detail, 13f, R.color.text_hint),
            Ui.rowParams(context, 6f),
        )
    }
}
