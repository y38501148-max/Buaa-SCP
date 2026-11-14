package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.model.KeyValue
import cn.edu.buaa.scp.util.Json

/**
 * 视图构造工具。界面按「深海军蓝 + 香槟金」的品牌色组织：
 * 金额用大字号，信息用轻卡片，操作区保持 48dp 触控高度。
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
        letterSpacing: Float = 0f,
    ): TextView = TextView(context).apply {
        text = content
        textSize = sp
        setTextColor(color(context, colorRes))
        typeface = ResourcesCompat.getFont(
            context,
            if (bold) R.font.noto_sans_sc_bold else R.font.noto_sans_sc_regular,
        )
        includeFontPadding = false
        this.letterSpacing = letterSpacing
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

    fun lp(
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Float = 0f,
        bottom: Float = 0f,
        context: Context,
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height).apply {
        topMargin = dp(context, top)
        bottomMargin = dp(context, bottom)
    }

    /** 添加一张白底圆角卡片，返回其内容容器。 */
    fun addCard(parent: LinearLayout): LinearLayout {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            setCardBackgroundColor(color(context, R.color.surface))
            radius = dp(context, 20f).toFloat()
            cardElevation = dp(context, 1f).toFloat()
            strokeWidth = 0
            setContentPadding(0, 0, 0, 0)
            rippleColor = tint(color(context, R.color.brand_blue_soft))
        }
        val body = column(context).apply {
            setPadding(dp(context, 18f), dp(context, 16f), dp(context, 18f), dp(context, 16f))
        }
        card.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(context, 12f) }
        parent.addView(card, params)
        return body
    }

    /** 卡片上方的分组小标题。 */
    fun sectionHeader(parent: LinearLayout, title: String) {
        val context = parent.context
        val label = text(context, title, 12f, R.color.text_secondary, bold = true, letterSpacing = 0.06f)
        label.setPadding(dp(context, 4f), 0, 0, 0)
        parent.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(context, 6f)
                bottomMargin = dp(context, 8f)
            },
        )
    }

    /** 添加带标题的卡片：标题在卡片外，内容在卡片内。 */
    fun addSection(parent: LinearLayout, title: String?): LinearLayout {
        if (!title.isNullOrEmpty()) sectionHeader(parent, title)
        return addCard(parent)
    }

    fun gap(parent: LinearLayout, dp: Float) {
        val context = parent.context
        parent.addView(
            View(context),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, dp)),
        )
    }

    fun divider(parent: LinearLayout, startInsetDp: Float = 0f) {
        val context = parent.context
        val line = View(context).apply { setBackgroundColor(color(context, R.color.divider)) }
        parent.addView(
            line,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1f).coerceAtLeast(1),
            ).apply {
                topMargin = dp(context, 10f)
                bottomMargin = dp(context, 10f)
                marginStart = dp(context, startInsetDp)
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
            text(context, label, 13.5f, R.color.text_secondary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        line.addView(
            text(context, Json.or(value, "—"), 13.5f, valueColor, bold = true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END },
        )
        parent.addView(line, rowParams(context, 9f))
        return line
    }

    fun addKv(parent: LinearLayout, item: KeyValue): LinearLayout =
        addKv(parent, item.key, item.value)

    /** 胶囊标签。 */
    fun pill(
        context: Context,
        title: String,
        backgroundRes: Int,
        textColorRes: Int,
        bold: Boolean = true,
    ): TextView = text(context, title, 11.5f, textColorRes, bold = bold).apply {
        gravity = Gravity.CENTER
        setPadding(dp(context, 10f), dp(context, 4f), dp(context, 10f), dp(context, 4f))
        setBackgroundResource(backgroundRes)
    }

    /** 状态圆点。 */
    fun statusDot(context: Context, normal: Boolean): View = View(context).apply {
        setBackgroundResource(if (normal) R.drawable.bg_dot_ok else R.drawable.bg_dot_warn)
        layoutParams = LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f))
            .apply { rightMargin = dp(context, 6f) }
    }

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
        setPadding(dp(context, 14f), dp(context, 7f), dp(context, 14f), dp(context, 7f))
        setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        setTextColor(color(context, if (selected) R.color.white else R.color.brand_blue))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
        foreground = selectableForeground(context)
    }

    /** 圆形头像：取姓名首字。 */
    fun avatar(context: Context, name: String): TextView {
        val initial = name.trim().take(1).ifEmpty { "通" }
        return text(context, initial, 19f, R.color.white, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_avatar)
            layoutParams = LinearLayout.LayoutParams(dp(context, 46f), dp(context, 46f))
        }
    }

    /** 带浅色底的图标方块。 */
    fun iconTile(
        context: Context,
        iconRes: Int,
        tintColorRes: Int,
        backgroundTintRes: Int = R.color.brand_blue_soft,
        sizeDp: Int = 44,
    ): FrameLayout {
        val box = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_icon_tile)
            backgroundTintList = tint(color(context, backgroundTintRes))
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = tint(color(context, tintColorRes))
            setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
        }
        box.addView(
            icon,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        box.layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp.toFloat()), dp(context, sizeDp.toFloat()))
        return box
    }

    /** 设置页的一行：图标 + 标题 + 右侧值/箭头。 */
    fun actionRow(
        parent: LinearLayout,
        iconRes: Int,
        title: String,
        value: String = "",
        danger: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val context = parent.context
        val line = row(context).apply {
            setPadding(0, dp(context, 10f), 0, dp(context, 10f))
            minimumHeight = dp(context, 48f)
        }
        val iconBg = if (danger) R.color.brand_gold_soft else R.color.brand_blue_soft
        val iconTint = if (danger) R.color.red else R.color.brand_blue
        line.addView(iconTile(context, iconRes, iconTint, iconBg, 38))
        val titleView = text(
            context,
            title,
            14.5f,
            if (danger) R.color.red else R.color.text_primary,
            bold = true,
        )
        line.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 12f)
            },
        )
        if (value.isNotEmpty()) {
            line.addView(text(context, value, 12.5f, R.color.text_hint))
        }
        if (onClick != null) {
            val arrow = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = tint(color(context, R.color.text_hint))
                setPadding(dp(context, 2f), dp(context, 2f), 0, dp(context, 2f))
            }
            line.addView(arrow, LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)).apply {
                marginStart = dp(context, 6f)
            })
            line.isClickable = true
            line.isFocusable = true
            line.foreground = selectableForeground(context)
            line.setOnClickListener { onClick() }
        }
        parent.addView(line)
        return line
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
            text(context, Json.or(label, "—"), 13f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(text(context, valueText, 13f, R.color.text_secondary, bold = true))
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
                this.width = (width * percent / 100).coerceAtLeast(dp(context, 3f))
                fill.layoutParams = this
            }
        }
        wrapper.addView(
            track,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 6f),
            ).apply { topMargin = dp(context, 8f) },
        )
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 14f) }
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
            text(context, amount, 10f, R.color.text_secondary, bold = true)
                .apply { gravity = Gravity.CENTER },
        )

        val barArea = FrameLayout(context)
        val bar = View(context).apply {
            setBackgroundResource(R.drawable.bg_column_bar)
            backgroundTintList = tint(color(context, R.color.brand_blue))
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
                this.height = (height * percent / 100).coerceAtLeast(dp(context, 3f))
                bar.layoutParams = this
            }
        }
        column.addView(
            barArea,
            LinearLayout.LayoutParams(dp(context, 18f), dp(context, barHeightDp.toFloat()))
                .apply { topMargin = dp(context, 6f) },
        )

        column.addView(
            text(context, monthLabel, 10f, R.color.text_hint).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 6f) },
        )
        return column
    }

    /** 浅色描边按钮（次要操作）。 */
    fun lightButton(context: Context, title: String): MaterialButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        typeface = ResourcesCompat.getFont(context, R.font.noto_sans_sc_bold)
        setTextColor(color(context, R.color.brand_blue))
        strokeWidth = dp(context, 1f)
        strokeColor = tint(color(context, R.color.divider))
        backgroundTintList = tint(color(context, R.color.surface))
        cornerRadius = dp(context, 14f)
        minimumHeight = dp(context, 48f)
        insetTop = 0
        insetBottom = 0
    }

    /** 主按钮（主要操作）。 */
    fun primaryButton(context: Context, title: String): MaterialButton = MaterialButton(context).apply {
        text = title
        isAllCaps = false
        textSize = 15f
        typeface = ResourcesCompat.getFont(context, R.font.noto_sans_sc_bold)
        backgroundTintList = tint(color(context, R.color.brand_blue))
        setTextColor(color(context, R.color.white))
        cornerRadius = dp(context, 16f)
        minimumHeight = dp(context, 52f)
        insetTop = 0
        insetBottom = 0
    }

    /** 金色按钮（充值等资金入口）。 */
    fun goldButton(context: Context, title: String): MaterialButton = MaterialButton(context).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        typeface = ResourcesCompat.getFont(context, R.font.noto_sans_sc_bold)
        backgroundTintList = tint(color(context, R.color.brand_gold))
        setTextColor(color(context, R.color.gold_pill_text))
        cornerRadius = dp(context, 22f)
        minimumHeight = dp(context, 44f)
        insetTop = 0
        insetBottom = 0
    }

    /**
     * 让 Toolbar 的背景延伸到状态栏，同时把标题区域整体下移。
     *
     * 只加 paddingTop 会把 56dp 的标题区压扁，导致 20sp 标题顶部被裁切；
     * 这里把 Toolbar 高度同步增加 topInset。
     */
    fun applyToolbarTopInset(toolbar: androidx.appcompat.widget.Toolbar, topInset: Int) {
        val context = toolbar.context
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.actionBarSize, value, true)
        val actionBarSize = TypedValue.complexToDimensionPixelSize(
            value.data,
            context.resources.displayMetrics,
        )
        val extra = dp(context, 8f)
        toolbar.layoutParams = toolbar.layoutParams.apply {
            height = actionBarSize + topInset + extra
        }
        toolbar.setPadding(
            toolbar.paddingLeft,
            topInset + extra / 2,
            toolbar.paddingRight,
            toolbar.paddingBottom,
        )
    }

    /** 44dp 图标按钮（翻月、刷新等）。 */
    fun iconButton(
        context: Context,
        iconRes: Int,
        description: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): FrameLayout {
        val box = FrameLayout(context).apply {
            setPadding(dp(context, 10f), dp(context, 10f), dp(context, 10f), dp(context, 10f))
            isClickable = true
            isFocusable = true
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.32f
            foreground = selectableForeground(context)
            contentDescription = description
            setOnClickListener { if (enabled) onClick() }
        }
        box.addView(
            ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = tint(color(context, R.color.brand_blue))
                contentDescription = description
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        box.layoutParams = LinearLayout.LayoutParams(dp(context, 44f), dp(context, 44f))
        return box
    }

    /** 标准列表右侧箭头。 */
    fun chevron(context: Context, tintColorRes: Int = R.color.text_hint): ImageView =
        ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_right)
            imageTintList = tint(color(context, tintColorRes))
            layoutParams = LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f))
        }

    /** 系统 selectableItemBackground，用于给自定义可点击视图补上按压反馈。 */
    fun selectableForeground(context: Context): android.graphics.drawable.Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(context, value.resourceId)
    }

    /** 点击波纹（部分容器需要显式抬起 pressed 状态）。 */
    fun applyRipple(view: View) {
        val context = view.context
        val mask = android.graphics.drawable.ColorDrawable(0xFFFFFFFF.toInt())
        view.background = RippleDrawable(
            ColorStateList.valueOf(color(context, R.color.brand_blue_soft)),
            view.background,
            mask,
        )
    }
}
