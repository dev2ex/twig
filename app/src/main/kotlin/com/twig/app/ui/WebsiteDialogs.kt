package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.Rect
import android.util.Log
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.WebsitePermission
import com.twig.app.WebsiteOrientation
import com.twig.app.secure.Secrets

object WebsiteDialogs {
    fun edit(ctx: Context, site: Website? = null) = editForm(ctx, site)

    fun add(ctx: Context, initial: Website) = editForm(ctx, initial = initial)

    fun defaults(ctx: Context) = editForm(ctx, defaults = true)

    private fun editForm(ctx: Context, site: Website? = null, defaults: Boolean = false,
        initial: Website = site ?: if (defaults) WebsiteStore.defaults(ctx) else WebsiteStore.newSite(ctx),
        onDefaultsSaved: (() -> Unit)? = null) {
        fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
        val padding = dp(20)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(12), padding, 0)
        }
        fun section(label: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(ctx).apply { setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider)) },
                LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply {
                    topMargin = dp(8); bottomMargin = dp(8)
                })
            addView(TextView(ctx).apply {
                setText(label)
                setTextColor(ContextCompat.getColor(ctx, R.color.action_text))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                setPaddingRelative(dp(6), 0, dp(6), dp(4))
            })
            form.addView(this)
        }
        fun pair(parent: LinearLayout, first: CheckBox, second: CheckBox) {
            parent.removeView(first)
            parent.removeView(second)
            val row = object : LinearLayout(ctx) {
                // Keep equal columns during the initial AT_MOST pass, as in batch rename.
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED) {
                        val width = (View.MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
                        first.layoutParams.width = when {
                            first.visibility == View.GONE -> 0
                            second.visibility == View.GONE -> width
                            else -> width / 2
                        }
                        second.layoutParams.width = if (second.visibility == View.GONE) 0
                            else width - first.layoutParams.width
                    }
                    super.onMeasure(widthSpec, heightSpec)
                }
            }.apply { isBaselineAligned = false }
            row.addView(first, LinearLayout.LayoutParams(0, -1))
            row.addView(second, LinearLayout.LayoutParams(0, -1))
            parent.addView(row)
        }
        fun fieldLabel(parent: LinearLayout, label: Int) = TextView(ctx).apply {
            setText(label)
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPaddingRelative(dp(6), dp(10), dp(6), dp(2))
            parent.addView(this)
        }
        val name = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_name)
            setSingleLine()
            setText(initial.name)
        }
        val url = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(initial.url)
        }
        form.addView(name)
        form.addView(url)
        val authSection = section(R.string.website_basic_auth)
        val authUsername = EditText(ctx).apply {
            tag = R.string.website_basic_auth_username
            hint = ctx.getString(R.string.website_basic_auth_username)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(site?.basicAuthUsername.orEmpty())
        }
        val authPassword = EditText(ctx).apply {
            tag = R.string.website_basic_auth_password
            hint = ctx.getString(R.string.website_basic_auth_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(site?.basicAuthPassword.orEmpty())
        }
        authSection.addView(authUsername)
        authSection.addView(authPassword)
        fun option(parent: LinearLayout, label: Int, checked: Boolean) = CheckBox(ctx).apply {
            tag = label // Identify each option within the scrollable form.
            setText(label)
            textSize = 14f
            minHeight = dp(48)
            isChecked = checked
            parent.addView(this)
        }
        val displaySection = section(R.string.website_group_display)
        val openFullscreen = option(displaySection, R.string.website_open_fullscreen, initial.openFullscreen)
        val fullscreen = option(displaySection, R.string.website_fullscreen, initial.fullscreen)
        val statusBar = option(displaySection, R.string.website_hide_status_bar, initial.hideStatusBar)
        val navigationBar = option(displaySection, R.string.website_hide_navigation_bar, initial.hideNavigationBar)
        pair(displaySection, openFullscreen, fullscreen)
        pair(displaySection, statusBar, navigationBar)
        val doubleTap = option(displaySection, R.string.website_double_tap_toolbar, initial.doubleTapToolbar)
        val browsingSection = section(R.string.website_group_browsing)
        val desktop = option(browsingSection, R.string.website_desktop_site, site?.desktopSite ?: false)
        val pullToRefresh = option(browsingSection, R.string.website_pull_to_refresh, initial.pullToRefresh)
        val selection = option(browsingSection, R.string.website_disable_text_selection, initial.disableTextSelection)
        val scrollAxis = option(browsingSection, R.string.website_lock_scroll_axis, initial.lockScrollAxis)
        pair(browsingSection, desktop, pullToRefresh)
        pair(browsingSection, selection, scrollAxis)
        val privacySection = section(R.string.website_group_privacy)
        val blockScreenshots = option(privacySection, R.string.website_block_screenshots, initial.blockScreenshots)
        val backgroundRunning = option(privacySection, R.string.website_allow_background_running,
            site?.allowBackgroundRunning ?: false).apply {
            text = ctx.getString(R.string.website_allow_background_running_hint,
                ctx.getString(R.string.website_allow_background_running))
        }
        val orientationSection = section(R.string.website_orientation)
        val orientation = object : RadioGroup(ctx) {
            private var rowHeights = IntArray(0)

            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val width = if (View.MeasureSpec.getMode(widthSpec) == View.MeasureSpec.UNSPECIFIED)
                    dp(280) else View.MeasureSpec.getSize(widthSpec)
                val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
                rowHeights = IntArray((childCount + 1) / 2)
                for (index in 0 until childCount) {
                    val cellWidth = if (index % 2 == 0) available / 2 else available - available / 2
                    val child = getChildAt(index)
                    child.measure(View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    rowHeights[index / 2] = maxOf(rowHeights[index / 2], child.measuredHeight)
                }
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    child.measure(View.MeasureSpec.makeMeasureSpec(child.measuredWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(rowHeights[index / 2], View.MeasureSpec.EXACTLY))
                }
                setMeasuredDimension(View.resolveSize(width, widthSpec),
                    View.resolveSize(paddingTop + rowHeights.sum() + paddingBottom, heightSpec))
            }

            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                var rowTop = paddingTop
                val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    val offset = if (index % 2 == 0) 0 else available / 2
                    val childLeft = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        width - paddingRight - offset - child.measuredWidth else paddingLeft + offset
                    child.layout(childLeft, rowTop, childLeft + child.measuredWidth, rowTop + rowHeights[index / 2])
                    if (index % 2 == 1) rowTop += rowHeights[index / 2]
                }
            }
        }.apply {
            tag = R.string.website_orientation
            val labels = listOf(
                WebsiteOrientation.SYSTEM to R.string.website_orientation_system,
                WebsiteOrientation.FREE to R.string.website_orientation_free,
                WebsiteOrientation.LOCKED to R.string.website_orientation_locked,
                WebsiteOrientation.LANDSCAPE to R.string.website_orientation_landscape,
                WebsiteOrientation.PORTRAIT to R.string.website_orientation_portrait,
            )
            for ((mode, label) in labels) addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = mode
                setText(label)
                textSize = 14f
                minHeight = dp(48)
                setPaddingRelative(0, dp(4), dp(4), dp(4))
            })
            val selected = site?.orientation ?: WebsiteOrientation.SYSTEM
            check(findViewWithTag<RadioButton>(selected).id)
        }
        orientationSection.addView(orientation)
        val advanced = LinearLayout(ctx).apply {
            this.orientation = LinearLayout.VERTICAL
            tag = "website_advanced_fields"
            visibility = View.GONE
        }
        val advancedHeader = TextView(ctx).apply {
            tag = R.string.website_advanced
            setText(R.string.website_advanced)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
            compoundDrawablePadding = (8 * ctx.resources.displayMetrics.density).toInt()
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
            val background = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            setBackgroundResource(background.resourceId)
            isFocusable = true
            setPaddingRelative(dp(6), 0, dp(6), 0)
        }
        fun expandAdvanced(show: Boolean) {
            if (!show && advanced.hasFocus()) {
                ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(advanced.windowToken, 0)
                advanced.findFocus()?.clearFocus()
            }
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advancedHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                if (show) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, 0)
        }
        advancedHeader.setOnClickListener { expandAdvanced(advanced.visibility != View.VISIBLE) }
        form.addView(advancedHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * ctx.resources.displayMetrics.density).toInt()))
        form.addView(advanced)
        val permissionFields = LinearLayout(ctx).apply {
            this.orientation = LinearLayout.VERTICAL
            visibility = if (defaults) View.GONE else View.VISIBLE
            advanced.addView(this)
        }
        fun permission(label: Int, value: WebsitePermission) = android.widget.Spinner(ctx).apply {
            tag = label
            fieldLabel(permissionFields, label)
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf(R.string.website_permission_ask, R.string.website_permission_allow,
                    R.string.website_permission_deny).map { ctx.getString(it) })
            setSelection(value.ordinal)
            permissionFields.addView(this)
        }
        val microphone = permission(R.string.website_microphone, initial.microphonePermission)
        val camera = permission(R.string.website_camera, initial.cameraPermission)
        val location = permission(R.string.website_location, initial.locationPermission)
        fun field(label: Int, hint: Int, value: String, multiline: Boolean = false) = EditText(ctx).apply {
            tag = label
            fieldLabel(advanced, label)
            this.hint = ctx.getString(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (multiline) {
                minLines = 3
                maxLines = 7
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                typeface = Typeface.MONOSPACE
                textSize = 13f
            } else setSingleLine()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(value)
            advanced.addView(this)
        }
        advanced.addView(TextView(ctx).apply {
            setText(R.string.website_global_settings_hint)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setPaddingRelative(dp(6), dp(16), dp(6), dp(4))
        })
        val userAgent = field(R.string.website_user_agent,
            if (defaults) R.string.website_user_agent_hint else R.string.website_user_agent_global_hint, initial.userAgent)
        val headers = field(R.string.website_headers, R.string.website_headers_hint, site?.headers.orEmpty(), true)
        val script = field(R.string.website_script, R.string.website_script_hint, initial.script, true)
        val style = field(R.string.website_style, R.string.website_style_hint, initial.style, true)
        val hidden = field(R.string.website_hidden_elements, R.string.website_hidden_elements_hint, initial.hiddenElements, true)
        val blocked = field(R.string.website_blocked_requests, R.string.website_blocked_requests_hint, initial.blockedRequests, true)
        advanced.addView(TextView(ctx).apply {
            setText(R.string.website_custom_scope)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setPaddingRelative(dp(6), dp(12), dp(6), dp(4))
        })
        if (defaults) {
            // Keep one editor and validation path for both per-site and default settings.
            listOf(name, url, authSection, desktop, backgroundRunning, orientationSection)
                .forEach { it.visibility = View.GONE }
            headers.visibility = View.GONE
            advanced.getChildAt(advanced.indexOfChild(headers) - 1).visibility = View.GONE
            expandAdvanced(true)
        }
        fun advancedError(field: EditText, message: Int) {
            expandAdvanced(true)
            field.error = ctx.getString(message)
            field.requestFocus()
            field.post { field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true) }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (defaults) R.string.website_defaults else if (site == null) R.string.website_add else R.string.website_edit)
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(if (!defaults && site == null) ctx.getString(R.string.website_defaults) else null, null)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnShowListener {
            if (!defaults && site == null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                editForm(ctx, defaults = true) {
                    val values = WebsiteStore.defaults(ctx)
                    openFullscreen.isChecked = values.openFullscreen
                    fullscreen.isChecked = values.fullscreen
                    doubleTap.isChecked = values.doubleTapToolbar
                    blockScreenshots.isChecked = values.blockScreenshots
                    statusBar.isChecked = values.hideStatusBar
                    navigationBar.isChecked = values.hideNavigationBar
                    pullToRefresh.isChecked = values.pullToRefresh
                    selection.isChecked = values.disableTextSelection
                    scrollAxis.isChecked = values.lockScrollAxis
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = WebsiteStore.normalizeUrl(url.text.toString())
                if (!defaults && address == null) {
                    url.error = ctx.getString(R.string.website_invalid_url)
                    return@setOnClickListener
                }
                if (!WebsiteCustomization.validBasicAuth(authUsername.text.toString(), authPassword.text.toString())) {
                    authUsername.error = ctx.getString(R.string.website_basic_auth_invalid)
                    authUsername.requestFocus()
                    return@setOnClickListener
                }
                val agent = userAgent.text.toString().trim()
                if (!WebsiteCustomization.validUserAgent(agent)) {
                    advancedError(userAgent, R.string.website_user_agent_invalid)
                    return@setOnClickListener
                }
                if (WebsiteCustomization.parseHeaders(headers.text.toString()) == null) {
                    advancedError(headers, R.string.website_headers_invalid)
                    return@setOnClickListener
                }
                if (WebsiteRequestBlocker.parse(blocked.text.toString()) == null) {
                    advancedError(blocked, R.string.website_blocked_requests_invalid)
                    return@setOnClickListener
                }
                if (defaults) {
                    WebsiteStore.saveDefaults(ctx, initial.copy(userAgent = agent,
                        doubleTapToolbar = doubleTap.isChecked, blockScreenshots = blockScreenshots.isChecked,
                        openFullscreen = openFullscreen.isChecked, fullscreen = fullscreen.isChecked,
                        hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                        pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                        lockScrollAxis = scrollAxis.isChecked,
                        script = script.text.toString(), style = style.text.toString(),
                        hiddenElements = hidden.text.toString(), blockedRequests = blocked.text.toString()))
                    onDefaultsSaved?.invoke()
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val validatedAddress = requireNotNull(address)
                val label = name.text.toString().trim().ifEmpty { android.net.Uri.parse(validatedAddress).host.orEmpty() }
                val saved = (site ?: Website(name = label, url = validatedAddress)).copy(
                    name = label, url = validatedAddress, fullscreen = fullscreen.isChecked, doubleTapToolbar = doubleTap.isChecked,
                    openFullscreen = openFullscreen.isChecked, allowBackgroundRunning = backgroundRunning.isChecked,
                    blockScreenshots = blockScreenshots.isChecked,
                    microphonePermission = WebsitePermission.entries[microphone.selectedItemPosition],
                    cameraPermission = WebsitePermission.entries[camera.selectedItemPosition],
                    locationPermission = WebsitePermission.entries[location.selectedItemPosition],
                    hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                    pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                    lockScrollAxis = scrollAxis.isChecked, desktopSite = desktop.isChecked,
                    orientation = orientation.findViewById<RadioButton>(orientation.checkedRadioButtonId).tag as WebsiteOrientation,
                    userAgent = agent, headers = headers.text.toString(),
                    basicAuthUsername = authUsername.text.toString(), basicAuthPassword = authPassword.text.toString(),
                    script = script.text.toString(), style = style.text.toString(), hiddenElements = hidden.text.toString(),
                    blockedRequests = blocked.text.toString(),
                )
                val originChanged = site != null && !WebsiteCustomization.sameOrigin(site.url, saved.url)
                try {
                    WebsiteStore.save(ctx, if (originChanged) saved.copy(
                        microphonePermission = WebsitePermission.ASK, cameraPermission = WebsitePermission.ASK,
                        locationPermission = WebsitePermission.ASK) else saved)
                } catch (_: Secrets.KeyUnavailable) {
                    if (saved.basicAuthUsername.isNotEmpty() || saved.basicAuthPassword.isNotEmpty()) {
                        authPassword.error = ctx.getString(R.string.backup_locked)
                        authPassword.requestFocus()
                    } else advancedError(headers, R.string.backup_locked)
                    return@setOnClickListener
                }
                if (site != null && site.url != saved.url) WebsiteIcons.forget(ctx, site)
                updateShortcut(ctx, saved)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun menu(ctx: Context, site: Website) {
        val actions = arrayOf(ctx.getString(R.string.website_edit),
            ctx.getString(R.string.action_pin_shortcut), ctx.getString(R.string.action_delete))
        AlertDialog.Builder(ctx).setTitle(site.name).setItems(actions) { _, index ->
            when (index) {
                0 -> edit(ctx, site)
                1 -> pin(ctx, site)
                2 -> AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.website_delete_confirm, site.name))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        WebsiteStore.remove(ctx, site.id)
                        WebsiteIcons.forget(ctx, site)
                        ShortcutManagerCompat.disableShortcuts(ctx, listOf("website_${site.id}"),
                            ctx.getString(R.string.website_missing))
                    }.show()
            }
        }.show()
    }

    fun shortcut(ctx: Context, site: Website, icon: Bitmap? = WebsiteIcons.cached(site)): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, "website_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(if (icon != null) ShortcutIcons.of(ctx, icon) else ShortcutIcons.of(ctx, R.drawable.ic_website))
            .setIntent(WebsiteActivity.intent(ctx, site.id))
            .build()

    /** Use the cache on a fresh process too, so editing a label keeps its real icon. */
    fun updateShortcut(ctx: Context, site: Website) {
        val app = ctx.applicationContext
        WebsiteIcons.load(app, site) { icon ->
            val saved = WebsiteStore.all(app).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            try {
                ShortcutManagerCompat.updateShortcuts(app, listOf(shortcut(app, saved, icon)))
            } catch (e: Exception) {
                Log.w("WebsiteIcons", "Could not update website shortcut", e)
            }
        }
    }

    fun pin(ctx: Context, site: Website) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            Toast.makeText(ctx, R.string.pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        WebsiteIcons.load(ctx, site) { icon ->
            val saved = WebsiteStore.all(ctx).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut(ctx, saved, icon), null)
        }
    }
}
