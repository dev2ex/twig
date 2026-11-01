package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.twig.app.MainActivity
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class, shadows = [PaneDisplayModeTest.StorageEnvironment::class])
class PaneDisplayModeTest {
    // Robolectric 4.14 does not implement this API; permit the real browser to initialize.
    @org.robolectric.annotation.Implements(android.os.Environment::class)
    class StorageEnvironment : org.robolectric.shadows.ShadowEnvironment() {
        companion object {
            @JvmStatic @org.robolectric.annotation.Implementation(minSdk = 30)
            fun isExternalStorageManager() = true
        }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setup() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Prefs.setRememberLocation(app, false)
    }

    private fun measure(activity: AppCompatActivity, dual: Boolean) {
        val layout = activity.findViewById<PaneLayout>(R.id.browser)
        layout.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        layout.layout(0, 0, 800, 1000)
        layout.finishTransition()
        val a = activity.findViewById<View>(R.id.pane_a)
        val b = activity.findViewById<View>(R.id.pane_b)
        val strip = activity.findViewById<View>(if (dual) R.id.strip_mid else R.id.strip_right)
        assertEquals(View.VISIBLE, strip.visibility)
        assertEquals(View.GONE, activity.findViewById<View>(if (dual) R.id.strip_right else R.id.strip_mid).visibility)
        assertEquals(if (dual) (800 - strip.measuredWidth) / 2 else 800 - strip.measuredWidth, a.width)
        assertEquals(a.right + strip.width, b.left)
        assertEquals(if (dual) 800 else 1600 - strip.width, b.right)
        if (dual) assertEquals(0, layout.scrollX)
    }

    private fun verify(activity: AppCompatActivity, dual: Boolean, landscape: Boolean) {
        idle()
        val host = activity as PaneFragment.Host
        val panes = activity.supportFragmentManager.fragments.filterIsInstance<PaneFragment>().sortedBy { it.paneIndex }
        assertEquals(2, panes.size)
        val left = panes[0]
        val right = panes[1]
        host.focusPane(left)
        val dir = XFile("file", app.cacheDir.path, true)
        left.viewModel.currentDir = dir
        left.viewModel.refreshTree()
        right.viewModel.currentDir = dir.copy(canWrite = false)
        right.viewModel.refreshTree()
        idle()
        measure(activity, dual)
        assertEquals(if (activity is MainActivity && landscape) View.GONE else View.VISIBLE,
            activity.findViewById<View>(R.id.toolbar).visibility)
        if (activity !is MainActivity) assertTrue(activity.findViewById<View>(R.id.btn_confirm).isEnabled)
        host.onPaneTouched(right)
        if (dual) assertTrue(host.isPaneActive(right)) else assertTrue(host.isPaneActive(left))
        host.onPaneSwipe(if (dual) 1000f else -1000f)
        assertTrue(host.isPaneActive(right))
        idle()
        measure(activity, dual)
        if (!dual) assertTrue(activity.findViewById<PaneLayout>(R.id.browser).scrollX > 0)
        if (activity !is MainActivity) assertFalse(activity.findViewById<View>(R.id.btn_confirm).isEnabled)
        for (pane in panes) {
            val adapter = ReflectionHelpers.getField<FileAdapter>(pane, "adapter")
            assertEquals(dual || pane === right, ReflectionHelpers.getField<Boolean>(adapter, "directoryCountsActive"))
            val container = activity.findViewById<android.view.ViewGroup>(if (pane === left) R.id.pane_a else R.id.pane_b)
            val hidden = !dual && pane === left
            assertEquals(if (hidden) android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS else android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS,
                container.descendantFocusability)
            assertEquals(if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                container.importantForAccessibility)
        }
        assertEquals(dir, left.viewModel.currentDir)
    }

    private fun verifyHosts(mode: Int, dual: Boolean, landscape: Boolean) {
        Prefs.setPaneLayout(app, mode)
        val controllers: List<ActivityController<out AppCompatActivity>> = listOf(
            Robolectric.buildActivity(MainActivity::class.java),
            Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.dirIntent(app, null)),
            Robolectric.buildActivity(ShareTargetActivity::class.java,
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "test")),
        )
        for (controller in controllers) {
            try {
                controller.setup()
                verify(controller.get(), dual, landscape)
                controller.recreate()
                verify(controller.get(), dual, landscape)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test @Config(qualifiers = "port") fun `portrait dual panes retain toolbar touch focus and both count subscriptions across recreation`() {
        verifyHosts(Prefs.PANE_LAYOUT_DUAL, dual = true, landscape = false)
    }

    @Test @Config(qualifiers = "land") fun `landscape single pane retains navigation controls and swipe switching across recreation`() {
        verifyHosts(Prefs.PANE_LAYOUT_SINGLE, dual = false, landscape = true)
    }

    @Test @Config(qualifiers = "port") fun `picker resume changes layout without replacing panes or losing destination`() {
        val controllers: List<ActivityController<out AppCompatActivity>> = listOf(
            Robolectric.buildActivity(PickerActivity::class.java, PickerActivity.dirIntent(app, null)),
            Robolectric.buildActivity(ShareTargetActivity::class.java,
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "test")),
        )
        for (controller in controllers) {
            Prefs.setPaneLayout(app, Prefs.PANE_LAYOUT_SINGLE)
            try {
                controller.setup()
                idle()
                val activity = controller.get()
                val host = activity as PaneFragment.Host
                val panes = activity.supportFragmentManager.fragments.filterIsInstance<PaneFragment>().sortedBy { it.paneIndex }
                val right = panes[1]
                val destination = XFile("file", app.cacheDir.path, true)
                right.viewModel.currentDir = destination
                right.viewModel.refreshTree()
                host.focusPane(right)
                for (mode in listOf(Prefs.PANE_LAYOUT_DUAL, Prefs.PANE_LAYOUT_SINGLE, Prefs.PANE_LAYOUT_FOLLOW_ORIENTATION)) {
                    controller.pause().stop()
                    Prefs.setPaneLayout(app, mode)
                    controller.start().resume()
                    idle()
                    assertTrue(host.isPaneActive(right))
                    assertEquals(panes, activity.supportFragmentManager.fragments.filterIsInstance<PaneFragment>().sortedBy { it.paneIndex })
                    assertEquals(destination, right.viewModel.currentDir)
                    assertTrue(activity.findViewById<View>(R.id.btn_confirm).isEnabled)
                    measure(activity, mode == Prefs.PANE_LAYOUT_DUAL)
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `layout preference invalidates UI signature and rejects unknown stored modes`() {
        val before = Prefs.uiSignature(app)
        Prefs.setPaneLayout(app, Prefs.PANE_LAYOUT_DUAL)
        assertNotEquals(before, Prefs.uiSignature(app))
        app.getSharedPreferences("twig_prefs", 0).edit().putInt("pane_layout", 999).commit()
        assertEquals(Prefs.PANE_LAYOUT_FOLLOW_ORIENTATION, Prefs.paneLayout(app))
    }
}
