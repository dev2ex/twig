package com.twig.app

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 34], qualifiers = "night")
class AppThemeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    class Screen : TwigActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(savedInstanceState)
            setContentView(LayoutInflater.from(this).inflate(R.layout.view_panes, null))
        }
    }

    @Before fun setup() {
        // Exercise AppCompat's device path, with isolated Activity configuration contexts.
        org.robolectric.shadows.ShadowBuild.setFingerprint("twig-theme-test-device")
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        AppTheme.install(app)
    }

    @After fun cleanup() {
        app.unregisterActivityLifecycleCallbacks(AppTheme)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @Test fun `OLED cold start resolves black colors in code XML backgrounds and dialogs`() {
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        val activity = controller.get()
        for (color in listOf(R.color.bg, R.color.surface, R.color.bar, R.color.toolbar, R.color.path_bar)) {
            assertEquals(Color.BLACK, ContextCompat.getColor(activity, color))
        }
        assertEquals(Color.BLACK, (activity.findViewById<android.view.View>(R.id.panes).background as ColorDrawable).color)
        assertNotEquals(Color.BLACK, activity.getColor(R.color.path_bar_active))
        assertNotEquals(Color.BLACK, activity.getColor(R.color.selected))
        assertEquals(Color.BLACK, (activity.window.decorView.background as ColorDrawable).color)
        assertEquals(Color.BLACK, activity.window.statusBarColor)
        val dialog = AlertDialog.Builder(activity).setMessage("OLED").create()
        dialog.show()
        val background = android.util.TypedValue()
        dialog.context.theme.resolveAttribute(android.R.attr.colorBackgroundFloating, background, true)
        assertEquals(Color.BLACK, background.data)
        assertEquals(Color.BLACK, dialog.context.getColor(R.color.surface))
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun `switching back to dark restores the existing gray palette`() {
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_YES)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        assertEquals(Color.rgb(30, 30, 30), controller.get().getColor(R.color.surface))
        assertEquals(Color.rgb(18, 18, 18), controller.get().getColor(R.color.bg))
        controller.pause().stop().destroy()
    }

    @Test fun `dark and OLED switches refresh existing screens in both directions`() {
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_YES)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        var previous = controller.get()
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.BLACK, controller.get().getColor(R.color.surface))
        previous = controller.get()
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_YES)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.rgb(30, 30, 30), controller.get().getColor(R.color.surface))
        controller.pause().stop().destroy()
    }

    @Test fun `system night mode and OLED switches refresh existing screens`() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        var previous = controller.get()
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.BLACK, controller.get().getColor(R.color.surface))
        previous = controller.get()
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.rgb(30, 30, 30), controller.get().getColor(R.color.surface))
        controller.pause().stop().destroy()
    }

    @Test fun `row background supports framework inflation before a theme is supplied`() {
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        val activity = controller.get()
        // Framework drawable loading may inflate with null, then apply the theme.
        val background = activity.resources.getDrawable(R.drawable.row_bg, null)
        background.applyTheme(activity.theme)
        assertEquals(Color.BLACK, (background.current as GradientDrawable).color!!.defaultColor)
        background.state = intArrayOf(android.R.attr.state_activated)
        assertEquals(activity.getColor(R.color.selected), (background.current as ColorDrawable).color)
        controller.pause().stop().destroy()
    }

    @Test fun `file list and grid rows inflate with OLED backgrounds`() {
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        val activity = controller.get()
        for (layout in listOf(R.layout.item_file, R.layout.item_thumb_cell)) {
            val row = LayoutInflater.from(activity).inflate(layout, null)
            assertEquals(Color.BLACK, (row.background.current as GradientDrawable).color!!.defaultColor)
        }
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `system light switches to OLED and back through actual recreation`() {
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        assertEquals(Color.WHITE, controller.get().getColor(R.color.surface))
        var previous = controller.get()
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.BLACK, controller.get().getColor(R.color.surface))
        assertEquals(Color.rgb(230, 230, 230), controller.get().getColor(R.color.text_primary))
        assertEquals(Color.BLACK, (controller.get().window.decorView.background as ColorDrawable).color)
        previous = controller.get()
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.WHITE, controller.get().getColor(R.color.surface))
        assertEquals(Color.rgb(33, 33, 33), controller.get().getColor(R.color.text_primary))
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [24, 28, 34], qualifiers = "notnight")
    fun `saved OLED choice is loaded at startup before the first window is created`() {
        // Persist the preference before running the same startup hook used by TwigApp.
        app.unregisterActivityLifecycleCallbacks(AppTheme)
        app.getSharedPreferences("twig_prefs", 0).edit().putInt("theme_mode", AppTheme.MODE_OLED).commit()
        AppTheme.install(app)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        assertEquals(Color.BLACK, (controller.get().window.decorView.background as ColorDrawable).color)
        assertEquals(Color.BLACK, controller.get().window.statusBarColor)
        assertEquals(Color.BLACK, controller.get().getColor(R.color.surface))
        assertEquals(Color.rgb(230, 230, 230), controller.get().getColor(R.color.text_primary))
        controller.pause().stop().destroy()
    }

    @Test fun `OLED switches to explicit light even while the system is dark`() {
        Prefs.setThemeMode(app, AppTheme.MODE_OLED)
        val controller = Robolectric.buildActivity(Screen::class.java).setup()
        val previous = controller.get()
        Prefs.setThemeMode(app, AppCompatDelegate.MODE_NIGHT_NO)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, controller.get())
        assertEquals(Color.WHITE, controller.get().getColor(R.color.surface))
        assertEquals(Color.rgb(33, 33, 33), controller.get().getColor(R.color.text_primary))
        controller.pause().stop().destroy()
    }

}
