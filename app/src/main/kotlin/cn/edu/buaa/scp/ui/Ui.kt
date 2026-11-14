package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.KeyValue
import cn.edu.buaa.scp.util.Json

/**
 * 极简视图构造工具。第一版界面以「信息密度 + 可读性」为先，不做花哨动效。
 */
object Ui {

    fun dp(context: Context, value: Float): Int =
        Math.round(value * context.resources.displayMetrics.density)

    fun color(context: Context, colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)

    fun text(
        context: Context,
        content: CharSequence?,
        sp: Float,
        colorRes: Int,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = content
        textSize = sp
        setTextColor(color(context, colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(context, 3f).toFloat(), 1f)
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun rowParams(context: Context, topMarginDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, topMarginDp) }

    /** 添加一张白底圆角卡片，返回其内容容器。 */
    fun addCard(parent: LinearLayout): LinearLayout {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(color(context, R.color.surface))
            radius = dp(context, 18f).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1f)
            strokeColor = color(context, R.color.divider)
        }
        val body = column(context).apply {
            setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
        }
        card.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 12f) }
        parent.addView(card, params)
        return body
    }

    /** 添加带标题的卡片，返回内容容器（标题已加好）。 */
    fun addSection(parent: LinearLayout, title: String?): LinearLayout {
        val body = addCard(parent)
        if (!title.isNullOrEmpty()) {
            body.addView(text(parent.context, title, 15f, R.color.text_primary, bold = true))
        }
        return body
    }

    fun gap(parent: LinearLayout, dp: Float) {
        val context = parent.context
        parent.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, dp)),
        )
    }

    fun divider(parent: LinearLayout) {
        val context = parent.context
        parent.addView(
            View(context).apply { setBackgroundColor(color(context, R.color.divider)) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 0.6f).coerceAtLeast(1),
            ).apply {
                topMargin = dp(context, 8f)
                bottomMargin = dp(context, 8f)
            },
        )
    }

    /** 一行「左标签 / 右值」，返回整行以便挂点击事件。 */
    fun addKv(
        parent: LinearLayout,
        label: String,
        value: String,
        valueColor: Int = R.color.text_primary,
    ): LinearLayout {
        val context = parent.context
        val line = row(context)
        line.addView(
            text(context, label, 13f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.addView(
            text(context, Json.or(value, "—"), 13f, valueColor),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END },
        )
        parent.addView(line, rowParams(context, 7f))
        return line
    }

    fun addKv(parent: LinearLayout, item: KeyValue): LinearLayout =
        addKv(parent, item.key, item.value)

    /** 选择芯片（类型筛选 / 月份筛选）。 */
    fun chip(
        context: Context,
        title: String,
        selected: Boolean,
        onClick: View.OnClickListener,
    ): TextView = TextView(context).apply {
        text = title
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
        setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        setTextColor(
            color(context, if (selected) R.color.white else R.color.buaa_blue),
        )
        setOnClickListener(onClick)
    }

    /** 横向占比条：左标题、右金额，下方进度条。 */
    fun barRow(
        context: Context,
        label: String,
        valueText: String,
        fraction: Float,
        barColorRes: Int,
    ): View {
        val wrapper = column(context)
        val head = row(context)
        head.addView(
            text(context, Json.or(label, "—"), 13f, R.color.text_primary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(text(context, valueText, 13f, R.color.text_secondary))
        wrapper.addView(head)

        val track = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_bar_track)
        }
        val fill = View(context).apply {
            setBackgroundResource(R.drawable.bg_bar_fill)
            backgroundTintList = tint(color(context, barColorRes))
        }
        track.addView(
            fill,
            FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        track.post {
            val width = track.width
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            (fill.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = (width * percent / 100).coerceAtLeast(dp(context, 2f))
                fill.layoutParams = this
            }
        }
        wrapper.addView(
            track,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 6f),
            ).apply { topMargin = dp(context, 6f) },
        )
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 10f) }
        }
    }

    /** 竖向柱状图的一根柱子（用于近半年趋势）。 */
    fun columnBar(
        context: Context,
        monthLabel: String,
        amount: String,
        fraction: Float,
        barHeightDp: Int,
    ): View {
        val column = column(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(
            text(context, amount, 11f, R.color.text_secondary).apply { gravity = Gravity.CENTER },
        )

        val barArea = FrameLayout(context)
        val bar = View(context).apply {
            setBackgroundResource(R.drawable.bg_bar_fill)
            backgroundTintList = tint(color(context, R.color.buaa_blue))
        }
        barArea.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM,
            ),
        )
        barArea.post {
            val height = barArea.height
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            (bar.layoutParams as FrameLayout.LayoutParams).apply {
                this.height = (height * percent / 100).coerceAtLeast(dp(context, 2f))
                bar.layoutParams = this
            }
        }
        column.addView(
            barArea,
            LinearLayout.LayoutParams(dp(context, 22f), dp(context, barHeightDp.toFloat()))
                .apply { topMargin = dp(context, 4f) },
        )

        column.addView(
            text(context, monthLabel, 10f, R.color.text_hint).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 4f) },
        )
        return column
    }

    /** 浅色按钮（次要操作）。 */
    fun lightButton(context: Context, title: String): MaterialButton = MaterialButton(context).apply {
        text = title
        isAllCaps = false
        backgroundTintList = tint(color(context, R.color.buaa_blue_light))
        setTextColor(color(context, R.color.buaa_blue))
    }

    /** 主按钮（主要操作）。 */
    fun primaryButton(context: Context, title: String): MaterialButton = MaterialButton(context).apply {
        text = title
        isAllCaps = false
        backgroundTintList = tint(color(context, R.color.buaa_blue))
        setTextColor(color(context, R.color.white))
    }

    fun statusDot(context: Context, normal: Boolean): View = View(context).apply {
        setBackgroundResource(if (normal) R.drawable.bg_dot_ok else R.drawable.bg_dot_warn)
        layoutParams = LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f))
            .apply { rightMargin = dp(context, 6f) }
    }
}
