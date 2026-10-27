package com.twig.app.ui

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class NameInputDialogTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(state)
        }
    }

    private fun replaceSelection(name: String, selectStem: Boolean, expected: String) {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val input = EditText(host.get()).apply { setText(name) }
        var saved: String? = null
        val dialog = AlertDialog.Builder(host.get()).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> saved = input.text.toString() }
            .create()
        try {
            dialog.showNameInput(input, selectStem)
            assertTrue(input.hasFocus())
            assertEquals(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
                dialog.window!!.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
            input.text.replace(input.selectionStart, input.selectionEnd, "renamed")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expected, saved)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }

    @Test fun `typing replaces file stems while preserving only a real final extension`() {
        replaceSelection("report.txt", true, "renamed.txt")
        replaceSelection("report.final.txt", true, "renamed.txt")
        replaceSelection(".settings.json", true, "renamed.json")
        replaceSelection(".nomedia", true, "renamed")
        replaceSelection("README", true, "renamed")
        replaceSelection("name.", true, "renamed")
    }

    @Test fun `directory and label prompts replace the entire name including dots`() {
        replaceSelection("folder.v2", false, "renamed")
        replaceSelection("My.playlist", false, "renamed")
        replaceSelection("", false, "renamed")
    }
}
