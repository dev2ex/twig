package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.SearchEngine
import com.twig.app.WebsiteStore
import com.twig.app.secure.Backup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebSearchTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    @Test fun `defaults retain the homepage and encode selected text as a single query value`() {
        assertEquals("https://start.duckduckgo.com/", SearchEngine.home(app))
        val text = "中文 &x=1 + #?/%\nnext 🐿"
        val result = Uri.parse(SearchEngine.url(app, "  $text  "))
        assertEquals(setOf("q"), result.queryParameterNames)
        assertEquals(text, result.getQueryParameter("q"))
        assertNull(result.fragment)
        assertNull(SearchEngine.url(app, " \n\t"))
    }

    @Test fun `custom templates support path queries and reject unsafe or missing placeholders`() {
        SearchEngine.save(app, "example.com", "https://example.com/find/{query}?lang=zh")
        val url = Uri.parse(SearchEngine.url(app, "a/b & c"))
        assertEquals(listOf("find", "a/b & c"), url.pathSegments)
        assertEquals("zh", url.getQueryParameter("lang"))
        assertEquals("https://example.com", SearchEngine.home(app))
        for (bad in listOf("https://example.com/", "javascript:{query}", "file:///{query}",
            "https://user:password@example.com/?q={query}", "https:///?q={query}",
            "https://example.com/?q={query}&extra={unknown}")) {
            assertFalse(bad, SearchEngine.validTemplate(bad))
        }
        app.getSharedPreferences("twig_prefs", 0).edit()
            .putString("search_home", "javascript:alert(1)")
            .putString("search_template", "file:///{query}").commit()
        assertEquals(SearchEngine.default.home, SearchEngine.home(app))
        assertEquals(SearchEngine.default.template, SearchEngine.template(app))
    }

    @Test fun `search provider survives configuration backup without adding a website`() {
        SearchEngine.save(app, "https://example.com/", "https://example.com/?term={query}")
        val out = ByteArrayOutputStream()
        Backup.export(app, out, null)
        clear()
        Backup.import(app, out.toByteArray(), null)
        assertEquals("https://example.com/", SearchEngine.home(app))
        assertEquals("test", Uri.parse(SearchEngine.url(app, "test")).getQueryParameter("term"))
        assertTrue(WebsiteStore.all(app).isEmpty())
    }

    @Test fun `selection action sends the current selection to the system chooser without targeting Twig`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = EditText(activity).apply { setText("first second") }
            activity.setContentView(view)
            WebSearch.attach(view)
            val mode = TestMode(activity)
            view.setSelection(0, 5)
            assertTrue(view.customSelectionActionModeCallback.onCreateActionMode(mode, mode.menu))
            view.setSelection(6, 12)
            assertTrue(mode.menu.performIdentifierAction(R.id.web_search, 0))
            assertTrue(mode.finished)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, intent.action)
            @Suppress("DEPRECATION")
            val request = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_WEB_SEARCH, request.action)
            assertEquals("second", request.getStringExtra(SearchManager.QUERY))
            assertNull(request.component)
            assertNull(request.`package`)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `search button homepage opens Twig directly through its gated search entry`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            WebSearch.home(controller.get())
            val request = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Intent.ACTION_WEB_SEARCH, request.action)
            assertEquals(WebSearchActivity::class.java.name, request.component!!.className)
            assertFalse(request.hasExtra(SearchManager.QUERY))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `configuration validates before saving and reset stays local until confirmed`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get().apply { setTheme(R.style.Theme_Twig) }
            SearchEngine.save(app, "https://example.com/", "https://example.com/?q={query}")
            WebSearch.configure(activity)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val home = dialog.findViewById<View>(android.R.id.content)!!.rootView
                .findViewWithTag<EditText>(R.string.search_engine_home)
            val template = home.rootView.findViewWithTag<EditText>(R.string.search_engine_template)
            template.setText("https://example.com/no-placeholder")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertNotNull(template.error)
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals(SearchEngine.default.home, home.text.toString())
            assertEquals("https://example.com/", SearchEngine.home(app))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals(SearchEngine.default.home, SearchEngine.home(app))
        } finally { controller.pause().stop().destroy() }
    }

    private class TestMode(private val activity: Activity) : ActionMode() {
        private val items = MenuBuilder(activity)
        var finished = false
        override fun getMenu(): Menu = items
        override fun finish() { finished = true }
        override fun invalidate() = Unit
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun getTitle(): CharSequence = ""
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun getSubtitle(): CharSequence = ""
        override fun setCustomView(view: View?) = Unit
        override fun getCustomView(): View? = null
        override fun getMenuInflater() = MenuInflater(activity)
    }
}
