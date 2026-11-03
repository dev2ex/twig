package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
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
class WebsiteBrowserTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    @Test fun `search requests share one document identity and load new queries in the existing webview`() {
        val first = WebsiteActivity.searchIntent(app, "https://example.com/?q=first")
        val next = WebsiteActivity.searchIntent(app, "https://other.example/?q=second")
        assertTrue(first.filterEquals(next))
        assertFalse(first.filterEquals(WebsiteActivity.browserIntent(app, "https://example.com/?q=first")))
        assertFalse(first.filterEquals(WebsiteActivity.intent(app, "saved")))
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, first).setup()
        try {
            val activity = controller.get()
            val web = activity.findViewById<WebView>(R.id.web)
            assertEquals("https://example.com/?q=first", shadowOf(web).lastLoadedUrl)
            controller.newIntent(next)
            assertSame(web, activity.findViewById<WebView>(R.id.web))
            assertEquals("https://other.example/?q=second", shadowOf(web).lastLoadedUrl)
            web.loadUrl("https://other.example/article")
            controller.newIntent(next)
            assertSame(web, activity.findViewById<WebView>(R.id.web))
            assertEquals("https://other.example/?q=second", shadowOf(web).lastLoadedUrl)
            assertTrue(WebsiteStore.all(app).isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `browser intent resolves to public relay and forwards only a validated web address`() {
        val address = "https://example.com/path?q=1#section"
        val incoming = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra("headers", "Secret: caller-data")
        val resolved = app.packageManager.resolveActivity(incoming, 0)!!.activityInfo
        assertEquals(WebsiteLinkActivity::class.java.name, resolved.name)
        assertTrue(resolved.exported)
        val relay = Robolectric.buildActivity(WebsiteLinkActivity::class.java, incoming).create()
        val forwarded = shadowOf(relay.get()).nextStartedActivity
        assertEquals(address, forwarded.dataString)
        assertEquals(WebsiteActivity::class.java.name, forwarded.component!!.className)
        assertNull(forwarded.extras)
        assertTrue(relay.get().isFinishing)
        relay.destroy()
        for (value in listOf("twig://website/private-id", "file:///private", "javascript:alert(1)",
            "https://user:password@example.com", "https://example.com:99999")) {
            val invalid = Robolectric.buildActivity(WebsiteLinkActivity::class.java,
                Intent(Intent.ACTION_VIEW, Uri.parse(value))).create()
            assertNull(value, shadowOf(invalid.get()).nextStartedActivity)
            assertTrue(invalid.get().isFinishing)
            invalid.destroy()
        }
    }

    @Test fun `temporary browser uses defaults keeps live page on relaunch and saves only on request`() {
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", userAgent = "DefaultAgent",
            script = "window.custom = true", style = "body { color: red; }",
            blockScreenshots = true, doubleTapToolbar = false))
        val intent = WebsiteActivity.browserIntent(app, "https://example.com/path")
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        assertFalse(activity.isFinishing)
        assertFalse((web as WebsiteWebView).doubleTapToolbar)
        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals("https://example.com/path", shadowOf(web).lastLoadedUrl)
        assertEquals("DefaultAgent", web.settings.userAgentString)
        assertTrue(WebsiteStore.all(app).isEmpty())
        web.loadUrl("https://example.com/draft")
        controller.newIntent(intent)
        assertSame(web, activity.findViewById<WebView>(R.id.web))
        assertEquals("https://example.com/draft", shadowOf(web).lastLoadedUrl)
        assertEquals(0, shadowOf(web).reloadInvocations)
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val item = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
            .first { it.title == activity.getString(R.string.website_add) }
        (item as androidx.appcompat.view.menu.MenuItemImpl).invoke()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val saved = WebsiteStore.all(app).single()
        assertEquals("https://example.com/draft", saved.url)
        assertEquals("", saved.userAgent)
        assertEquals("", saved.script)
        assertEquals("window.custom = true", WebsiteStore.effective(app, saved).script)
        assertFalse(saved.id.startsWith("browser:"))
        assertTrue(saved.blockScreenshots)
        assertFalse(saved.doubleTapToolbar)
        controller.pause().stop().destroy()
    }
}
