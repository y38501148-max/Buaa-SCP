package cn.edu.buaa.scp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.edu.buaa.scp.R
import cn.edu.buaa.scp.databinding.ActivityOtherBinding

/**
 * 「其他功能」：从原卡务页拆出来的六项写操作入口。
 *
 * 这里只做导航与说明，具体表单在 [CardOpActivity]。
 */
class OtherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }

        val context = this
        val body = binding.body

        Ui.sectionHeader(body, "校园卡")
        val cardSection = Ui.addCard(body)
        addRow(context, cardSection, CardOp.LOSS)
        Ui.divider(cardSection, 50f)
        addRow(context, cardSection, CardOp.UNLOSS)
        Ui.divider(cardSection, 50f)
        addRow(context, cardSection, CardOp.PASSWORD)
        Ui.divider(cardSection, 50f)
        addRow(context, cardSection, CardOp.QUOTA)

        Ui.sectionHeader(body, "银行卡")
        val bankSection = Ui.addCard(body)
        addRow(context, bankSection, CardOp.BIND_BANK)
        Ui.divider(bankSection, 50f)
        addRow(context, bankSection, CardOp.UNBIND_BANK)

    }

    private fun addRow(context: Context, parent: LinearLayout, op: CardOp) {
        val line = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 10f), 0, Ui.dp(context, 10f))
            minimumHeight = Ui.dp(context, 56f)
            isClickable = true
            isFocusable = true
            foreground = Ui.selectableForeground(context)
            setOnClickListener {
                startActivity(
                    Intent(this@OtherActivity, CardOpActivity::class.java)
                        .putExtra(CardOpActivity.EXTRA_OP, op.name),
                )
            }
        }
        line.addView(
            Ui.iconTile(
                context,
                op.icon,
                if (op.danger) R.color.red else R.color.brand_blue,
                if (op.danger) R.color.brand_red_soft else R.color.brand_blue_soft,
                38,
            ),
        )
        val column = Ui.column(context)
        column.addView(
            Ui.text(
                context,
                op.title,
                14.5f,
                if (op.danger) R.color.red else R.color.text_primary,
                bold = true,
            ),
        )
        column.addView(
            Ui.text(context, op.subtitle, 11f, R.color.text_hint),
            Ui.rowParams(context, 2f),
        )
        line.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ui.dp(context, 12f)
            },
        )
        line.addView(Ui.chevron(context))
        parent.addView(line)
    }

    private fun applyEdgeToEdgeInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            Ui.applyToolbarTopInset(binding.toolbar, bars.top)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }
}
