package com.twig.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsitePermission
import com.twig.app.WebsiteStore
import com.twig.app.secure.Secrets

/** WebView's shared geolocation store must never remember a bookmark's decision globally. */
internal class WebsiteLocationPermissions(
    private val activity: AppCompatActivity,
    private val bookmarkId: () -> String?,
    private val pageUrl: () -> String?,
) {
    private class Request(val origin: String, val callback: GeolocationPermissions.Callback)
    private var pending: Request? = null
    private var dialog: AlertDialog? = null
    var requestingAndroid = false
        private set
    private var resultReady = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestingAndroid = false
        resultReady = pending != null
        resume()
    }

    private fun available() = !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !Secrets.locked(activity)

    private fun savedSite(origin: String): Website? {
        val id = bookmarkId() ?: return null
        return WebsiteStore.all(activity).firstOrNull {
            it.id == id && WebsiteCustomization.sameOrigin(it.url, origin) &&
                WebsiteCustomization.sameOrigin(it.url, pageUrl())
        }
    }

    fun request(origin: String, callback: GeolocationPermissions.Callback) {
        val uri = Uri.parse(origin)
        if (pending != null || requestingAndroid || !available() || uri.scheme != "https" || uri.host.isNullOrEmpty()) {
            callback.invoke(origin, false, false)
            return
        }
        val request = Request(origin, callback)
        pending = request
        val site = savedSite(origin)
        when (site?.locationPermission ?: WebsitePermission.ASK) {
            WebsitePermission.ALLOW -> { authorize(request); return }
            WebsitePermission.DENY -> { complete(request, false); return }
            WebsitePermission.ASK -> Unit
        }
        val remember = if (site != null) CheckBox(activity).apply {
            tag = R.string.website_permission_remember
            setText(R.string.website_permission_remember)
        } else null
        fun decide(allow: Boolean) {
            if (pending !== request) return
            dialog = null
            if (!available()) { cancel(); return }
            if (remember?.isChecked == true) savedSite(origin)?.takeIf { it.id == site?.id }?.let { latest ->
                try {
                    WebsiteStore.save(activity, latest.copy(locationPermission =
                        if (allow) WebsitePermission.ALLOW else WebsitePermission.DENY))
                } catch (_: Secrets.KeyUnavailable) {
                    Toast.makeText(activity, R.string.backup_locked, Toast.LENGTH_SHORT).show()
                }
            }
            if (allow) authorize(request) else complete(request, false)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.website_media_permission, origin))
            .setMessage(R.string.website_location)
            .setView(remember?.let { checkbox ->
                LinearLayout(activity).apply {
                    val padding = (24 * activity.resources.displayMetrics.density).toInt()
                    setPadding(padding, 0, padding, 0)
                    addView(checkbox)
                }
            })
            .setPositiveButton(R.string.website_permission_allow) { _, _ -> decide(true) }
            .setNegativeButton(R.string.website_permission_deny) { _, _ -> decide(false) }
            .setOnCancelListener { if (pending === request) cancel() }
            .show()
    }

    private fun hasLocation() = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun authorize(request: Request) {
        if (hasLocation()) complete(request, true)
        else {
            requestingAndroid = true
            // Android 12+ offers approximate/precise only when these are requested together.
            launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    fun resume() {
        if (!resultReady || !available()) return
        val request = pending ?: return
        complete(request, hasLocation())
    }

    fun pause() {
        if (!requestingAndroid && !resultReady) cancel()
    }

    /** The WebView has already withdrawn the request; do not invoke its old callback. */
    fun hidden() {
        clear()
    }

    fun cancel() {
        val request = pending
        clear()
        request?.callback?.invoke(request.origin, false, false)
    }

    private fun clear() {
        pending = null
        resultReady = false
        dialog?.dismiss()
        dialog = null
    }

    private fun complete(request: Request, allow: Boolean) {
        clear()
        request.callback.invoke(request.origin, allow, false)
    }
}
