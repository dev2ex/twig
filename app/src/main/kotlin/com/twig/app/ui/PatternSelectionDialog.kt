package com.twig.app.ui

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import com.twig.app.Prefs
import com.twig.app.R

internal object PatternSelectionDialog {
    enum class Action {
        SELECT, DESELECT, INVERT;

        fun checked(previous: Boolean): Boolean = when (this) {
            SELECT -> true
            DESELECT -> false
            INVERT -> !previous
        }
    }

    fun show(context: Context, apply: (Action, String?, Prefs.PatternSelectionTypes) -> Unit) {
        val recent = Prefs.selectionPatterns(context)
        val types = Prefs.patternSelectionTypes(context)
        val input = EditText(context).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHint(R.string.search_hint)
        }
        val density = context.resources.displayMetrics.density
        val touchSize = (48 * density).toInt()
        val patternRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (recent.isNotEmpty()) {
                addView(ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
                    setImageResource(R.drawable.ic_history)
                    setColorFilter(input.currentTextColor)
                    contentDescription = context.getString(R.string.pattern_history)
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(R.string.pattern_history)
                            .setItems(recent.toTypedArray()) { _, index ->
                                input.setText(recent[index])
                                input.setSelection(input.length())
                            }
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(touchSize, touchSize))
            }
        }
        val files = CheckBox(context).apply {
            setText(R.string.pattern_files)
            isChecked = types.files
        }
        val folders = CheckBox(context).apply {
            setText(R.string.media_folders)
            isChecked = types.folders
        }
        fun selectedTypes() = Prefs.PatternSelectionTypes(files.isChecked, folders.isChecked)

        val title = TextView(context).apply {
            setText(R.string.strip_pattern)
            setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Title)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        val close = ImageButton(context, null, android.R.attr.borderlessButtonStyle).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(title.currentTextColor)
            contentDescription = context.getString(R.string.dialog_cancel)
        }
        val titleRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative((24 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), 0)
            addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(touchSize, touchSize))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * density).toInt()
            setPaddingRelative(padding, 0, padding, 0)
            addView(patternRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                addView(files, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(folders, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(context)
            .setCustomTitle(titleRow)
            .setView(ScrollView(context).apply { addView(content) })
            .setNeutralButton(R.string.pattern_invert) { _, _ -> apply(Action.INVERT, null, selectedTypes()) }
            .setNegativeButton(R.string.action_unselect) { _, _ -> apply(Action.DESELECT, input.text.toString().trim(), selectedTypes()) }
            .setPositiveButton(R.string.action_select) { _, _ -> apply(Action.SELECT, input.text.toString().trim(), selectedTypes()) }
            .create()
            .showNameInput(input)
        val select = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val deselect = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        val invert = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        fun updateActions() {
            val hasTypes = selectedTypes().any
            val canMatch = hasTypes && input.text.isNotBlank()
            select.isEnabled = canMatch
            deselect.isEnabled = canMatch
            invert.isEnabled = hasTypes
        }
        close.setOnClickListener { dialog.cancel() }
        files.setOnCheckedChangeListener { _, _ -> updateActions() }
        folders.setOnCheckedChangeListener { _, _ -> updateActions() }
        input.doAfterTextChanged { updateActions() }
        updateActions()
    }
}
