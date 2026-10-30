package com.twig.app.ui

import android.app.Activity
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
class EncryptedFilePasswordDialogTest {
    @Test fun `shared archive and PDF prompt masks text and retains empty submissions`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val ctx = ContextThemeWrapper(host.get(), R.style.Theme_Twig)
            for (hint in listOf(R.string.archive_password, R.string.pdf_password)) {
                var confirmed: Pair<String, Boolean>? = null
                var dismissals = 0
                val dialog = EncryptedFilePasswordDialog.show(ctx, "locked.file", hintRes = hint,
                    onDismiss = { dismissals++ }) { password, remember -> confirmed = password to remember }
                val custom = dialog.findViewById<android.view.ViewGroup>(androidx.appcompat.R.id.custom)!!
                val box = custom.getChildAt(0) as LinearLayout
                val input = box.getChildAt(0) as EditText
                val save = box.getChildAt(1) as CheckBox
                assertEquals(ctx.getString(hint), input.hint.toString())
                assertTrue(input.transformationMethod is PasswordTransformationMethod)
                assertFalse(input.isSaveEnabled)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertNull(confirmed)
                input.setText("private-password")
                assertNotEquals(input.text.toString(), input.transformationMethod.getTransformation(input.text, input).toString())
                save.isChecked = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals("private-password" to true, confirmed)
                assertFalse(dialog.isShowing)
                assertEquals("", input.text.toString())
                assertEquals(1, dismissals)
            }
        } finally { host.pause().stop().destroy() }
    }
}
