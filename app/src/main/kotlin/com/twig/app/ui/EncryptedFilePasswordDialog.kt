package com.twig.app.ui

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.twig.app.R

/** Shared password entry and optional remembering for encrypted archives and PDFs. */
object EncryptedFilePasswordDialog {
    fun show(
        ctx: Context,
        name: String,
        @StringRes hintRes: Int = R.string.archive_password,
        remember: Boolean = false,
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onConfirm: (password: String, remember: Boolean) -> Unit,
    ): AlertDialog {
        val input = EditText(ctx).apply {
            hint = ctx.getString(hintRes)
            // setSingleLine resets the transformation, so configure it before the password type.
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val save = CheckBox(ctx).apply {
            text = ctx.getString(R.string.archive_save_pw)
            isChecked = remember
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
            addView(save)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.archive_locked, name))
            .setView(box)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.dialog_ok, null)
            .setOnCancelListener { onCancel() }
            .setOnDismissListener {
                input.text.clear()
                onDismiss()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = input.text.toString()
            if (password.isNotEmpty()) {
                val rememberPassword = save.isChecked
                dialog.dismiss()
                onConfirm(password, rememberPassword)
            }
        }
        input.requestFocus()
        return dialog
    }
}
