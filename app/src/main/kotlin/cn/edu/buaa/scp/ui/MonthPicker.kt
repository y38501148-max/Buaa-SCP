package cn.edu.buaa.scp.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.util.Json
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/** 年月选择器：年份可翻，月份 3×4 网格；当前月之后的月份不可选。 */
object MonthPicker {

    private const val MIN_YEAR = 2018

    fun show(context: Context, selected: String, onPick: (String) -> Unit) {
        val nowYear = Calendar.getInstance().get(Calendar.YEAR)
        val nowMonth = Calendar.getInstance().get(Calendar.MONTH) + 1
        var year = selected.take(4).toIntOrNull()?.coerceIn(MIN_YEAR, nowYear) ?: nowYear
        val selectedYear = year
        val selectedMonth = selected.takeLast(2).toIntOrNull() ?: nowMonth

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20f), Ui.dp(context, 4f), Ui.dp(context, 20f), Ui.dp(context, 4f))
        }

        lateinit var render: () -> Unit
        val yearRow = Ui.row(context)
        val prev = Ui.iconButton(context, R.drawable.ic_chevron_left, "上一年") {
            if (year > MIN_YEAR) {
                year--
                render()
            }
        }
        val yearLabel = Ui.text(context, "$year 年", 18f, R.color.text_primary, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val next = Ui.iconButton(context, R.drawable.ic_chevron_right, "下一年", enabled = year < nowYear) {
            if (year < nowYear) {
                year++
                render()
            }
        }
        yearRow.addView(prev)
        yearRow.addView(yearLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        yearRow.addView(next)
        root.addView(yearRow)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 4f))
        }
        root.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("选择年月")
            .setView(root)
            .setNegativeButton("取消", null)
            .setNeutralButton("本月") { _, _ -> onPick(Json.currentMonth()) }
            .create()

        fun pick(month: Int) {
            onPick(String.format(java.util.Locale.US, "%04d-%02d", year, month))
            dialog.dismiss()
        }

        render = {
            yearLabel.text = "$year 年"
            prev.alpha = if (year > MIN_YEAR) 1f else 0.32f
            next.alpha = if (year < nowYear) 1f else 0.32f
            grid.removeAllViews()
            for (rowIndex in 0 until 3) {
                val row = Ui.row(context).apply {
                    setPadding(0, Ui.dp(context, 3f), 0, Ui.dp(context, 3f))
                }
                for (column in 0 until 4) {
                    val month = rowIndex * 4 + column + 1
                    val enabled = year < nowYear || month <= nowMonth
                    val highlighted = year == selectedYear && month == selectedMonth
                    val cell = Ui.text(
                        context,
                        "${month}月",
                        13f,
                        if (highlighted) R.color.white else R.color.brand_blue,
                        bold = highlighted,
                    ).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, Ui.dp(context, 10f), 0, Ui.dp(context, 10f))
                        setBackgroundResource(
                            if (highlighted) R.drawable.bg_chip_selected else R.drawable.bg_chip,
                        )
                        alpha = if (enabled) 1f else 0.3f
                        isClickable = enabled
                        isFocusable = enabled
                        if (enabled) {
                            foreground = Ui.selectableForeground(context)
                            setOnClickListener { pick(month) }
                        }
                    }
                    row.addView(
                        cell,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (column > 0) marginStart = Ui.dp(context, 6f)
                        },
                    )
                }
                grid.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

        render()
        dialog.show()
    }
}
