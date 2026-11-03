package com.twig.app.ui

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.twig.app.SearchEngine
import com.twig.app.secure.Secrets
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebSearchActivityTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clear() {
        Secrets.reset(app)
        app.getSharedPreferences("twig_prefs", 0).edit().clear().commit()
    }

    @Test fun `Twig is a system search option and handles queries with its configured provider`() {
        val query = "中文 & plus +\nnext"
        val request = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
            .putExtra("headers", "Secret: caller-data")
        val matches = app.packageManager.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue(matches.any { it.activityInfo.name == WebSearchActivity::class.java.name && it.activityInfo.exported })
        SearchEngine.save(app, "https://example.com/", "https://example.com/?term={query}")
        val controller = Robolectric.buildActivity(WebSearchActivity::class.java, request).create()
        try {
            val forwarded = shadowOf(controller.get()).nextStartedActivity
            assertEquals(WebsiteActivity::class.java.name, forwarded.component!!.className)
            assertEquals(query, Uri.parse(forwarded.getStringExtra(WebsiteActivity.SEARCH_ADDRESS)).getQueryParameter("term"))
            assertFalse(forwarded.hasExtra("headers"))
            assertTrue(controller.get().isFinishing)
        } finally { controller.destroy() }
    }

    @Test fun `empty system search opens the configured homepage and invalid actions do nothing`() {
        SearchEngine.save(app, "https://example.com/start", "https://example.com/?q={query}")
        for (action in listOf(Intent.ACTION_WEB_SEARCH, Intent.ACTION_VIEW)) {
            val controller = Robolectric.buildActivity(WebSearchActivity::class.java, Intent(action)).create()
            try {
                val forwarded = shadowOf(controller.get()).nextStartedActivity
                if (action == Intent.ACTION_WEB_SEARCH) {
                    assertEquals("https://example.com/start", forwarded.getStringExtra(WebsiteActivity.SEARCH_ADDRESS))
                } else assertNull(forwarded)
                assertTrue(controller.get().isFinishing)
            } finally { controller.destroy() }
        }
    }
}
