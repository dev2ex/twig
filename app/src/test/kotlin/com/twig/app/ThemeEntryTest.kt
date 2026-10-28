package com.twig.app

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.secure.Secrets
import com.twig.app.ui.LauncherShortcutActivity
import com.twig.app.ui.RunCommandActivity
import com.twig.app.ui.ViewIntentActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Exercise manifest themes and entry guards, rather than a substitute relay Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(application = TwigApp::class, sdk = [24, 28, 34], qualifiers = "notnight")
class ThemeEntryTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private object MemoryWrapper : Secrets.Wrapper {
        private var key: ByteArray? = null
        override fun available() = true
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "memory" }
        override fun unwrap(blob: String) = key?.copyOf()
    }

    @Before fun setup() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("twig-theme-test-device")
        shadowOf(app).grantPermissions("com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // As on a cold start, load a saved OLED choice before constructing any Activity.
        app.unregisterActivityLifecycleCallbacks(AppTheme)
        app.getSharedPreferences("twig_prefs", 0).edit().putInt("theme_mode", AppTheme.MODE_OLED).commit()
        AppTheme.install(app)
        Secrets.wrapper = MemoryWrapper
        Secrets.reset(app)
        Secrets.enc(app, "initialize")
        Secrets.enableMasterPassword(app, "theme-test-password".toCharArray())
        Secrets.lock()
        ShadowDialog.reset()
    }

    @After fun cleanup() {
        Secrets.reset(app)
        Secrets.wrapper = Secrets.AndroidKeystore
    }

    @Test fun `main entry creates its black window before asking for unlock`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(Color.BLACK, (activity.window.decorView.background as ColorDrawable).color)
            assertEquals(Color.BLACK, activity.window.statusBarColor)
            assertEquals(Color.BLACK, activity.getColor(R.color.surface))
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `transparent entry points retain transparency and can show OLED unlock dialogs`() {
        val file = File(Environment.getExternalStorageDirectory(), "theme-entry.txt").apply {
            parentFile!!.mkdirs()
            writeText("Theme entry test")
        }
        val entries = listOf(
            ViewIntentActivity::class.java to Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/plain"),
            RunCommandActivity::class.java to Intent(),
            LauncherShortcutActivity::class.java to Intent(),
        )
        for ((entry, intent) in entries) {
            val controller = Robolectric.buildActivity(entry, intent).setup()
            try {
                val activity = controller.get()
                assertFalse("${entry.simpleName} must wait for unlock", activity.isFinishing)
                assertEquals(Color.TRANSPARENT, (activity.window.decorView.background as ColorDrawable).color)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertTrue(dialog.isShowing)
                assertEquals(Color.BLACK, dialog.context.getColor(R.color.surface))
                val colors = dialog.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackgroundFloating))
                try { assertEquals(Color.BLACK, colors.getColor(0, Color.RED)) }
                finally { colors.recycle() }
            } finally {
                ShadowDialog.getLatestDialog()?.dismiss()
                controller.pause().stop().destroy()
            }
        }
    }
}
