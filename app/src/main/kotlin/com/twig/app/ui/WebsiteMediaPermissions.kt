package com.twig.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** Remembered consent belongs only to the opened bookmark and its saved origin. */
internal class WebsiteMediaPermissions(
    private val activity: AppCompatActivity,
    private val bookmarkId: () -> String? = { null },
    private val pageUrl: () -> String? = { null },
) {
    private var pending: PermissionRequest? = null
    private var selected = emptyList<String>()
    private var dialog: AlertDialog? = null
    var requestingAndroid = false
        private set
    private var resultReady = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestingAndroid = false
        resultReady = pending != null
        // Android delivers results before onResume; starting capture here can fail as background access.
        resume()
    }

    private fun available() = !activity.isFinishing && !activity.isDestroyed &&
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !Secrets.locked(activity)

    fun resume() {
        if (!resultReady || !available()) return
        val request = pending ?: return
        complete(request, selected.filter { hasPermission(it) })
    }

    private fun savedSite(request: PermissionRequest): Website? {
        val id = bookmarkId() ?: return null
        return WebsiteStore.all(activity).firstOrNull {
            it.id == id && WebsiteCustomization.sameOrigin(it.url, request.origin.toString()) &&
                WebsiteCustomization.sameOrigin(it.url, pageUrl())
        }
    }

    private fun policy(site: Website?, resource: String) = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> site?.microphonePermission
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> site?.cameraPermission
        else -> null
    } ?: WebsitePermission.ASK

    fun request(request: PermissionRequest) {
        // A canceled system dialog may still deliver a result. Do not attach it to a new request.
        if (pending != null || requestingAndroid || !available() ||
            request.origin.scheme != "https" || request.origin.host.isNullOrEmpty()) {
            request.deny()
            return
        }
        val resources = request.resources.distinct().filter { androidPermission(it) != null }
        if (resources.isEmpty()) {
            request.deny()
            return
        }
        pending = request
        val site = savedSite(request)
        if (resources.none { policy(site, it) == WebsitePermission.ASK }) {
            authorize(request, resources.filter { policy(site, it) == WebsitePermission.ALLOW })
            return
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        val choices = resources.associateWith { resource ->
            CheckBox(activity).apply {
                tag = resource
                setText(if (resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    R.string.website_microphone else R.string.website_camera)
                isChecked = policy(site, resource) != WebsitePermission.DENY
                isEnabled = policy(site, resource) == WebsitePermission.ASK
                form.addView(this)
            }
        }
        val remember = if (site != null) CheckBox(activity).apply {
            tag = R.string.website_permission_remember
            setText(R.string.website_permission_remember)
            form.addView(this)
        } else null
        fun decide(allowed: List<String>) {
            dialog = null
            if (pending !== request) return
            if (!available()) { cancel(); return }
            if (remember?.isChecked == true) {
                // Re-read the record: edits and bookmark deletion while prompting must be respected.
                savedSite(request)?.takeIf { it.id == site?.id }?.let { latest ->
                    val updated = latest.copy(
                        microphonePermission = if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources)
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in allowed) WebsitePermission.ALLOW else WebsitePermission.DENY
                            else latest.microphonePermission,
                        cameraPermission = if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources)
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in allowed) WebsitePermission.ALLOW else WebsitePermission.DENY
                            else latest.cameraPermission)
                    try { WebsiteStore.save(activity, updated) }
                    catch (_: Secrets.KeyUnavailable) {
                        Toast.makeText(activity, R.string.backup_locked, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            authorize(request, allowed)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.website_media_permission, request.origin.toString()))
            .setView(ScrollView(activity).apply { addView(form) })
            .setNegativeButton(R.string.website_permission_deny) { _, _ -> decide(emptyList()) }
            .setPositiveButton(R.string.website_permission_allow) { _, _ ->
                decide(resources.filter { choices.getValue(it).isChecked })
            }
            .setOnCancelListener { if (pending === request) cancel() }
            .show()
    }

    private fun authorize(request: PermissionRequest, allowed: List<String>) {
        selected = allowed
        val missing = allowed.filterNot { hasPermission(it) }.mapNotNull { androidPermission(it) }
        if (missing.isEmpty()) complete(request, allowed)
        else {
            requestingAndroid = true
            launcher.launch(missing.toTypedArray())
        }
    }

    fun canceled(request: PermissionRequest) {
        // WebView already canceled this request; calling deny on it is no longer valid.
        if (pending === request) clear()
    }

    fun pause() {
        // Android's permission UI pauses the Activity itself; its result waits until resume.
        if (!requestingAndroid && !resultReady) cancel()
    }

    fun cancel() {
        val request = pending
        clear()
        request?.deny()
    }

    private fun clear() {
        pending = null
        selected = emptyList()
        resultReady = false
        dialog?.dismiss()
        dialog = null
    }

    private fun complete(request: PermissionRequest, resources: List<String>) {
        clear()
        if (resources.isEmpty()) request.deny() else request.grant(resources.toTypedArray())
    }

    private fun hasPermission(resource: String) = androidPermission(resource)?.let {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    } == true

    private fun androidPermission(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }
}
