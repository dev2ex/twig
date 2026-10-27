package com.twig.app.ui

import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/** Name prompts are ready to type into; file prompts keep the last extension intact. */
internal fun AlertDialog.showNameInput(input: EditText, selectStem: Boolean = false): AlertDialog {
    input.requestFocus()
    window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    show()
    val name = input.text.toString()
    val end = if (selectStem) {
        name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex } ?: name.length
    } else name.length
    input.setSelection(0, end)
    return this
}
