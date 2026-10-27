package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Looper
import android.webkit.GeolocationPermissions
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
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
@Config(sdk = [24, 34], application = Application::class)
class WebsiteLocationPermissionsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val origin = "https://example.com"
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private class Result : GeolocationPermissions.Callback {
        val values = mutableListOf<Triple<String, Boolean, Boolean>>()
        override fun invoke(origin: String, allow: Boolean, retain: Boolean) {
            values.add(Triple(origin, allow, retain))
        }
    }

    @Before fun clear() {
        WebsiteStore.preferences(app).edit().clear().commit()
        shadowOf(app).denyPermissions(coarse, fine)
    }

    private fun page(site: Website? = null,
        block: (org.robolectric.android.controller.ActivityController<WebsiteActivity>, WebView) -> Unit) {
        if (site != null) WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            if (site == null) WebsiteActivity.browserIntent(app, origin) else WebsiteActivity.intent(app, site.id)).setup()
        try { block(controller, controller.get().findViewById(R.id.web)) }
        finally { controller.pause().stop().destroy() }
    }

    private fun choose(allow: Boolean, remember: Boolean = false) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        if (remember) dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_permission_remember).performClick()
        dialog.getButton(if (allow) DialogInterface.BUTTON_POSITIVE else DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `temporary pages ask even with Android permission and an identical saved bookmark`() {
        shadowOf(app).grantPermissions(coarse)
        WebsiteStore.save(app, Website(name = "Saved", url = origin, locationPermission = WebsitePermission.ALLOW))
        page { _, web ->
            repeat(2) {
                val result = Result()
                shadowOf(web).webChromeClient!!.onGeolocationPermissionsShowPrompt(origin, result)
                assertTrue(result.values.isEmpty())
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertNull(dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_permission_remember))
                choose(true)
                assertEquals(listOf(Triple(origin, true, false)), result.values)
            }
        }
    }

    @Test fun `system requests both precisions and approximate approval waits for resumed page`() {
        page { controller, web ->
            val result = Result()
            shadowOf(web).webChromeClient!!.onGeolocationPermissionsShowPrompt(origin, result)
            choose(true)
            val requested = shadowOf(controller.get()).lastRequestedPermission
            assertEquals(setOf(coarse, fine), requested.requestedPermissions.toSet())
            controller.pause()
            shadowOf(app).grantPermissions(coarse)
            controller.get().onRequestPermissionsResult(requested.requestCode, arrayOf(coarse, fine),
                intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED))
            assertTrue(result.values.isEmpty())
            controller.resume()
            assertEquals(listOf(Triple(origin, true, false)), result.values)
        }
    }

    @Test fun `saved bookmark can remember allow or block without WebView global retention`() {
        shadowOf(app).grantPermissions(coarse)
        for (allow in listOf(true, false)) {
            val site = Website(name = "Saved", url = origin)
            page(site) { _, web ->
                val client = shadowOf(web).webChromeClient!!
                val result = Result()
                client.onGeolocationPermissionsShowPrompt(origin, result)
                choose(allow, remember = true)
                val saved = WebsiteStore.all(app).first { it.id == site.id }
                assertEquals(if (allow) WebsitePermission.ALLOW else WebsitePermission.DENY, saved.locationPermission)
                assertEquals(WebsitePermission.ASK, saved.microphonePermission)
                assertEquals(WebsitePermission.ASK, saved.cameraPermission)
                assertEquals(listOf(Triple(origin, allow, false)), result.values)
                val again = Result()
                client.onGeolocationPermissionsShowPrompt(origin, again)
                assertEquals(listOf(Triple(origin, allow, false)), again.values)
            }
        }
    }

    @Test fun `foreign origins ports and redirected pages never inherit saved location permission`() {
        val site = Website(name = "Saved", url = origin, locationPermission = WebsitePermission.ALLOW)
        page(site) { _, web ->
            val client = shadowOf(web).webChromeClient!!
            for (foreign in listOf("https://other.example", "https://example.com:444")) {
                val result = Result()
                client.onGeolocationPermissionsShowPrompt(foreign, result)
                assertTrue(result.values.isEmpty())
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertNull(dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_permission_remember))
                choose(false)
                assertEquals(listOf(Triple(foreign, false, false)), result.values)
            }
            web.loadUrl("https://other.example")
            val result = Result()
            client.onGeolocationPermissionsShowPrompt(origin, result)
            assertTrue(result.values.isEmpty())
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNull(dialog.window!!.decorView.findViewWithTag<CheckBox>(R.string.website_permission_remember))
            choose(false)
        }
    }

    @Test fun `hidden canceled and navigated requests cannot receive late system approval`() {
        page { controller, web ->
            val client = shadowOf(web).webChromeClient!!
            val hidden = Result()
            client.onGeolocationPermissionsShowPrompt(origin, hidden)
            client.onGeolocationPermissionsHidePrompt()
            assertTrue(hidden.values.isEmpty())
            assertFalse(ShadowDialog.getLatestDialog().isShowing)
            val stale = Result()
            client.onGeolocationPermissionsShowPrompt(origin, stale)
            choose(true)
            val requested = shadowOf(controller.get()).lastRequestedPermission
            shadowOf(web).webViewClient.onPageStarted(web, "https://other.example", null)
            assertEquals(listOf(Triple(origin, false, false)), stale.values)
            val replacement = Result()
            client.onGeolocationPermissionsShowPrompt(origin, replacement)
            assertEquals(listOf(Triple(origin, false, false)), replacement.values)
            shadowOf(app).grantPermissions(coarse)
            controller.get().onRequestPermissionsResult(requested.requestCode, arrayOf(coarse, fine),
                intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_DENIED))
            assertEquals(1, stale.values.size)
        }
    }

    @Test fun `insecure requests and system denial are rejected`() {
        page { controller, web ->
            val client = shadowOf(web).webChromeClient!!
            val insecure = Result()
            client.onGeolocationPermissionsShowPrompt("http://example.com", insecure)
            assertEquals(listOf(Triple("http://example.com", false, false)), insecure.values)
            val denied = Result()
            client.onGeolocationPermissionsShowPrompt(origin, denied)
            choose(true)
            val requested = shadowOf(controller.get()).lastRequestedPermission
            controller.get().onRequestPermissionsResult(requested.requestCode, arrayOf(coarse, fine),
                intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED))
            assertEquals(listOf(Triple(origin, false, false)), denied.values)
        }
    }

    @Test fun `location configuration persists in backups but is excluded from defaults`() {
        val site = Website(name = "Saved", url = origin)
        page(site) { controller, _ ->
            WebsiteDialogs.edit(controller.get(), site)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.window!!.decorView.findViewWithTag<Spinner>(R.string.website_location).setSelection(WebsitePermission.ALLOW.ordinal)
            choose(true)
            val saved = WebsiteStore.all(app).single()
            assertEquals(WebsitePermission.ALLOW, saved.locationPermission)
            val exported = WebsiteStore.exportJson(app)
            WebsiteStore.preferences(app).edit().clear().commit()
            WebsiteStore.importJson(app, exported)
            assertEquals(saved, WebsiteStore.all(app).single())
            WebsiteStore.saveDefaults(app, saved)
            assertEquals(WebsitePermission.ASK, WebsiteStore.newSite(app).locationPermission)
        }
    }
    @Test fun `revoking saved location refreshes the live page`() {
        val site = Website(name = "Saved", url = origin, locationPermission = WebsitePermission.ALLOW)
        page(site) { controller, web ->
            val count = shadowOf(web).reloadInvocations
            WebsiteStore.save(app, site.copy(locationPermission = WebsitePermission.DENY))
            controller.newIntent(WebsiteActivity.intent(app, site.id))
            assertEquals(count + 1, shadowOf(web).reloadInvocations)
            val result = Result()
            shadowOf(web).webChromeClient!!.onGeolocationPermissionsShowPrompt(origin, result)
            assertEquals(listOf(Triple(origin, false, false)), result.values)
        }
    }

}
