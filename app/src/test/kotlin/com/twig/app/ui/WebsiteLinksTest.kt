package com.twig.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteLinksTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val url = "https://example.com/current?q=中文#section"
    private val probe get() = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        .addCategory(Intent.CATEGORY_BROWSABLE)

    private fun browser(pkg: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = "$pkg.Browser"
            exported = true
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
    }

    @Test fun `default browser receives full current address without private page extras`() {
        shadowOf(app.packageManager).setResolveInfosForIntent(probe, listOf(
            browser("external.default"), browser(app.packageName), browser("external.other")))
        val result = WebsiteLinks.browserIntent(app, url)!!
        assertEquals("external.default", result.component!!.packageName)
        assertEquals(Intent.ACTION_VIEW, result.action)
        assertEquals(url, result.dataString)
        assertNull(result.extras)
    }

    @Test fun `Twig as default offers only other browsers`() {
        shadowOf(app.packageManager).setResolveInfosForIntent(probe, listOf(
            browser(app.packageName), browser("external.one"), browser("external.two")))
        val chooser = WebsiteLinks.browserIntent(app, url)!!
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val targets = listOf(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!) +
            chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.map { it as Intent }
        assertEquals(setOf("external.one", "external.two"), targets.map { it.component!!.packageName }.toSet())
        assertTrue(targets.all { it.dataString == url })
    }

    @Test fun `only Twig installed cannot reopen itself and internal URLs stay internal`() {
        shadowOf(app.packageManager).setResolveInfosForIntent(probe, listOf(browser(app.packageName)))
        assertNull(WebsiteLinks.browserIntent(app, url))
        assertNull(WebsiteLinks.browserIntent(app, "file:///data/private"))
        assertNull(WebsiteLinks.browserIntent(app, "about:blank"))
    }
}
