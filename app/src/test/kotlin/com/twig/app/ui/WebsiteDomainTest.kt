package com.twig.app.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsitePermission
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
class WebsiteDomainTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val profile = Website(name = "Search", url = "https://search.example/start", applyToDomain = true,
        userAgent = "SearchAgent", script = "window.searchConfig = true;", style = ".search { color: red; }",
        hiddenElements = ".searchAd", blockedRequests = "search-ads.example", pullToRefresh = true,
        blockScreenshots = true)

    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    private fun request(address: String, main: Boolean = true, redirect: Boolean = false, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(address)
        override fun isForMainFrame() = main
        override fun isRedirect() = redirect
        override fun hasGesture() = true
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun `domain sharing is opt in matches exact hosts and direct bookmarks keep their choices`() {
        val owner = Website(name = "Temporary", url = "https://search.example/query")
        WebsiteStore.save(app, profile.copy(applyToDomain = false))
        assertEquals(owner, WebsiteStore.forPage(app, owner, owner.url, temporary = true))
        WebsiteStore.save(app, profile)
        val matched = WebsiteStore.forPage(app, owner, owner.url, temporary = true)
        assertEquals("SearchAgent", matched.userAgent)
        assertEquals(owner.id, matched.id)
        assertEquals(owner.url, matched.url)
        assertFalse(matched.applyToDomain)
        for (url in listOf("https://sub.search.example/", "https://search.example.evil/", "https://other.example/")) {
            assertEquals("", WebsiteStore.forPage(app, owner, url, temporary = true).script)
        }
        assertEquals("SearchAgent", WebsiteStore.forPage(app, owner, "https://SEARCH.EXAMPLE/query", true).userAgent)
        val direct = owner.copy(userAgent = "DirectAgent")
        assertEquals(direct, WebsiteStore.forPage(app, direct, owner.url, temporary = false))
    }

    @Test fun `enabling another profile replaces the same host choice without reordering and backup retains it`() {
        val second = profile.copy(id = "second", name = "Second", url = "https://search.example/other", applyToDomain = false)
        WebsiteStore.save(app, profile)
        WebsiteStore.save(app, second)
        WebsiteStore.save(app, second.copy(applyToDomain = true))
        val saved = WebsiteStore.all(app)
        assertEquals(listOf(profile.id, second.id), saved.map { it.id })
        assertFalse(saved.first().applyToDomain)
        assertTrue(saved.last().applyToDomain)
        val json = WebsiteStore.exportJson(app)
        clear()
        WebsiteStore.importJson(app, json)
        assertEquals(saved, WebsiteStore.all(app))
        WebsiteStore.importJson(app, """[{"id":"old","name":"Old","url":"https://search.example"}]""")
        assertFalse(WebsiteStore.all(app).single().applyToDomain)
    }

    @Test fun `domain matching never widens credential origins permission grants or background sessions`() {
        WebsiteStore.save(app, profile.copy(headers = "X-Token: secret", basicAuthUsername = "alice",
            basicAuthPassword = "secret", allowBackgroundRunning = true,
            microphonePermission = WebsitePermission.ALLOW, cameraPermission = WebsitePermission.ALLOW,
            locationPermission = WebsitePermission.ALLOW))
        val owner = Website(name = "Temporary", url = "https://search.example/query")
        val matching = WebsiteStore.forPage(app, owner, owner.url, true)
        assertEquals("X-Token: secret", matching.headers)
        assertEquals("alice", matching.basicAuthUsername)
        assertFalse(matching.allowBackgroundRunning)
        assertEquals(WebsitePermission.ASK, matching.microphonePermission)
        assertEquals(WebsitePermission.ASK, matching.cameraPermission)
        assertEquals(WebsitePermission.ASK, matching.locationPermission)
        for (url in listOf("http://search.example/query", "https://search.example:8443/query")) {
            val changedOrigin = WebsiteStore.forPage(app, owner, url, true)
            assertEquals(profile.script, changedOrigin.script)
            assertEquals("", changedOrigin.headers)
            assertEquals("", changedOrigin.basicAuthUsername)
            assertEquals("", changedOrigin.basicAuthPassword)
        }
    }

    @Test fun `external and system search pages receive domain settings and global additions without persisting copies`() {
        WebsiteStore.save(app, profile)
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", script = "window.globalConfig = true;",
            style = ".global { display: none; }", blockedRequests = "global-ads.example"))
        val address = "https://search.example/results?q=twig"
        for (intent in listOf(WebsiteActivity.browserIntent(app, address), WebsiteActivity.searchIntent(app, address))) {
            val page = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
            try {
                val activity = page.get()
                val web = activity.findViewById<WebsiteWebView>(R.id.web)
                assertEquals(address, shadowOf(web).lastLoadedUrl)
                assertEquals("SearchAgent", web.settings.userAgentString)
                assertTrue(web.pullToRefresh)
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                web.webViewClient.onPageFinished(web, address)
                val script = shadowOf(web).lastEvaluatedJavascript
                assertTrue(script.contains("window.searchConfig = true;"))
                assertTrue(script.contains("window.globalConfig = true;"))
                for (host in listOf("search-ads.example", "global-ads.example")) {
                    assertNotNull(web.webViewClient.shouldInterceptRequest(web, request("https://$host/ad", main = false)))
                }
                assertEquals(listOf(profile), WebsiteStore.all(app))
            } finally { page.pause().stop().destroy() }
        }
    }

    @Test fun `saved search navigation selects destination profile before GET and resets on foreign redirects and back`() {
        WebsiteStore.save(app, profile)
        val destination = Website(name = "Article", url = "https://article.example/home", applyToDomain = true,
            userAgent = "ArticleAgent", headers = "X-Article: yes", script = "window.articleConfig = true;",
            blockedRequests = "article-ads.example")
        WebsiteStore.save(app, destination)
        val page = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, profile.id)).setup()
        try {
            val web = page.get().findViewById<WebView>(R.id.web)
            val client = web.webViewClient
            assertFalse(client.shouldOverrideUrlLoading(web, request(destination.url, main = false)))
            assertEquals("SearchAgent", web.settings.userAgentString)
            val article = "https://article.example/article"
            assertTrue(client.shouldOverrideUrlLoading(web, request(article)))
            assertEquals("ArticleAgent", web.settings.userAgentString)
            assertEquals(mapOf("X-Article" to "yes"), shadowOf(web).lastAdditionalHttpHeaders)
            client.onPageStarted(web, article, null)
            client.onPageFinished(web, article)
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("window.articleConfig = true;"))
            assertFalse(shadowOf(web).lastEvaluatedJavascript.contains("window.searchConfig = true;"))
            assertNull(client.shouldInterceptRequest(web, request("https://search-ads.example/ad", main = false)))
            assertNotNull(client.shouldInterceptRequest(web, request("https://article-ads.example/ad", main = false)))
            assertTrue(client.shouldOverrideUrlLoading(web, request("https://other.example/redirect", redirect = true)))
            assertTrue(shadowOf(web).lastAdditionalHttpHeaders.isNullOrEmpty())
            web.loadUrl(profile.url)
            client.onPageStarted(web, profile.url, null)
            client.onPageFinished(web, profile.url)
            assertEquals("SearchAgent", web.settings.userAgentString)
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("window.searchConfig = true;"))
        } finally { page.pause().stop().destroy() }
    }

    @Test fun `editing domain profile updates open temporary page and removing it restores defaults`() {
        WebsiteStore.save(app, profile)
        val page = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.browserIntent(app, "https://search.example/query")).setup()
        try {
            val web = page.get().findViewById<WebView>(R.id.web)
            val mobile = WebsiteWebView(app).settings.userAgentString
            WebsiteStore.save(app, profile.copy(userAgent = "UpdatedAgent"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("UpdatedAgent", web.settings.userAgentString)
            assertTrue(shadowOf(web).reloadInvocations > 0)
            WebsiteStore.remove(app, profile.id)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(mobile, web.settings.userAgentString)
            assertNull(web.webViewClient.shouldInterceptRequest(web, request("https://search-ads.example/ad", main = false)))
        } finally { page.pause().stop().destroy() }
    }

    @Test fun `temporary debug edits remain in memory without modifying the shared profile or global CSS`() {
        WebsiteStore.save(app, profile)
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", style = ".global { color: blue; }"))
        val page = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.browserIntent(app, "https://search.example/query")).setup()
        try {
            val activity = page.get()
            val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
            val item = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
                .single { it.title == activity.getString(R.string.website_debug) }
            (item as androidx.appcompat.view.menu.MenuItemImpl).invoke()
            val panel = activity.findViewById<View>(R.id.debug_host)
            panel.findViewWithTag<View>(R.string.website_style).performClick()
            val editor = panel.findViewWithTag<EditText>(R.string.website_debug_css_input)
            assertEquals(profile.style, editor.text.toString())
            editor.setText(".temporary { display: none; }")
            panel.findViewWithTag<View>(R.string.viewer_save).performClick()
            val web = activity.findViewById<WebView>(R.id.web)
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains(".temporary"))
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains(".global"))
            assertEquals(listOf(profile), WebsiteStore.all(app))
            val add = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
                .single { it.title == activity.getString(R.string.website_add) }
            (add as androidx.appcompat.view.menu.MenuItemImpl).invoke()
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val saved = WebsiteStore.all(app).last()
            assertEquals(".temporary { display: none; }", saved.style)
            assertFalse(saved.applyToDomain)
            assertEquals(profile, WebsiteStore.all(app).first())
        } finally { page.pause().stop().destroy() }
    }

    @Test fun `website editor persists domain sharing and defaults never expose it`() {
        val saved = profile.copy(applyToDomain = false)
        WebsiteStore.save(app, saved)
        val page = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, saved.id)).setup()
        try {
            WebsiteDialogs.edit(page.get(), saved)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val option = dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_apply_to_domain)
            assertFalse(option.isChecked)
            option.isChecked = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(WebsiteStore.all(app).single().applyToDomain)
            WebsiteDialogs.defaults(page.get())
            val defaults = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(View.GONE, defaults.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_apply_to_domain).visibility)
            defaults.dismiss()
        } finally { page.pause().stop().destroy() }
    }

    @Test fun `POST navigation applies page customization without changing UA or replaying the in flight request`() {
        WebsiteStore.save(app, profile)
        val page = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.browserIntent(app, "https://other.example/form")).setup()
        try {
            val web = page.get().findViewById<WebView>(R.id.web)
            val mobile = web.settings.userAgentString
            val client = web.webViewClient
            val result = "https://search.example/form-result"
            val previousUrl = shadowOf(web).lastLoadedUrl
            val reloads = shadowOf(web).reloadInvocations
            assertFalse(client.shouldOverrideUrlLoading(web, request(result, method = "POST")))
            client.onPageStarted(web, result, null)
            assertEquals(mobile, web.settings.userAgentString)
            assertEquals(previousUrl, shadowOf(web).lastLoadedUrl)
            assertEquals(reloads, shadowOf(web).reloadInvocations)
            // Chromium owns completion of the original POST; only the next request gets the new UA.
            web.loadUrl(result)
            client.onPageFinished(web, result)
            assertEquals("SearchAgent", web.settings.userAgentString)
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("window.searchConfig = true;"))
        } finally { page.pause().stop().destroy() }
    }
}
