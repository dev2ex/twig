package com.twig.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.twig.app.CompareSession
import com.twig.app.Prefs
import com.twig.app.R
import org.json.JSONObject

/**
 * Compare options and exclude-rules dialogs. Hand-written views, no preference library
 * (same convention as [SettingsActivity]).
 */

/** Last-used options; defaults of [CompareOptions] when nothing is stored yet. */
fun loadCompareOptions(ctx: Context): CompareOptions {
    val raw = Prefs.compareOptions(ctx)
    if (raw.isBlank()) return CompareOptions()
    return runCatching { CompareSession.optionsFromJson(JSONObject(raw)) }.getOrDefault(CompareOptions())
}

fun saveCompareOptions(ctx: Context, o: CompareOptions) {
    Prefs.setCompareOptions(ctx, CompareSession.optionsToJson(o).toString())
}

/** Time-tolerance tiers. The last tier "ignore time" = only by size (and content). */
private val TOLERANCES = longArrayOf(0L, 1_000L, 2_000L, 60_000L, Long.MAX_VALUE / 4)

/** Content-compare size cap tiers; 0 = off. */
private val CONTENT_LIMITS = longArrayOf(0L, 256L * 1024, 1L shl 20, 16L shl 20)

private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

private fun spinner(ctx: Context, labels: List<String>, selected: Int): Spinner =
    Spinner(ctx).apply {
        adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        setSelection(selected.coerceIn(0, labels.size - 1))
    }

private fun label(ctx: Context, text: String) = TextView(ctx).apply {
    this.text = text
    textSize = 12f
    setPadding(0, dp(ctx, 10), 0, dp(ctx, 2))
}

fun showCompareOptions(ctx: Context, current: CompareOptions, onApply: (CompareOptions) -> Unit) {
    val tolLabels = listOf(
        ctx.getString(R.string.compare_tol_exact),
        ctx.getString(R.string.compare_tol_1s),
        ctx.getString(R.string.compare_tol_2s),
        ctx.getString(R.string.compare_tol_1m),
        ctx.getString(R.string.compare_tol_ignore),
    )
    val limitLabels = listOf(
        ctx.getString(R.string.compare_content_off),
        "256 KB", "1 MB", "16 MB",
    )
    fun idxOf(arr: LongArray, v: Long) = arr.indexOfFirst { it == v }.let { if (it < 0) 0 else it }

    val spTol = spinner(ctx, tolLabels, idxOf(TOLERANCES, current.timeToleranceMs))
    val cbHour = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_hour_shift)
        isChecked = current.allowHourShift
    }
    val cbCase = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_ignore_case)
        isChecked = current.ignoreCase
    }
    val spLocal = spinner(ctx, limitLabels, idxOf(CONTENT_LIMITS, current.contentLimitLocal))
    val spNet = spinner(ctx, limitLabels, idxOf(CONTENT_LIMITS, current.contentLimitNetwork))
    val cbTimeGate = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_content_time_gate)
        isChecked = current.contentOnlyIfTimeDiffers
    }
    val cbIncremental = CheckBox(ctx).apply {
        text = ctx.getString(R.string.compare_sync_incremental_opt)
        isChecked = current.incrementalSync
    }

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        addView(label(ctx, ctx.getString(R.string.compare_tolerance)))
        addView(spTol)
        addView(cbHour)
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.compare_hour_shift_hint)
            textSize = 11f
            alpha = 0.7f
        })
        addView(cbCase)
        addView(label(ctx, ctx.getString(R.string.compare_content_local)))
        addView(spLocal)
        addView(label(ctx, ctx.getString(R.string.compare_content_net)))
        addView(spNet)
        addView(cbTimeGate)
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.compare_content_time_gate_hint)
            textSize = 11f
            alpha = 0.7f
        })
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.compare_content_hint)
            textSize = 11f
            alpha = 0.7f
        })
        addView(label(ctx, ctx.getString(R.string.compare_sync_section)))
        addView(cbIncremental)
        addView(TextView(ctx).apply {
            text = ctx.getString(R.string.compare_sync_incremental_hint)
            textSize = 11f
            alpha = 0.7f
        })
    }

    AlertDialog.Builder(ctx)
        .setTitle(R.string.compare_options)
        .setView(ScrollView(ctx).apply { addView(box) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val o = current.copy(
                timeToleranceMs = TOLERANCES[spTol.selectedItemPosition],
                allowHourShift = cbHour.isChecked,
                ignoreCase = cbCase.isChecked,
                contentLimitLocal = CONTENT_LIMITS[spLocal.selectedItemPosition],
                contentLimitNetwork = CONTENT_LIMITS[spNet.selectedItemPosition],
                contentOnlyIfTimeDiffers = cbTimeGate.isChecked,
                incrementalSync = cbIncremental.isChecked,
            )
            saveCompareOptions(ctx, o)
            onApply(o)
        }
        .show()
}

/** Common exclude rules, one-tap into the editor — no one wants to type `node_modules` every time. */
private val EXCLUDE_PRESETS = listOf(".git", "node_modules", "build", ".DS_Store", "Thumbs.db", "*.tmp")

fun showExcludeEditor(ctx: Context, current: List<String>, onApply: (List<String>) -> Unit) {
    val input = EditText(ctx).apply {
        setText(current.joinToString("\n"))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        minLines = 5
        setSingleLine(false)
    }
    val hint = TextView(ctx).apply {
        text = ctx.getString(R.string.compare_excludes_hint)
        textSize = 11f
        alpha = 0.7f
        setPadding(0, dp(ctx, 6), 0, 0)
    }
    val presets = TextView(ctx).apply {
        text = ctx.getString(R.string.compare_excludes_presets)
        textSize = 13f
        setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
        setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.action_text))
        setOnClickListener {
            val checked = BooleanArray(EXCLUDE_PRESETS.size)
            AlertDialog.Builder(ctx)
                .setTitle(R.string.compare_excludes_presets)
                .setMultiChoiceItems(EXCLUDE_PRESETS.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val have = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    EXCLUDE_PRESETS.forEachIndexed { i, p -> if (checked[i] && p !in have) have += p }
                    input.setText(have.joinToString("\n"))
                }
                .show()
        }
    }

    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(hint)
        addView(presets)
    }

    AlertDialog.Builder(ctx)
        .setTitle(R.string.compare_excludes)
        .setView(ScrollView(ctx).apply { addView(box) })
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val list = input.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            onApply(list)
        }
        .show()
}
