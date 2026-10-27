package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompressionFormatMemoryTest {
    @Test fun `format survives cancellation and activity recreation without remembering move or password`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Prefs.setRememberLocation(app, false)
        val folder = File(app.cacheDir, "compress-format-test").apply { mkdirs() }
        val source = File(folder, "report.txt").apply { writeText("content") }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        fun open(): AlertDialog {
            val manager = controller.get().supportFragmentManager
            val pane = manager.findFragmentByTag("pane0") as PaneFragment
            val other = manager.findFragmentByTag("pane1") as PaneFragment
            ReflectionHelpers.setField(other.viewModel, "currentDir", XFile("file", folder.path, true))
            ReflectionHelpers.callInstanceMethod<Unit>(pane, "performCompress",
                ReflectionHelpers.ClassParameter.from(List::class.java,
                    listOf(XFile("file", source.path, false, size = source.length()))),
                ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, true))
            return ShadowDialog.getLatestDialog() as AlertDialog
        }
        try {
            val initial = open()
            assertEquals(R.id.rb_zip, initial.findViewById<RadioGroup>(R.id.rg_format)!!.checkedRadioButtonId)
            val name = initial.findViewById<EditText>(R.id.et_name)!!
            name.setText("custom.title.zip")
            initial.findViewById<RadioGroup>(R.id.rg_format)!!.check(R.id.rb_7z)
            assertEquals("custom.title.7z", name.text.toString())
            initial.findViewById<CheckBox>(R.id.cb_move)!!.isChecked = true
            initial.findViewById<EditText>(R.id.et_password)!!.setText("not remembered")
            initial.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            controller.recreate()
            val reopened = open()
            assertEquals(R.id.rb_7z, reopened.findViewById<RadioGroup>(R.id.rg_format)!!.checkedRadioButtonId)
            assertEquals("report.7z", reopened.findViewById<EditText>(R.id.et_name)!!.text.toString())
            assertFalse(reopened.findViewById<CheckBox>(R.id.cb_move)!!.isChecked)
            assertEquals("", reopened.findViewById<EditText>(R.id.et_password)!!.text.toString())
            reopened.findViewById<RadioGroup>(R.id.rg_format)!!.check(R.id.rb_zip)
            reopened.dismiss()
            val zipAgain = open()
            assertEquals(R.id.rb_zip, zipAgain.findViewById<RadioGroup>(R.id.rg_format)!!.checkedRadioButtonId)
            assertEquals("report.zip", zipAgain.findViewById<EditText>(R.id.et_name)!!.text.toString())
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
            folder.deleteRecursively()
        }
    }
}
