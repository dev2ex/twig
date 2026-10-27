package com.twig.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.net.http.SslError
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.MenuItem
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.SslErrorHandler
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.HttpAuthHandler
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.ServerCertificateTrust
import com.twig.fs.network.ServerTls
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.WebsiteOrientation
import com.twig.app.secure.Secrets
import com.twig.app.databinding.ActivityWebsiteBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** Shares WebView's persistent website data across tree entries and launcher shortcuts. */
class WebsiteActivity : AppCompatActivity() {
    private lateinit var b: ActivityWebsiteBinding
    private lateinit var site: Website
    private lateinit var localSite: Website
    private val globalSettingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "defaults" && ::site.isInitialized && !Secrets.locked(this)) {
            applyWebsiteIntent()
        }
    }
    private var pendingSavedState: Bundle? = null
    private var pendingWebsiteIntent = false
    private var lockCover: View? = null
    @Volatile private var requestBlocker = WebsiteRequestBlocker.EMPTY
    private var mobileUserAgent = ""
    private lateinit var debugger: WebsiteDebugPanel
    private var remoteDebugMenu: MenuItem? = null
    private var customStyleApplied = false
    private var pendingMainUrl: String? = null
    private var backgroundNotificationRequested = false
    private val backgroundNotificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var toolbarVisibleOverride: Boolean? = null
    private var immersiveFullscreen = false
    private var fullscreenFromToolbar = false
    private var fullscreenLayer: FrameLayout? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val certificateRequests = mutableMapOf<SslErrorHandler, Job>()
    private val mediaPermissions = WebsiteMediaPermissions(this,
        bookmarkId = { if (!isBrowserPage() && ::localSite.isInitialized) localSite.id else null },
        pageUrl = { if (::b.isInitialized) b.web.url else null })
    private val locationPermissions = WebsiteLocationPermissions(this,
        bookmarkId = { if (!isBrowserPage() && ::localSite.isInitialized) localSite.id else null },
        pageUrl = { if (::b.isInitialized) b.web.url else null })
    private val downloads = WebsiteDownloads(this)
    private var upload: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = upload
        upload = null
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK) {
            data?.clipData?.let { clip -> Array(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data?.data?.let { arrayOf(it) }
        } else null
        callback?.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebsiteBinding.inflate(layoutInflater)
        setContentView(b.root)
        WebsiteStore.preferences(this).registerOnSharedPreferenceChangeListener(globalSettingsListener)
        pendingSavedState = savedInstanceState
        unlockWebsite()
    }

    /** Keep the live WebView running behind an opaque input shield while asking to unlock. */
    private fun protectWebsite() {
        syncScreenshotProtection()
        if (!Secrets.locked(this) || lockCover != null) return
        lockCover = View(this).apply {
            tag = "website_lock_cover"
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@WebsiteActivity, R.color.surface))
            isClickable = true
            isFocusable = true
        }.also {
            (window.decorView as ViewGroup).addView(it, ViewGroup.LayoutParams(-1, -1))
            it.requestFocus()
        }
        b.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        fullscreenLayer?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun unlockWebsite() {
        protectWebsite()
        SecurityUi.gate(this) {
            if (isFinishing || isDestroyed) return@gate
            syncScreenshotProtection()
            if (!::site.isInitialized) {
                initializeWebsite(pendingSavedState)
                pendingSavedState = null
            }
            if (isFinishing) return@gate
            if (pendingWebsiteIntent) {
                pendingWebsiteIntent = false
                applyWebsiteIntent()
            }
            if (isFinishing) return@gate
            lockCover?.let { (it.parent as? ViewGroup)?.removeView(it) }
            lockCover = null
            b.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            fullscreenLayer?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                WebsiteRemoteDebug.start(this)
                syncRemoteDebugMenu()
            }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) resumeWebsite()
        }
    }

    private fun syncScreenshotProtection() {
        val secure = if (Secrets.locked(this)) true else {
            requestedWebsite()?.blockScreenshots ?: false
        }
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun isBrowserPage() = intent.data?.scheme?.lowercase() in setOf("http", "https")

    private fun requestedWebsite(): Website? {
        if (isBrowserPage()) {
            val address = WebsiteStore.normalizeUrl(intent.data.toString()) ?: return null
            if (::localSite.isInitialized && localSite.id == "browser:$address") return localSite
            return WebsiteStore.newSite(this).copy(id = "browser:$address",
                name = Uri.parse(address).host.orEmpty(), url = address)
        }
        val id = intent.data?.takeIf { it.scheme == "twig" && it.host == "website" }?.lastPathSegment
        return WebsiteStore.all(this).firstOrNull { it.id == id }
    }

    private fun openAddress() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(b.web.url ?: site.url)
            selectAll()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.website_open_url).setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = WebsiteStore.normalizeUrl(input.text.toString())
                if (address == null) input.error = getString(R.string.website_invalid_url)
                else {
                    startActivity(browserIntent(this, address))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebsite(savedInstanceState: Bundle?) {
        val requested = requestedWebsite()
        val saved = requested?.let { WebsiteStore.effective(this, it) }
        if (saved == null || WebsiteStore.normalizeUrl(saved.url) == null) {
            Toast.makeText(this, R.string.website_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        localSite = requireNotNull(requested)
        site = saved
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                if (!Secrets.locked(this@WebsiteActivity)) resumeWebsite()
            }
        })
        requestBlocker = WebsiteRequestBlocker.parse(saved.blockedRequests) ?: WebsiteRequestBlocker.EMPTY
        debugger = WebsiteDebugPanel(this, b.debugHost, b.web, { localSite }, persist = !isBrowserPage()) { updated ->
            localSite = updated
            site = WebsiteStore.effective(this, updated)
            customStyleApplied = WebsiteCustomization.hasStyles(site)
        }
        backgroundNotificationRequested = savedInstanceState?.getBoolean("background_notification_requested") ?: false
        toolbarVisibleOverride = savedInstanceState?.takeIf { it.containsKey("toolbar_visible_override") }
            ?.getBoolean("toolbar_visible_override")
        b.web.onDoubleTapToolbar = {
            toolbarVisibleOverride = b.toolbar.visibility != View.VISIBLE
            applyWebsiteOptions()
        }
        immersiveFullscreen = savedInstanceState?.getBoolean("immersive_fullscreen", site.openFullscreen)
            ?: site.openFullscreen
        fullscreenFromToolbar = immersiveFullscreen &&
            (savedInstanceState?.getBoolean("fullscreen_from_toolbar") == true)
        title = site.name
        b.toolbar.title = site.name
        b.toolbar.setNavigationOnClickListener { back() }
        b.toolbar.menu.add(R.string.viewer_search).apply {
            setIcon(R.drawable.ic_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { toggleSearch(true); true }
        }
        b.searchInput.doAfterTextChanged { searchPage() }
        b.searchInput.setOnEditorActionListener { _, _, _ -> b.web.findNext(true); true }
        b.searchPrev.setOnClickListener { b.web.findNext(false) }
        b.searchNext.setOnClickListener { b.web.findNext(true) }
        b.searchClose.setOnClickListener { toggleSearch(false) }
        b.web.setFindListener { active, count, done ->
            if (done && b.searchBar.visibility == View.VISIBLE && !b.searchInput.text.isNullOrEmpty()) {
                b.searchCount.text = if (count == 0) "0" else "${active + 1}/$count"
            }
        }
        b.toolbar.menu.add(R.string.website_enter_fullscreen).setOnMenuItemClickListener {
            toolbarVisibleOverride = null
            immersiveFullscreen = true
            fullscreenFromToolbar = true
            applyWebsiteOptions()
            true
        }
        b.toolbar.menu.add(R.string.action_refresh).setOnMenuItemClickListener { b.web.reload(); true }
        if (isBrowserPage()) {
            b.toolbar.menu.add(R.string.website_open_url).setOnMenuItemClickListener { openAddress(); true }
            b.toolbar.menu.add(R.string.website_add).setOnMenuItemClickListener {
                val address = WebsiteStore.normalizeUrl(b.web.url ?: site.url) ?: site.url
                WebsiteDialogs.add(this, localSite.copy(name = Uri.parse(address).host.orEmpty(), url = address))
                true
            }
        } else b.toolbar.menu.add(R.string.action_pin_shortcut).setOnMenuItemClickListener {
            WebsiteDialogs.pin(this, site); true
        }
        b.toolbar.menu.add(R.string.website_debug).setOnMenuItemClickListener {
            debugger.show(); true
        }
        remoteDebugMenu = b.toolbar.menu.add(R.string.website_debug_pc).apply {
            setOnMenuItemClickListener {
                val forced = WebsiteRemoteDebug.forcedBySystem
                if (!forced) WebsiteRemoteDebug.setEnabled(!WebsiteRemoteDebug.enabled)
                syncRemoteDebugMenu()
                if (forced || WebsiteRemoteDebug.enabled) androidx.appcompat.app.AlertDialog.Builder(this@WebsiteActivity)
                    .setTitle(if (forced) R.string.website_debug_pc_forced else R.string.website_debug_pc)
                    .setMessage(if (forced) getString(R.string.website_debug_pc_forced_help, Build.TYPE)
                        else getString(R.string.website_debug_pc_help))
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
        }
        syncRemoteDebugMenu()
        // Insets for independently hidden system bars, including the on-screen keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            val hideStatus = immersiveFullscreen || site.hideStatusBar
            val hideNavigation = immersiveFullscreen || site.hideNavigationBar
            if (hideStatus || hideNavigation) {
                val types = WindowInsetsCompat.Type.ime() or
                    (if (hideStatus) 0 else WindowInsetsCompat.Type.statusBars()) or
                    (if (hideNavigation) 0 else WindowInsetsCompat.Type.navigationBars()) or
                    (if (hideStatus) 0 else WindowInsetsCompat.Type.displayCutout())
                val bars = insets.getInsets(types)
                // Hidden status bars use the cutout area on every edge, as in the file panes.
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(0, 0, 0, 0)
            insets
        }
        b.web.clearSslPreferences()
        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            // Chromium latches gesture locks when media elements are created. Set before loading,
            // so authorized camera previews and incoming WebRTC streams can actually play.
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        mobileUserAgent = b.web.settings.userAgentString
        b.web.onPullProgress = { fraction ->
            b.pullIndicator.visibility = if (fraction > 0f) View.VISIBLE else View.GONE
            b.pullIndicator.alpha = fraction
            b.pullIndicator.translationY = 32 * resources.displayMetrics.density * fraction
        }
        b.web.onRefresh = { b.web.reload() }
        applyWebsiteOptions()
        CookieManager.getInstance().setAcceptCookie(true)
        b.web.setDownloadListener { url, agent, disposition, mime, size ->
            downloads.offer(site, url, agent, disposition, mime, size)
        }
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Called off the UI thread. Read only the immutable rule snapshot, never views or preferences.
                if (request.isForMainFrame || !requestBlocker.blocks(request.url.toString())) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val credentials = WebsiteCustomization.basicAuthFor(site, view.url ?: pendingMainUrl, host)
                if (credentials != null && handler.useHttpAuthUsernamePassword()) {
                    handler.proceed(credentials.first, credentials.second)
                } else handler.cancel()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                confirmCertificate(view, handler, error)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mediaPermissions.cancel()
                locationPermissions.cancel()
                pendingMainUrl = url
                debugger.navigation(url)
                customStyleApplied = false
                cancelCertificateRequests()
                view.clearSslPreferences()
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) pendingMainUrl = request.url.toString()
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "https" || scheme == "http") {
                    if (request.isForMainFrame && request.method == "GET") {
                        val url = request.url.toString()
                        val headers = WebsiteCustomization.headersFor(site, url)
                        if (!request.isRedirect && headers.isNotEmpty()) {
                            view.loadUrl(url, headers.toMutableMap())
                            return true
                        }
                        if (request.isRedirect && site.headers.isNotEmpty() &&
                            !WebsiteCustomization.sameOrigin(site.url, url)) {
                            // Restart a foreign redirect without this bookmark's additional headers.
                            view.loadUrl(url)
                            return true
                        }
                    }
                    return false
                }
                // Never grant a website access to app files or arbitrary intent extras.
                if (request.isForMainFrame && request.hasGesture() && scheme in setOf("mailto", "tel", "sms", "geo")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@WebsiteActivity, R.string.website_no_app, Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                applyPageOptions()
                applyCustomStyle()
            }

            override fun onPageFinished(view: WebView, url: String) {
                applyPageOptions()
                applyCustomStyle()
                if (WebsiteCustomization.sameOrigin(site.url, view.url)) {
                    WebsiteCustomization.pageScript(site)?.let { view.evaluateJavascript(it, null) }
                }
                if (b.searchBar.visibility == View.VISIBLE) searchPage()
                b.toolbar.subtitle = Uri.parse(url).host
                persistCookies()
            }
        }
        b.web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
                locationPermissions.request(origin, callback)

            override fun onGeolocationPermissionsHidePrompt() = locationPermissions.hidden()

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) =
                mediaPermissions.request(request)

            override fun onPermissionRequestCanceled(request: android.webkit.PermissionRequest) =
                mediaPermissions.canceled(request)

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                debugger.console(message)
                return true
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                WebsiteIcons.received(this@WebsiteActivity, site, view.url, icon)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showPageFullscreen(view, callback)
            }

            override fun onHideCustomView() = hidePageFullscreen()

            override fun onProgressChanged(view: WebView, progress: Int) {
                b.progress.progress = progress
                b.progress.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams): Boolean {
                upload?.onReceiveValue(null)
                upload = callback
                try {
                    picker.launch(params.createIntent().apply {
                        setClass(this@WebsiteActivity, PickerActivity::class.java)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    })
                } catch (_: ActivityNotFoundException) {
                    upload?.onReceiveValue(null)
                    upload = null
                    Toast.makeText(this@WebsiteActivity, R.string.website_no_app, Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        val sameRequest = savedInstanceState?.getString("website_request_key")?.let {
            it == WebsiteCustomization.requestKey(site)
        } ?: (site.userAgent.isEmpty() && site.headers.isEmpty() && site.script.isEmpty() &&
            site.basicAuthUsername.isEmpty() && site.basicAuthPassword.isEmpty())
        if (savedInstanceState?.getString("bookmark_url") != site.url || !sameRequest ||
            b.web.restoreState(savedInstanceState) == null) loadWebsite(site.url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWebsiteIntent = true
        unlockWebsite()
    }

    private fun applyWebsiteIntent() {
        // Bringing an existing document task forward must keep its live WebView,
        // including navigation, scroll position and unsaved form contents.
        val requested = requestedWebsite()
        val saved = requested?.let { WebsiteStore.effective(this, it) }
        if (!::site.isInitialized || saved == null || saved.id != site.id ||
            WebsiteStore.normalizeUrl(saved.url) == null) {
            recreate()
            return
        }
        localSite = requireNotNull(requested)
        if (saved == site) return
        val changedUrl = saved.url != site.url
        if (saved.fullscreen != site.fullscreen || saved.openFullscreen != site.openFullscreen ||
            saved.doubleTapToolbar != site.doubleTapToolbar) toolbarVisibleOverride = null
        val changedDesktop = saved.desktopSite != site.desktopSite
        val changedAgent = saved.userAgent != site.userAgent
        val changedHeaders = saved.headers != site.headers
        val changedScript = saved.script != site.script
        val changedAuth = saved.basicAuthUsername != site.basicAuthUsername || saved.basicAuthPassword != site.basicAuthPassword
        val revokedPermission = WebsiteCustomization.sameOrigin(site.url, b.web.url) &&
            ((saved.microphonePermission != site.microphonePermission && saved.microphonePermission != com.twig.app.WebsitePermission.ALLOW) ||
                (saved.cameraPermission != site.cameraPermission && saved.cameraPermission != com.twig.app.WebsitePermission.ALLOW) ||
                (saved.locationPermission != site.locationPermission && saved.locationPermission != com.twig.app.WebsitePermission.ALLOW))
        if (saved.openFullscreen != site.openFullscreen) {
            immersiveFullscreen = saved.openFullscreen
            fullscreenFromToolbar = false
        }
        site = saved
        requestBlocker = WebsiteRequestBlocker.parse(saved.blockedRequests) ?: WebsiteRequestBlocker.EMPTY
        if (changedUrl || changedDesktop || changedAgent || changedHeaders || changedScript || changedAuth) hidePageFullscreen()
        applyWebsiteOptions()
        when {
            changedUrl -> loadWebsite(site.url)
            changedHeaders || changedAuth -> {
                mediaPermissions.cancel()
                locationPermissions.cancel()
                loadWebsite(b.web.url ?: site.url)
            }
            revokedPermission -> {
                mediaPermissions.cancel()
                locationPermissions.cancel()
                b.web.reload()
            }
            changedDesktop || changedAgent || changedScript -> b.web.reload()
            else -> { applyPageOptions(); applyCustomStyle() }
        }
    }

    private fun loadWebsite(url: String) {
        pendingMainUrl = url
        val headers = WebsiteCustomization.headersFor(site, url)
        if (headers.isEmpty()) b.web.loadUrl(url) else b.web.loadUrl(url, headers.toMutableMap())
    }

    private fun applyCustomStyle() {
        val styled = WebsiteStore.effective(this, debugger.styleSite(localSite))
        if (!WebsiteCustomization.hasStyles(styled) && !customStyleApplied) return
        if (WebsiteCustomization.sameOrigin(site.url, b.web.url)) {
            b.web.evaluateJavascript(WebsiteCustomization.styleScript(styled), null)
            customStyleApplied = WebsiteCustomization.hasStyles(styled)
        }
    }

    private fun back() {
        if (Secrets.locked(this)) { unlockWebsite(); return }
        if (fullscreenLayer != null) {
            hidePageFullscreen()
            return
        }
        if (debugger.hide()) return
        if (b.searchBar.visibility == View.VISIBLE) {
            toggleSearch(false)
            return
        }
        if (immersiveFullscreen && fullscreenFromToolbar) {
            val wasHidden = b.toolbar.visibility != View.VISIBLE
            toolbarVisibleOverride = null
            immersiveFullscreen = false
            fullscreenFromToolbar = false
            applyWebsiteOptions()
            // Consume Back only when it actually brings the toolbar back. If the
            // bookmark now hides it permanently, do not add an invisible Back step.
            if (wasHidden && b.toolbar.visibility == View.VISIBLE) return
        }
        // System navigation already handles revealing hidden bars. A bookmark's
        // display mode must not add another Back stage before history/finish.
        if (b.web.canGoBack()) b.web.goBack() else finish()
    }

    private fun toggleSearch(show: Boolean) {
        b.searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            b.searchInput.requestFocus()
            keyboard.showSoftInput(b.searchInput, 0)
        } else {
            keyboard.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
            b.searchInput.setText("")
            b.web.clearMatches()
            b.web.requestFocus()
        }
    }

    /** Match HTML preview: use WebView's native find-in-page, including highlighting and scrolling. */
    private fun searchPage() {
        b.web.clearMatches()
        b.searchCount.text = ""
        val query = b.searchInput.text.toString()
        if (query.isNotEmpty() && b.searchBar.visibility == View.VISIBLE) b.web.findAllAsync(query)
    }

    /** Chromium supplies this view for HTML video and the page Fullscreen API. */
    private fun showPageFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenLayer != null || view.parent != null) {
            callback.onCustomViewHidden()
            return
        }
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT))
        }
        fullscreenLayer = layer
        fullscreenCallback = callback
        ViewCompat.setOnApplyWindowInsetsListener(layer) { container, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.ime())
            container.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        (window.decorView as ViewGroup).addView(layer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        b.root.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        applySystemBars()
        ViewCompat.requestApplyInsets(layer)
        view.requestFocus()
    }

    private fun hidePageFullscreen(restorePage: Boolean = true) {
        val layer = fullscreenLayer ?: return
        val callback = fullscreenCallback
        // Clear first: Chromium may call onHideCustomView again from the callback.
        fullscreenLayer = null
        fullscreenCallback = null
        layer.keepScreenOn = false
        layer.removeAllViews()
        (layer.parent as? ViewGroup)?.removeView(layer)
        if (restorePage) {
            b.root.visibility = View.VISIBLE
            applySystemBars()
            ViewCompat.requestApplyInsets(b.root)
            b.web.requestFocus()
        }
        callback?.onCustomViewHidden()
    }

    private fun applyWebsiteOptions() {
        val orientation = when (site.orientation) {
            WebsiteOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_USER
            WebsiteOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            WebsiteOrientation.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            WebsiteOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WebsiteOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // Do not recapture a locked orientation when unrelated display options change.
        if (requestedOrientation != orientation) requestedOrientation = orientation
        title = site.name
        b.toolbar.title = site.name
        b.toolbar.visibility = if (toolbarVisibleOverride ?: !(site.fullscreen || immersiveFullscreen)) View.VISIBLE else View.GONE
        b.web.doubleTapToolbar = site.doubleTapToolbar
        b.web.pullToRefresh = site.pullToRefresh
        b.web.lockScrollAxis = site.lockScrollAxis
        b.web.settings.apply {
            val wanted = site.userAgent.takeIf { WebsiteCustomization.validUserAgent(it) }.orEmpty().ifBlank {
                if (site.desktopSite) desktopUserAgent(mobileUserAgent) else mobileUserAgent
            }
            if (userAgentString != wanted) userAgentString = wanted
            useWideViewPort = true
            loadWithOverviewMode = site.desktopSite
        }
        syncBackgroundRunning()
        applySystemBars()
        ViewCompat.requestApplyInsets(b.root)
    }

    internal fun backgroundRunningDisabled() {
        if (::site.isInitialized) site = site.copy(allowBackgroundRunning = false)
        if (::b.isInitialized && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) b.web.onPause()
    }

    private fun syncBackgroundRunning() {
        if (!::site.isInitialized) return
        // Existing sessions stay alive while locked, but cannot create a new session or prompt.
        if (Secrets.locked(this)) return
        val allowed = WebsiteStore.all(this).firstOrNull { it.id == site.id }?.allowBackgroundRunning == true
        if (allowed != site.allowBackgroundRunning) site = site.copy(allowBackgroundRunning = allowed)
        WebsiteBackground.update(this, site)
        if (site.allowBackgroundRunning && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            Build.VERSION.SDK_INT >= 33 && !backgroundNotificationRequested &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            backgroundNotificationRequested = true
            backgroundNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun applySystemBars() {
        if (!::site.isInitialized) return
        val pageFullscreen = fullscreenLayer != null
        val hideStatus = pageFullscreen || immersiveFullscreen || site.hideStatusBar
        val hideNavigation = pageFullscreen || immersiveFullscreen || site.hideNavigationBar
        if (pageFullscreen) NavBarTint.apply(this, Color.BLACK) else NavBarTint.surface(this)
        WindowCompat.setDecorFitsSystemWindows(window, !hideStatus && !hideNavigation)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (hideStatus)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideStatus) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
            if (hideNavigation) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /** Reapply on each document; CSS also covers content inserted later by a SPA. */
    private fun applyPageOptions() {
        b.web.evaluateJavascript("""
            (() => {
                const key = '__twig_selection_style';
                let style = document.getElementById(key);
                if (${site.disableTextSelection}) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = key;
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = 'html,body,body *{-webkit-user-select:none!important;user-select:none!important}' +
                        'input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important}';
                } else if (style) style.remove();
                let viewport = document.querySelector('meta[name="viewport"]');
                if (${site.desktopSite}) {
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        viewport.__twigCreated = true;
                        (document.head || document.documentElement).appendChild(viewport);
                    }
                    if (viewport.__twigOriginal === undefined)
                        viewport.__twigOriginal = viewport.getAttribute('content');
                    viewport.setAttribute('content', 'width=1280');
                } else if (viewport && viewport.__twigOriginal !== undefined) {
                    if (viewport.__twigCreated) viewport.remove();
                    else if (viewport.__twigOriginal === null) viewport.removeAttribute('content');
                    else viewport.setAttribute('content', viewport.__twigOriginal);
                    delete viewport.__twigOriginal;
                }
            })();
        """.trimIndent(), null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        ViewCompat.requestApplyInsets(fullscreenLayer ?: b.root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /** Authenticate the actual failing request, including subresources and redirects. */
    private fun confirmCertificate(view: WebView, handler: SslErrorHandler, error: SslError) {
        // WebView caches proceed decisions itself. Our fingerprint store must remain
        // the authority rather than Chromium remembering a broader SSL exception.
        view.clearSslPreferences()
        val uri = Uri.parse(error.url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT)?.removeSurrounding("[", "]")
        val port = if (uri.port == -1) 443 else uri.port
        val certificate = runCatching {
            val encoded = SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate")
                ?: return@runCatching null
            java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(encoded.inputStream()) as java.security.cert.X509Certificate
        }.getOrNull()
        if (uri.scheme != "https" || host.isNullOrEmpty() || port !in 1..65535 ||
            certificate == null || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            handler.cancel()
            return
        }
        val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val trust = ServerCertificateTrust.forApp(this)
        val fingerprint = ServerTls.fingerprint(certificate)
        val previous = trust.remembered(authority)
        if (previous == fingerprint) {
            handler.proceed()
            view.clearSslPreferences()
            return
        }
        val job = lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var proceeded = false
            try {
                val accepted = runInterruptible(Dispatchers.IO) { trust.confirm(authority, certificate, previous) }
                if (accepted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    handler.proceed()
                    proceeded = true
                    view.clearSslPreferences()
                }
            } finally {
                if (certificateRequests.remove(handler) != null && !proceeded) handler.cancel()
            }
        }
        certificateRequests[handler] = job
        job.start()
    }

    private fun cancelCertificateRequests() {
        val pending = certificateRequests.toMap()
        certificateRequests.clear()
        pending.forEach { (handler, job) ->
            handler.cancel()
            job.cancel()
        }
    }

    private fun persistCookies() {
        lifecycleScope.launch(Dispatchers.IO) { CookieManager.getInstance().flush() }
    }

    override fun onPause() {
        mediaPermissions.pause()
        locationPermissions.pause()
        if (::debugger.isInitialized) debugger.stopInspection()
        cancelCertificateRequests()
        if (::b.isInitialized) {
            // Keep opted-in WebViews running; their foreground service protects the host from cached-app freezing.
            if ((!::site.isInitialized || !site.allowBackgroundRunning) && !mediaPermissions.requestingAndroid && !locationPermissions.requestingAndroid) b.web.onPause()
            persistCookies()
        }
        super.onPause()
    }

    private fun syncRemoteDebugMenu() {
        val forced = WebsiteRemoteDebug.forcedBySystem
        remoteDebugMenu?.apply {
            setTitle(if (forced) R.string.website_debug_pc_forced else R.string.website_debug_pc)
            isCheckable = !forced
            isChecked = !forced && WebsiteRemoteDebug.enabled
        }
    }

    override fun onResume() {
        super.onResume()
        unlockWebsite()
    }

    private fun resumeWebsite() {
        if (::site.isInitialized && !Secrets.locked(this)) applyWebsiteIntent()
        syncBackgroundRunning()
        syncRemoteDebugMenu()
        if (::b.isInitialized) {
            b.web.clearSslPreferences()
            b.web.onResume()
            mediaPermissions.resume()
            locationPermissions.resume()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!::site.isInitialized) {
            pendingSavedState?.let { outState.putAll(it) }
            super.onSaveInstanceState(outState)
            return
        }
        outState.putBoolean("background_notification_requested", backgroundNotificationRequested)
        toolbarVisibleOverride?.let { outState.putBoolean("toolbar_visible_override", it) }
        outState.putBoolean("immersive_fullscreen", immersiveFullscreen)
        outState.putBoolean("fullscreen_from_toolbar", fullscreenFromToolbar)
        if (::site.isInitialized) {
            outState.putString("bookmark_url", site.url)
            outState.putString("website_request_key", WebsiteCustomization.requestKey(site))
        }
        b.web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        protectWebsite()
        if (::site.isInitialized && !Secrets.locked(this)) {
            WebsiteRemoteDebug.start(this)
            syncRemoteDebugMenu()
        }
    }

    override fun onStop() {
        mediaPermissions.cancel()
        locationPermissions.cancel()
        WebsiteRemoteDebug.stop(this)
        downloads.stop()
        super.onStop()
    }

    override fun onDestroy() {
        mediaPermissions.cancel()
        locationPermissions.cancel()
        WebsiteStore.preferences(this).unregisterOnSharedPreferenceChangeListener(globalSettingsListener)
        WebsiteBackground.release(this)
        WebsiteRemoteDebug.stop(this)
        if (::debugger.isInitialized) debugger.destroy()
        downloads.destroy()
        cancelCertificateRequests()
        hidePageFullscreen(restorePage = false)
        upload?.onReceiveValue(null)
        upload = null
        b.web.stopLoading()
        (b.web.parent as? android.view.ViewGroup)?.removeView(b.web)
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Keep the installed engine version while asking servers for their desktop layout. */
        internal fun desktopUserAgent(mobile: String): String = mobile
            .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Version/4.0", "").replace(" Mobile", "")

        internal fun browserIntent(ctx: Context, address: String) = Intent(ctx, WebsiteActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(address))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        /** A document task per bookmark keeps desktop launches independent of the file browser. */
        fun intent(ctx: Context, id: String) = Intent(ctx, WebsiteActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.Builder().scheme("twig").authority("website").appendPath(id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
