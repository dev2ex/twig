package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Secrets
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [WebsiteCustomizationTest.AuthHandlerShadow::class])
class WebsiteLockTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var site: Website
    private var page: ActivityController<WebsiteActivity>? = null
    private val activity get() = page!!.get()
    private val web get() = activity.findViewById<WebView>(R.id.web)
    private val cover get() = activity.window.decorView.findViewWithTag<View>("website_lock_cover")

    private object MemoryWrapper : Secrets.Wrapper {
        private var key: ByteArray? = null
        override fun available() = true
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "memory" }
        override fun unwrap(blob: String) = key?.copyOf()
    }

    @Before fun setup() {
        Secrets.wrapper = MemoryWrapper
        Secrets.reset(app)
        WebsiteStore.preferences(app).edit().clear().commit()
        site = Website(name = "Private", url = "https://example.com", allowBackgroundRunning = true,
            basicAuthUsername = "alice", basicAuthPassword = "private-password", blockScreenshots = false)
        WebsiteStore.save(app, site)
        Secrets.enableMasterPassword(app, "master-password".toCharArray())
        CookieManager.getInstance().setCookie(site.url, "session=existing-login")
        ShadowDialog.reset()
    }

    @After fun cleanup() {
        page?.pause()?.stop()?.destroy()
        Secrets.reset(app)
        Secrets.wrapper = Secrets.AndroidKeystore
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    private fun open() {
        page = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, site.id)).setup()
    }

    @Test fun `system search waits for unlock and cancellation launches nothing`() {
        Secrets.lock()
        val request = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, "private query")
        val controller = Robolectric.buildActivity(WebSearchActivity::class.java, request).create().start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    private fun unlock(screenshotsBlocked: Boolean = false) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        fun input(view: View): EditText? {
            if (view is EditText) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        input(dialog.window!!.decorView)!!.setText("master-password")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((Secrets.locked(app) || cover != null) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertFalse(Secrets.locked(app))
        assertNull(cover)
        assertEquals(screenshotsBlocked,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun `global updates wait until a locked website is unlocked`() {
        open()
        val agent = web.settings.userAgentString
        Secrets.lock()
        page!!.pause().stop()
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", userAgent = "UpdatedGlobalAgent"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(agent, web.settings.userAgentString)
        page!!.start().resume()
        assertNotNull(cover)
        assertEquals(agent, web.settings.userAgentString)
        unlock()
        assertEquals("UpdatedGlobalAgent", web.settings.userAgentString)
        assertEquals(listOf(site), WebsiteStore.all(app))
    }

    @Test fun `launcher relay forwards no saved action while locked or after cancelling unlock`() {
        val relay = LauncherShortcutActivity.save(app, WebsiteActivity.intent(app, site.id))
        Secrets.lock()
        val controller = Robolectric.buildActivity(LauncherShortcutActivity::class.java, relay).create()
        try {
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test fun `external browser request waits for unlock before loading a page`() {
        Secrets.lock()
        page = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.browserIntent(app, "https://external.example/path")).setup()
        assertNull(shadowOf(web).lastLoadedUrl)
        assertNotNull(cover)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        unlock()
        assertEquals("https://external.example/path", shadowOf(web).lastLoadedUrl)
        assertEquals(listOf(site), WebsiteStore.all(app))
    }

    @Test fun `locked shortcut loads nothing until unlock then authenticates with decrypted Basic credentials`() {
        Secrets.lock()
        open()
        assertNull(shadowOf(web).lastLoadedUrl)
        assertNotNull(cover)
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(WebsiteBackground.entries().isEmpty())
        page!!.newIntent(WebsiteActivity.intent(app, site.id))
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        unlock()
        assertEquals(site.url, shadowOf(web).lastLoadedUrl)
        assertTrue(WebsiteBackground.entries().any { it.id == site.id })
        val handler = ReflectionHelpers.callConstructor(HttpAuthHandler::class.java)
        web.webViewClient.onReceivedHttpAuthRequest(web, handler, "example.com", "Private")
        assertEquals("alice" to "private-password",
            Shadow.extract<WebsiteCustomizationTest.AuthHandlerShadow>(handler).credentials)
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }

    @Test fun `recents unlock keeps the same authenticated page and background session without reloading`() {
        open()
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        val original = web
        web.loadUrl("https://example.com/player")
        page!!.pause().stop()
        Secrets.lock()
        page!!.start().resume().visible()
        assertNotNull(cover)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(shadowOf(web).wasOnPauseCalled())
        assertTrue(WebsiteBackground.entries().any { it.id == site.id })
        unlock()
        assertSame(original, web)
        assertEquals("https://example.com/player", shadowOf(web).lastLoadedUrl)
        assertEquals(0, shadowOf(web).reloadInvocations)
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }

    @Test fun `reused shortcut defers edited Basic credentials until unlock`() {
        open()
        web.loadUrl("https://example.com/private")
        WebsiteStore.save(app, site.copy(basicAuthPassword = "new-password"))
        page!!.pause().stop()
        Secrets.lock()
        page!!.newIntent(WebsiteActivity.intent(app, site.id)).start().resume().visible()
        assertNotNull(cover)
        assertEquals("https://example.com/private", shadowOf(web).lastLoadedUrl)
        unlock()
        val handler = ReflectionHelpers.callConstructor(HttpAuthHandler::class.java)
        web.webViewClient.onReceivedHttpAuthRequest(web, handler, "example.com", "Private")
        assertEquals("alice" to "new-password",
            Shadow.extract<WebsiteCustomizationTest.AuthHandlerShadow>(handler).credentials)
    }

    @Test fun `screenshot protection option survives unlock and refreshes when returning to the website`() {
        WebsiteStore.save(app, site.copy(blockScreenshots = true))
        Secrets.lock()
        open()
        unlock(screenshotsBlocked = true)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        page!!.pause().stop()
        WebsiteStore.save(app, site.copy(blockScreenshots = false))
        page!!.start().resume().visible()
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        page!!.pause().stop()
        WebsiteStore.save(app, site.copy(blockScreenshots = true))
        page!!.start().resume().visible()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun `website screenshot protection also works without a master password`() {
        WebsiteStore.save(app, site.copy(blockScreenshots = true))
        assertTrue(Secrets.disableMasterPassword(app, "master-password".toCharArray()))
        open()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun `screenshot choice defaults off with a master password and editor saves an override`() {
        site = site.copy(blockScreenshots = false)
        WebsiteStore.save(app, site)
        open()
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        WebsiteDialogs.edit(activity, site)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val option = dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_block_screenshots)
        assertFalse(option.isChecked)
        option.performClick()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(true, WebsiteStore.all(app).single().blockScreenshots)
        val exported = WebsiteStore.exportJson(app)
        WebsiteStore.importJson(app, exported)
        assertEquals(true, WebsiteStore.all(app).single().blockScreenshots)
        page!!.pause().stop().start().resume().visible()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun `screenshot choice and editor default off without a master password`() {
        assertTrue(Secrets.disableMasterPassword(app, "master-password".toCharArray()))
        site = site.copy(blockScreenshots = false)
        WebsiteStore.save(app, site)
        open()
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        WebsiteDialogs.edit(activity, site)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_block_screenshots).isChecked)
        dialog.dismiss()
        WebsiteDialogs.edit(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val adding = ShadowDialog.getLatestDialog() as AlertDialog
        assertFalse(adding.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_block_screenshots).isChecked)
        adding.dismiss()
    }

    @Test fun `cancelling unlock closes a cold website without loading or deleting its login cookie`() {
        Secrets.lock()
        open()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(web).lastLoadedUrl)
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }
}
