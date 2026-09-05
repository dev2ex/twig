package com.twig.app.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TextCodec

/**
 * The "text encoding" pick box: which charsets are checked, and in what order.
 *
 * Hand-written view (this project pulls in no preference library); a row = checkbox +
 * label + up/down arrow. ★ The explanatory note lives inside the **custom view** and
 * not via `setMessage()`: AlertDialog's content panel can only hold one thing, and
 * when you set both message and a list/custom view the message wins and the other
 * never gets attached to the view tree at all (see the `PrivilegedDialogTest` pitfall
 * in CLAUDE.md).
 */
object TextCharsetPicker {

    /**
     * Encoding → language label. The encoding name alone means nothing to most people;
     * the language is what they actually pick by. Anything missing here falls back to
     * "Western European", so keep this in step with [TextCodec.CANDIDATES].
     */
    private val LANG = mapOf(
        "GB18030" to R.string.charset_lang_zh_cn,
        "Big5" to R.string.charset_lang_zh_tw,
        "Big5-HKSCS" to R.string.charset_lang_zh_hk,
        "Shift_JIS" to R.string.charset_lang_ja,
        "EUC-JP" to R.string.charset_lang_ja,
        "ISO-2022-JP" to R.string.charset_lang_ja,
        "EUC-KR" to R.string.charset_lang_ko,
        "windows-874" to R.string.charset_lang_thai,
        "KOI8-R" to R.string.charset_lang_cyrillic,
        "windows-1250" to R.string.charset_lang_central,
        "windows-1251" to R.string.charset_lang_cyrillic,
        "windows-1252" to R.string.charset_lang_western,
        "windows-1253" to R.string.charset_lang_greek,
        "windows-1254" to R.string.charset_lang_turkish,
        "windows-1255" to R.string.charset_lang_hebrew,
        "windows-1256" to R.string.charset_lang_arabic,
        "windows-1257" to R.string.charset_lang_baltic,
        "ISO-8859-2" to R.string.charset_lang_central,
        "ISO-8859-15" to R.string.charset_lang_western,
    )

    /** Settings-row subtitle: what is selected and in what order; when nothing is selected, say outright "Unicode only". */
    fun subtitle(ctx: Context): String {
        val names = Prefs.textCharsets(ctx).filter { it in TextCodec.CANDIDATES }
        return if (names.isEmpty()) ctx.getString(R.string.charsets_none) else names.joinToString(" → ")
    }

    fun show(ctx: Context, onDone: () -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        // Already-selected entries come first in the user's order, the rest follow in default
// order — order is priority, it has to be visible.
        val chosen = Prefs.textCharsets(ctx).filter { it in TextCodec.CANDIDATES }
        val order = ArrayList(chosen + TextCodec.CANDIDATES.filter { it !in chosen })
        val checked = HashSet(chosen)

        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var rebuild: () -> Unit
        rebuild = {
            rows.removeAllViews()
            val lastChecked = order.indexOfLast { it in checked }
            order.forEachIndexed { i, name ->
                rows.addView(row(ctx, dp, name, i, lastChecked, order, checked) { rebuild() })
            }
        }
        rebuild()

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), 0)
            addView(
                TextView(ctx).apply {
                    setText(R.string.charsets_dialog_note)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    setPadding(0, 0, (8 * dp).toInt(), (8 * dp).toInt())
                },
            )
            addView(rows)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_text_charsets)
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.setTextCharsets(ctx, order.filter { it in checked })
                TextCodec.load(ctx) // Take effect immediately: the decoder reads an in-process cache.
                onDone()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun row(
        ctx: Context,
        dp: Float,
        name: String,
        index: Int,
        lastChecked: Int,
        order: MutableList<String>,
        checked: MutableSet<String>,
        refresh: () -> Unit,
    ): View {
        val on = name in checked
        val box = CheckBox(ctx).apply {
            isChecked = on
            setOnClickListener {
                if (isChecked) checked.add(name) else checked.remove(name)
                // The checked block always floats to the top: freshly checked entries move to the
// end of the selected block; freshly unchecked entries fall back to the unselected
// block.
                val sel = order.filter { it in checked }
                val rest = order.filter { it !in checked }
                order.clear()
                order.addAll(sel + rest)
                refresh()
            }
        }
        val label = TextView(ctx).apply {
            text = ctx.getString(R.string.charset_item, name, ctx.getString(LANG[name] ?: R.string.charset_lang_western))
            textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        fun arrow(text: String, desc: Int, enabled: Boolean, move: () -> Unit) = TextView(ctx).apply {
            this.text = text
            contentDescription = ctx.getString(desc)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = (40 * dp).toInt()
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            setTextColor(
                ContextCompat.getColor(ctx, if (enabled) R.color.text_primary else R.color.text_secondary),
            )
            alpha = if (enabled) 1f else 0.3f
            // Unchecked items do not participate in ordering: the order within the unselected
// block has no meaning, and exposing arrows there would suggest "if I move it, it counts".
            visibility = if (on) View.VISIBLE else View.INVISIBLE
            if (enabled) setOnClickListener { move(); refresh() }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(box)
            addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            addView(
                arrow("↑", R.string.charset_move_up, on && index > 0) {
                    order[index] = order.set(index - 1, order[index])
                },
            )
            addView(
                arrow("↓", R.string.charset_move_down, on && index < lastChecked) {
                    order[index] = order.set(index + 1, order[index])
                },
            )
            setOnClickListener { box.performClick() }
        }
    }
}
