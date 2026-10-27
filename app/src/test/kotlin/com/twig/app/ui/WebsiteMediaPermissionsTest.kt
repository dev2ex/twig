package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.DialogInterface
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.WebView
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
import androidx.appcompat.app.AlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class WebsiteMediaPermissionsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
    private val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE

    private class Request(private val kinds: Array<String>, private val url: String = "https://example.com") : PermissionRequest() {
        var grants: Array<String>? = null
        var denials = 0
        override fun getOrigin() = Uri.parse(url)
        override fun getResources() = kinds
        override fun grant(resources: Array<String>) { grants = resources }
        override fun deny() { denials++ }
    }

    @Before fun clear() {
        WebsiteStore.preferences(app).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private fun page(site: Website? = null, block: (org.robolectric.android.controller.ActivityController<WebsiteActivity>, android.webkit.WebChromeClient) -> Unit) {
        if (site != null) WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            if (site == null) WebsiteActivity.browserIntent(app, "https://example.com")
            else WebsiteActivity.intent(app, site.id)).setup()
        try {
            block(controller, shadowOf(controller.get().findViewById<WebView>(R.id.web)).webChromeClient!!)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `consent is required even with Android permissions and unknown resources never granted`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        page { _, client ->
            val request = Request(arrayOf(audio, video, "future.resource"))
            client.onPermissionRequest(request)
            assertNull(request.grants)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            // Users can allow the microphone without the camera.
            dialog.window!!.decorView.findViewWithTag<android.widget.CheckBox>(video).performClick()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertArrayEquals(arrayOf(audio), request.grants)
            assertEquals(0, request.denials)
        }
    }

    @Test fun `insecure and unsupported requests are denied`() {
        page { _, client ->
            for (request in listOf(Request(arrayOf(audio), "http://example.com"), Request(arrayOf("future.resource")))) {
                client.onPermissionRequest(request)
                assertEquals(1, request.denials)
                assertNull(request.grants)
            }
        }
    }

    @Test fun `cancel navigation and pause invalidate pending consent`() {
        page { controller, client ->
            val canceled = Request(arrayOf(audio))
            client.onPermissionRequest(canceled)
            val dialog = ShadowDialog.getLatestDialog()
            client.onPermissionRequestCanceled(canceled)
            assertFalse(dialog.isShowing)
            assertEquals(0, canceled.denials)
            val navigating = Request(arrayOf(video))
            client.onPermissionRequest(navigating)
            val web = controller.get().findViewById<WebView>(R.id.web)
            shadowOf(web).webViewClient.onPageStarted(web, "https://other.example", null)
            assertEquals(1, navigating.denials)
            val paused = Request(arrayOf(audio))
            client.onPermissionRequest(paused)
            controller.pause()
            assertEquals(1, paused.denials)
        }
    }

    @Test fun `system result grants only approved Android permissions`() {
        page { controller, client ->
            val request = Request(arrayOf(audio, video))
            client.onPermissionRequest(request)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNull(request.grants)
            controller.pause()
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            val permissions = shadowOf(controller.get()).lastRequestedPermission
            controller.get().onRequestPermissionsResult(permissions.requestCode,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED, android.content.pm.PackageManager.PERMISSION_DENIED))
            assertNull(request.grants)
            controller.resume()
            assertArrayEquals(arrayOf(audio), request.grants)
        }
    }
    @Test fun `late system results cannot grant a canceled or replacement request`() {
        page { controller, client ->
            val request = Request(arrayOf(audio))
            client.onPermissionRequest(request)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val permissions = shadowOf(controller.get()).lastRequestedPermission
            client.onPermissionRequestCanceled(request)
            val replacement = Request(arrayOf(video))
            client.onPermissionRequest(replacement)
            assertEquals(1, replacement.denials)
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            controller.get().onRequestPermissionsResult(permissions.requestCode,
                arrayOf(Manifest.permission.RECORD_AUDIO), intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
            assertNull(request.grants)
            assertNull(replacement.grants)
            assertEquals(0, request.denials)
        }
    }

    @Test fun `remembered choices persist only requested resources and skip future prompts`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val site = Website(name = "Saved", url = "https://example.com", cameraPermission = WebsitePermission.DENY)
        page(site) { _, client ->
            val first = Request(arrayOf(audio))
            client.onPermissionRequest(first)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.window!!.decorView.findViewWithTag<android.widget.CheckBox>(R.string.website_permission_remember).performClick()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertArrayEquals(arrayOf(audio), first.grants)
            val remembered = WebsiteStore.all(app).single()
            assertEquals(WebsitePermission.ALLOW, remembered.microphonePermission)
            assertEquals(WebsitePermission.DENY, remembered.cameraPermission)
            val second = Request(arrayOf(audio, video))
            client.onPermissionRequest(second)
            assertArrayEquals(arrayOf(audio), second.grants)
            assertFalse(dialog.isShowing)
            val camera = Request(arrayOf(video))
            client.onPermissionRequest(camera)
            assertEquals(1, camera.denials)
        }
    }

    @Test fun `explicit block can be remembered but dismissal cannot`() {
        val site = Website(name = "Saved", url = "https://example.com")
        page(site) { _, client ->
            val first = Request(arrayOf(audio))
            client.onPermissionRequest(first)
            val canceled = ShadowDialog.getLatestDialog() as AlertDialog
            canceled.window!!.decorView.findViewWithTag<android.widget.CheckBox>(R.string.website_permission_remember).performClick()
            canceled.cancel()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(WebsitePermission.ASK, WebsiteStore.all(app).single().microphonePermission)
            val second = Request(arrayOf(audio))
            client.onPermissionRequest(second)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.window!!.decorView.findViewWithTag<android.widget.CheckBox>(R.string.website_permission_remember).performClick()
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, second.denials)
            assertEquals(WebsitePermission.DENY, WebsiteStore.all(app).single().microphonePermission)
            val third = Request(arrayOf(audio))
            client.onPermissionRequest(third)
            assertEquals(1, third.denials)
            assertFalse(dialog.isShowing)
        }
    }

    @Test fun `temporary pages always ask even when the same address has a saved grant`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        WebsiteStore.save(app, Website(name = "Saved", url = "https://example.com", microphonePermission = WebsitePermission.ALLOW))
        page { _, client ->
            repeat(2) {
                val request = Request(arrayOf(audio))
                client.onPermissionRequest(request)
                assertNull(request.grants)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertNull(dialog.window!!.decorView.findViewWithTag<android.view.View>(R.string.website_permission_remember))
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertArrayEquals(arrayOf(audio), request.grants)
            }
        }
    }

    @Test fun `foreign frames ports and redirected pages cannot use bookmark grants`() {
        val site = Website(name = "Saved", url = "https://example.com", microphonePermission = WebsitePermission.ALLOW)
        page(site) { controller, client ->
            for (origin in listOf("https://foreign.example", "https://example.com:444")) {
                val request = Request(arrayOf(audio), origin)
                client.onPermissionRequest(request)
                assertNull(request.grants)
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                assertNull(dialog.window!!.decorView.findViewWithTag<android.view.View>(R.string.website_permission_remember))
                dialog.cancel()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            val web = controller.get().findViewById<WebView>(R.id.web)
            web.loadUrl("https://foreign.example")
            val request = Request(arrayOf(audio))
            client.onPermissionRequest(request)
            assertNull(request.grants)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNull(dialog.window!!.decorView.findViewWithTag<android.view.View>(R.string.website_permission_remember))
            dialog.cancel()
        }
    }

    @Test fun `site configuration stores independent permissions and exports them`() {
        val site = Website(name = "Saved", url = "https://example.com")
        page(site) { controller, _ ->
            WebsiteDialogs.edit(controller.get(), site)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val root = dialog.window!!.decorView
            root.findViewWithTag<android.widget.Spinner>(R.string.website_microphone).setSelection(WebsitePermission.ALLOW.ordinal)
            root.findViewWithTag<android.widget.Spinner>(R.string.website_camera).setSelection(WebsitePermission.DENY.ordinal)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            val saved = WebsiteStore.all(app).single()
            assertEquals(WebsitePermission.ALLOW, saved.microphonePermission)
            assertEquals(WebsitePermission.DENY, saved.cameraPermission)
            val exported = WebsiteStore.exportJson(app)
            WebsiteStore.preferences(app).edit().clear().commit()
            WebsiteStore.importJson(app, exported)
            assertEquals(saved, WebsiteStore.all(app).single())
            WebsiteStore.saveDefaults(app, saved)
            assertEquals(WebsitePermission.ASK, WebsiteStore.newSite(app).microphonePermission)
            assertEquals(WebsitePermission.ASK, WebsiteStore.newSite(app).cameraPermission)
        }
    }

    @Test fun `old and unrecognized permission records default to asking`() {
        WebsiteStore.importJson(app, """[{"id":"old","name":"Old","url":"https://example.com", "microphonePermission":"future", "locationPermission":"future"}]""")
        assertEquals(WebsitePermission.ASK, WebsiteStore.all(app).single().microphonePermission)
        assertEquals(WebsitePermission.ASK, WebsiteStore.all(app).single().cameraPermission)
        assertEquals(WebsitePermission.ASK, WebsiteStore.all(app).single().locationPermission)
    }

    @Test fun `capture declares the Chromium audio routing permission`() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.MODIFY_AUDIO_SETTINGS))
    }

    @Test fun `editing a bookmark to another origin resets remembered capture permissions`() {
        val site = Website(name = "Saved", url = "https://example.com", microphonePermission = WebsitePermission.ALLOW,
            cameraPermission = WebsitePermission.DENY, locationPermission = WebsitePermission.ALLOW)
        page(site) { controller, _ ->
            WebsiteDialogs.edit(controller.get(), site)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fun changeUrl(view: android.view.View): Boolean {
                if (view is android.widget.EditText && view.hint == app.getString(R.string.website_url)) {
                    view.setText("https://other.example")
                    return true
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
                    if (changeUrl(view.getChildAt(i))) return true
                return false
            }
            assertTrue(changeUrl(dialog.window!!.decorView))
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            val saved = WebsiteStore.all(app).single()
            assertEquals("https://other.example", saved.url)
            assertEquals(WebsitePermission.ASK, saved.microphonePermission)
            assertEquals(WebsitePermission.ASK, saved.cameraPermission)
            assertEquals(WebsitePermission.ASK, saved.locationPermission)
        }
    }

    @Test fun `revoking capture in settings reloads the live page while remembering allow does not`() {
        val site = Website(name = "Saved", url = "https://example.com")
        page(site) { controller, _ ->
            val web = controller.get().findViewById<WebView>(R.id.web)
            val count = shadowOf(web).reloadInvocations
            val allowed = site.copy(microphonePermission = WebsitePermission.ALLOW)
            WebsiteStore.save(app, allowed)
            controller.newIntent(WebsiteActivity.intent(app, site.id))
            assertEquals(count, shadowOf(web).reloadInvocations)
            WebsiteStore.save(app, allowed.copy(microphonePermission = WebsitePermission.DENY))
            controller.newIntent(WebsiteActivity.intent(app, site.id))
            assertEquals(count + 1, shadowOf(web).reloadInvocations)
        }
    }

    @Test fun `stream playback is configured before loading and capture still requires consent`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        page { controller, client ->
            val web = controller.get().findViewById<WebView>(R.id.web)
            val request = Request(arrayOf(video))
            client.onPermissionRequest(request)
            assertFalse(web.settings.mediaPlaybackRequiresUserGesture)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertArrayEquals(arrayOf(video), request.grants)
            assertFalse(web.settings.mediaPlaybackRequiresUserGesture)
            shadowOf(web).webViewClient.onPageStarted(web, "https://example.com/next", null)
            assertFalse(web.settings.mediaPlaybackRequiresUserGesture)
            val denied = Request(arrayOf(video))
            client.onPermissionRequest(denied)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, denied.denials)
            assertFalse(web.settings.mediaPlaybackRequiresUserGesture)
        }
    }

}
