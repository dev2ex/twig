package com.twig.app.ui

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteOrientation
import com.twig.app.WebsiteStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteOrientationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    @Test fun `old and unknown orientation values follow system without losing bookmarks`() {
        WebsiteStore.preferences(app).edit().putString("list", """[
            {"id":"old","name":"Old","url":"https://example.com"},
            {"id":"future","name":"Future","url":"https://example.org","orientation":"future"}
        ]""").commit()
        val sites = WebsiteStore.all(app)
        assertEquals(2, sites.size)
        assertTrue(sites.all { it.orientation == WebsiteOrientation.SYSTEM })
        for (mode in WebsiteOrientation.entries) {
            val edited = sites[0].copy(orientation = mode)
            WebsiteStore.save(app, edited)
            assertEquals(edited, WebsiteStore.all(app)[0])
            assertEquals(sites[1], WebsiteStore.all(app)[1])
        }
    }

    @Test fun `editing offers five mutually exclusive choices and remembers the selection`() {
        val site = Website(name = "Example", url = "https://example.com", orientation = WebsiteOrientation.LOCKED)
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        try {
            WebsiteDialogs.edit(controller.get(), site)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val group = dialog.window!!.decorView.findViewWithTag<RadioGroup>(R.string.website_orientation)
            assertEquals(5, group.childCount)
            assertEquals(WebsiteOrientation.LOCKED, group.findViewById<RadioButton>(group.checkedRadioButtonId).tag)
            for (mode in WebsiteOrientation.entries) {
                group.findViewWithTag<RadioButton>(mode).performClick()
                assertEquals(mode, group.findViewById<RadioButton>(group.checkedRadioButtonId).tag)
                assertEquals(1, (0 until group.childCount).count { (group.getChildAt(it) as RadioButton).isChecked })
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(site.copy(orientation = WebsiteOrientation.PORTRAIT, blockScreenshots = false), WebsiteStore.all(app).single())
            WebsiteDialogs.edit(controller.get(), WebsiteStore.all(app).single())
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            val reopenedGroup = reopened.window!!.decorView.findViewWithTag<RadioGroup>(R.string.website_orientation)
            assertEquals(WebsiteOrientation.PORTRAIT,
                reopenedGroup.findViewById<RadioButton>(reopenedGroup.checkedRadioButtonId).tag)
            reopened.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `orientation choices use two columns with wrapping labels and native mutual exclusion`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        try {
            WebsiteDialogs.edit(controller.get(), site)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val group = dialog.window!!.decorView.findViewWithTag<RadioGroup>(R.string.website_orientation)
            val density = app.resources.displayMetrics.density
            val first = group.getChildAt(0) as RadioButton
            first.text = "Follow the system display orientation setting"
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                group.layoutDirection = direction
                group.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                group.layout(0, 0, group.measuredWidth, group.measuredHeight)
                val second = group.getChildAt(1)
                val third = group.getChildAt(2)
                val last = group.getChildAt(4) as RadioButton
                assertEquals(first.top, second.top)
                assertEquals(first.bottom, second.bottom)
                assertEquals(first.bottom, third.top)
                assertEquals(first.left, last.left)
                assertEquals(group.height, last.bottom)
                if (direction == View.LAYOUT_DIRECTION_LTR) assertTrue(first.right <= second.left)
                else assertTrue(second.right <= first.left)
                for (index in 0 until group.childCount) {
                    val button = group.getChildAt(index) as RadioButton
                    assertTrue(button.height >= (48 * density).toInt())
                    button.performClick()
                    assertEquals(1, (0 until group.childCount).count { (group.getChildAt(it) as RadioButton).isChecked })
                }
            }
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `adding a website defaults to following the system`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        try {
            WebsiteDialogs.edit(controller.get())
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val group = dialog.window!!.decorView.findViewWithTag<RadioGroup>(R.string.website_orientation)
            assertEquals(WebsiteOrientation.SYSTEM, group.findViewById<View>(group.checkedRadioButtonId).tag)
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `orientation edits update the native policy without reloading the live page`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, activity.requestedOrientation)
            val web = activity.findViewById<WebView>(R.id.web)
            web.loadUrl("https://example.com/draft")
            web.scrollTo(0, 321)
            val policies = listOf(
                WebsiteOrientation.FREE to ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                WebsiteOrientation.LOCKED to ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                WebsiteOrientation.LANDSCAPE to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                WebsiteOrientation.PORTRAIT to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                WebsiteOrientation.SYSTEM to ActivityInfo.SCREEN_ORIENTATION_USER,
            )
            for ((mode, expected) in policies) {
                WebsiteStore.save(app, site.copy(orientation = mode))
                controller.newIntent(intent)
                assertEquals(expected, activity.requestedOrientation)
                assertSame(web, activity.findViewById(R.id.web))
                assertEquals("https://example.com/draft", shadowOf(web).lastLoadedUrl)
                assertEquals(0, shadowOf(web).reloadInvocations)
                assertEquals(321, web.scrollY)
            }
        } finally { controller.pause().stop().destroy() }
    }
}
