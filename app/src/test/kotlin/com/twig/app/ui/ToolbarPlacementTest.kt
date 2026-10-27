package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "port", application = Application::class)
class ToolbarPlacementTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        ReflectionHelpers.setStaticField(androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java,
            "_instance", null)
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        Prefs.setRememberLocation(app, false)
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, "com.twig.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 500, 800)
    }

    @Test fun `toolbar stays at top by default and its preference changes the layout signature`() {
        assertFalse(Prefs.toolbarAtBottom(app))
        val before = Prefs.uiSignature(app)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            val toolbar = controller.get().findViewById<Toolbar>(R.id.toolbar)
            val root = toolbar.parent as ViewGroup
            layout(root)
            assertEquals(0, toolbar.top)
            assertTrue(toolbar.bottom <= root.findViewById<View>(R.id.browser).top)
            Prefs.setToolbarAtBottom(app, true)
            assertNotEquals(before, Prefs.uiSignature(app))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `main toolbar sits below clipboard content and pane switching still works`() {
        Prefs.setToolbarAtBottom(app, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            val activity = controller.get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            activity.supportFragmentManager.executePendingTransactions()
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            val root = toolbar.parent as ViewGroup
            val clipboard = root.findViewById<View>(R.id.clip_bar)
            clipboard.visibility = View.VISIBLE
            layout(root)
            assertEquals(root.height, toolbar.bottom)
            assertTrue(clipboard.height > 0)
            assertTrue(clipboard.bottom <= toolbar.top)
            assertTrue(root.findViewById<View>(R.id.browser).bottom <= clipboard.top)
            val switch = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
                .single { it.title == activity.getString(R.string.menu_swap_pane) }
            switch.actionView!!.performClick()
            val right = activity.supportFragmentManager.findFragmentByTag("pane1") as PaneFragment
            assertTrue(activity.isPaneActive(right))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `share picker follows toolbar position without overlapping its save controls`() {
        Prefs.setToolbarAtBottom(app, true)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "shared text")
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, intent)
        try {
            controller.setup()
            val activity = controller.get()
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            val root = toolbar.parent as ViewGroup
            layout(root)
            assertEquals(root.height, toolbar.bottom)
            val confirmBar = activity.findViewById<View>(R.id.btn_confirm).parent as View
            assertTrue(confirmBar.bottom <= toolbar.top)
            toolbar.menu.getItem(0).actionView!!.performClick()
            assertTrue(activity.isPaneActive(activity.supportFragmentManager.findFragmentByTag("share_pane_1") as PaneFragment))
            Prefs.setToolbarAtBottom(app, false)
            controller.pause().resume()
            layout(root)
            assertEquals(0, toolbar.top)
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(qualifiers = "land")
    fun `landscape main browser retains the centered action strip`() {
        Prefs.setToolbarAtBottom(app, true)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            val activity = controller.get()
            assertEquals(View.GONE, activity.findViewById<View>(R.id.toolbar).visibility)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.strip_mid).visibility)
        } finally { controller.pause().stop().destroy() }
    }
}
