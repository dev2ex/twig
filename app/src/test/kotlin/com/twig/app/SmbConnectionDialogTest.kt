package com.twig.app

import android.app.Application
import android.content.DialogInterface
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmbConnectionDialogTest {
    @Test fun `blank SMB credentials stay anonymous while explicit guest accounts are preserved`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences(ConnectionStore.FILE, 0).edit().clear().commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val activity = controller.get()
            val show = MainActivity::class.java.getDeclaredMethod("showSmbDialog", SavedConnection::class.java)
                .apply { isAccessible = true }
            fun dialog(edit: SavedConnection?): AlertDialog {
                show.invoke(activity, edit)
                return ShadowDialog.getLatestDialog() as AlertDialog
            }
            val first = dialog(null)
            assertEquals("", first.findViewById<EditText>(R.id.et_user)!!.text.toString())
            first.findViewById<EditText>(R.id.et_host)!!.setText("smb-dialog.test")
            first.findViewById<EditText>(R.id.et_share)!!.setText("Pub")
            first.findViewById<EditText>(R.id.et_user)!!.setText("  ")
            first.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val anonymous = ConnectionStore.all(app).single()
            assertEquals("", anonymous.user)
            assertEquals("", anonymous.password)

            ConnectionStore.replaceAll(app, listOf(anonymous.copy(user = "guest")))
            val guest = ConnectionStore.all(app).single()
            val second = dialog(guest)
            assertEquals("guest", second.findViewById<EditText>(R.id.et_user)!!.text.toString())
            second.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("guest", ConnectionStore.all(app).single().user)
        } finally {
            controller.pause().stop().destroy()
            app.getSharedPreferences(ConnectionStore.FILE, 0).edit().clear().commit()
        }
    }
}
