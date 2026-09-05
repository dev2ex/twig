package com.twig.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import com.twig.app.Format
import com.twig.app.PlaybackStore
import com.twig.app.Prefs
import com.twig.app.Privileged
import com.twig.app.R
import rikka.shizuku.Shizuku

/**
 * Standalone settings page (hand-written layout, no preference library): display
 * (language / row height / text size) + thumbnails. Changes written to Prefs take
 * effect immediately; MainActivity auto-recreates when it returns to the foreground
 * if [Prefs.uiSignature] changes.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density

    /** Thumbnail sub-item rows (greyed out and disabled when the master switch is off). */
    private val thumbRows = ArrayList<View>()

    // The whole companion is no longer private: PROJECT_URL must be readable by tests (the other two stay private).
    internal companion object {
        /** Shizuku authorisation request code; this page only requests once, any value works. */
        private const val SHIZUKU_REQ = 4001

        /** Same tag as Privileged, `adb logcat -s twig-priv` shows the entire chain at once. */
        private const val PRIV_TAG = "twig-priv"

        /**
         * Project homepage. As a constant rather than a string resource: the URL is
         * identical in both languages, putting it in strings.xml would only add a
         * "needs translation but cannot be translated" entry (and updating the value
         * requires editing two copies).
         */
        const val PROJECT_URL = "https://github.com/dev2ex/twig"
    }

    /** Terminal font import: after copying into the app's private directory, the entire page rebuilds and the subtitle updates. */
    private val fontPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TerminalFont.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_font_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_font_applied, name), Toast.LENGTH_SHORT).show()
            }
            build()
        }

    private fun fontSubtitle(): String =
        TerminalFont.currentName(this) ?: getString(R.string.settings_term_font_default)

    /** Terminal colour scheme import: termux's colors.properties format, base16 off-the-shelf schemes work directly. */
    private val colorsPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val uri = r.data?.data ?: return@registerForActivityResult
            val name = TermColors.import(this, uri)
            if (name == null) {
                Toast.makeText(this, R.string.msg_colors_invalid, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_colors_applied, name), Toast.LENGTH_SHORT).show()
            }
            TermColors.applyToSessions()
            build()
        }

    private fun colorsSubtitle(): String =
        TermColors.currentName(this) ?: getString(R.string.settings_term_colors_default)

    /**
     * Backup export: the password is asked for **before** the file is picked (asking
     * afterwards would leave a 0-byte .twigbak if the user cancels midway, since
     * they've already created an empty file by then). Stash it here until we
     * actually write.
     */
    private var exportPassword: CharArray? = null

    /**
     * The destination goes through **Twig's own directory picker**, not SAF — so
     * backups land directly on SMB/WebDAV/S3, exactly matching where the tree can go.
     */
    private val backupExport =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val pw = exportPassword
            exportPassword = null
            pickedFile(r)?.let { SecurityUi.runExport(this, it, pw) }
        }

    private val backupImport =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            pickedFile(r)?.let { SecurityUi.runImport(this, it) { build() } }
        }

    /** PickerActivity's "return path" mode sends back scheme + path, reassembled into an XFile. */
    private fun pickedFile(r: androidx.activity.result.ActivityResult): com.twig.core.XFile? {
        val d = r.data ?: return null
        val scheme = d.getStringExtra(PickerActivity.EXTRA_PICKED_SCHEME) ?: return null
        val path = d.getStringExtra(PickerActivity.EXTRA_PICKED_PATH) ?: return null
        return com.twig.core.XFile(scheme, path, isDir = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = Toolbar(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.toolbar))
            setTitleTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            title = getString(R.string.action_settings)
            navigationIcon = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.ic_up)
            setNavigationOnClickListener { finish() }
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (16 * dp).toInt())
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        actionBarHeight(),
                    ),
                )
                addView(
                    ScrollView(this@SettingsActivity).apply { addView(list) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f },
                )
            },
        )
        build()
    }

    private fun actionBarHeight(): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.actionBarSize, tv, true)
        return android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
    }

    private fun build() {
        list.removeAllViews()
        thumbRows.clear()

        section(getString(R.string.settings_section_display))
        // After picking a language AppCompatDelegate recreates all Activities (including
        // this page) in the new language, so the entire page's text refreshes — no need
        // to build() ourselves.
        choiceRow(
            getString(R.string.action_language),
            LanguagePref.labels(this),
            get = { LanguagePref.current() },
            set = { LanguagePref.apply(it) },
        )
        choiceRow(
            getString(R.string.action_density),
            arrayOf(getString(R.string.density_compact), getString(R.string.density_normal), getString(R.string.density_large)),
            get = { Prefs.density(this) },
            set = { Prefs.setDensity(this, it) },
        )
        // Row height and text size are two independent preferences: row height only
        // controls row height and icon size, text size only controls the font.
        // Previously they were a single setting (making rows bigger also made the text
        // bigger), so "shorter rows but readable text" wasn't possible.
        choiceRow(
            getString(R.string.action_text_size),
            arrayOf(getString(R.string.text_size_small), getString(R.string.text_size_normal), getString(R.string.text_size_large)),
            get = { Prefs.textSize(this) },
            set = { Prefs.setTextSize(this, it) },
        )
        switchRow(
            getString(R.string.settings_show_hidden), getString(R.string.settings_show_hidden_desc),
            get = { Prefs.showHidden(this) },
            set = { Prefs.setShowHidden(this, it) },
        )
        switchRow(
            getString(R.string.settings_row_divider), getString(R.string.settings_row_divider_desc),
            get = { Prefs.rowDivider(this) },
            set = { Prefs.setRowDivider(this, it) },
        )

        section(getString(R.string.settings_section_grid))
        choiceRow(
            getString(R.string.settings_grid_mode),
            arrayOf(
                getString(R.string.settings_grid_off),
                getString(R.string.settings_grid_media),
                getString(R.string.settings_grid_all),
            ),
            get = { Prefs.thumbsGrid(this) },
            set = { Prefs.setThumbsGrid(this, it) },
        )
        switchRow(
            getString(R.string.settings_grid_names), getString(R.string.settings_grid_names_desc),
            get = { Prefs.thumbsGridNames(this) },
            set = { Prefs.setThumbsGridNames(this, it) },
        )

        section(getString(R.string.settings_section_slideshow))
        val intervals = intArrayOf(2000, 3000, 5000, 8000, 15000, 30000)
        choiceRow(
            getString(R.string.settings_slideshow_interval),
            intervals.map { getString(R.string.settings_slideshow_interval_n, it / 1000) }.toTypedArray(),
            get = { intervals.indexOf(Prefs.slideshowIntervalMs(this).toInt()).coerceAtLeast(0) },
            set = { Prefs.setSlideshowIntervalMs(this, intervals[it].toLong()) },
        )
        switchRow(
            getString(R.string.settings_slideshow_shuffle), getString(R.string.settings_slideshow_shuffle_desc),
            get = { Prefs.slideshowShuffle(this) },
            set = { Prefs.setSlideshowShuffle(this, it) },
        )
        switchRow(
            getString(R.string.settings_slideshow_loop), getString(R.string.settings_slideshow_loop_desc),
            get = { Prefs.slideshowLoop(this) },
            set = { Prefs.setSlideshowLoop(this, it) },
        )
        switchRow(
            getString(R.string.settings_slideshow_keep_awake), getString(R.string.settings_slideshow_keep_awake_desc),
            get = { Prefs.slideshowKeepAwake(this) },
            set = { Prefs.setSlideshowKeepAwake(this, it) },
        )
        val maxCounts = intArrayOf(500, 1000, 3000, 10000, 30000)
        choiceRow(
            getString(R.string.settings_slideshow_max),
            maxCounts.map { getString(R.string.settings_slideshow_max_n, it) }.toTypedArray(),
            get = { maxCounts.indexOf(Prefs.slideshowMaxImages(this)).coerceAtLeast(0) },
            set = { Prefs.setSlideshowMaxImages(this, maxCounts[it]) },
        )

        section(getString(R.string.settings_section_thumbs))
        switchRow(
            getString(R.string.settings_thumbs_switch), getString(R.string.settings_thumbs_switch_desc),
            get = { Prefs.thumbs(this) },
            set = { Prefs.setThumbs(this, it); applyEnabled() },
        )
        thumbRows += switchRow(
            getString(R.string.settings_thumbs_network),
            getString(R.string.settings_thumbs_network_desc),
            get = { Prefs.thumbsNetwork(this) },
            set = { Prefs.setThumbsNetwork(this, it) },
        )
        thumbRows += switchRow(
            getString(R.string.settings_thumbs_embedded), getString(R.string.settings_thumbs_embedded_desc),
            get = { Prefs.thumbsEmbedded(this) },
            set = { Prefs.setThumbsEmbedded(this, it) },
        )
        thumbRows += actionRow(getString(R.string.settings_clear_thumbs_cache), Format.size(Thumbs.cacheBytes(this))) { subtitle ->
            Thumbs.clearCache(this)
            subtitle.text = Format.size(0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        section(getString(R.string.settings_section_security))
        actionRow(getString(R.string.settings_master_pw), SecurityUi.masterSubtitle(this)) { sub ->
            SecurityUi.showMaster(this) { sub.text = SecurityUi.masterSubtitle(this) }
        }
        actionRow(getString(R.string.settings_backup_export), getString(R.string.settings_backup_export_desc)) { _ ->
            SecurityUi.askExportPassword(this) { pw ->
                exportPassword = pw
                backupExport.launch(
                    PickerActivity.dirIntent(this, getString(R.string.backup_pick_dir)),
                )
            }
        }
        actionRow(getString(R.string.settings_backup_import), getString(R.string.settings_backup_import_desc)) { _ ->
            backupImport.launch(
                PickerActivity.pathIntentAny(this, getString(R.string.backup_pick_file)),
            )
        }

        section(getString(R.string.settings_section_privileged))
        actionRow(getString(R.string.settings_privileged), privilegedSubtitle()) { sub ->
            val options = arrayOf(
                getString(R.string.priv_mode_off),
                getString(R.string.priv_mode_root),
                getString(R.string.priv_mode_shizuku),
            )
            AlertDialog.Builder(this)
                // ★ The description must go via setCustomTitle, not setMessage — AlertDialog's
                // content panel only fits one thing; when both message and the choice list
                // are set, the message wins and **the list is not rendered at all**.
                // The result: the dialog only shows the description and "Cancel", with no
                // selectable option, while the code looks perfectly fine (this is how it
                // shipped at 0.x — nobody could select anything).
                .setCustomTitle(dialogHeader(getString(R.string.settings_privileged), getString(R.string.priv_note_shizuku)))
                .setSingleChoiceItems(options, Privileged.active) { dlg, w ->
                    dlg.dismiss()
                    choosePrivileged(w, sub)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        section(getString(R.string.settings_section_terminal))
        actionRow(getString(R.string.settings_term_font), fontSubtitle()) { sub ->
            val custom = TerminalFont.currentName(this)
            val items = if (custom != null) {
                arrayOf(
                    getString(R.string.settings_term_font_import),
                    getString(R.string.settings_term_font_reset),
                )
            } else {
                arrayOf(getString(R.string.settings_term_font_import))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_term_font)
                .setItems(items) { _, w ->
                    if (w == 0) {
                        // MIME uses */*: many file apps don't give ttf the correct MIME, pinning it would
                        // prevent selection
                        fontPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_font)))
                    } else {
                        TerminalFont.clear(this)
                        sub.text = fontSubtitle()
                    }
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        actionRow(getString(R.string.settings_term_colors), colorsSubtitle()) { _ ->
            TermColors.showPicker(
                this,
                { colorsPicker.launch(PickerActivity.intent(this, getString(R.string.settings_term_colors))) },
                { build() },
            )
        }

        section(getString(R.string.settings_section_text))
        // The subtitle shows "the current order" rather than a feature description:
        // the description is in the dialog, while the order is the only state for this item.
        actionRow(getString(R.string.settings_text_charsets), TextCharsetPicker.subtitle(this)) { sub ->
            TextCharsetPicker.show(this) { sub.text = TextCharsetPicker.subtitle(this) }
        }

        section(getString(R.string.settings_section_player))
        val speeds = intArrayOf(150, 200, 300)
        choiceRow(
            getString(R.string.settings_longpress_speed),
            speeds.map { getString(R.string.settings_speed_n, it / 100f) }.toTypedArray(),
            get = { speeds.indexOf((Prefs.longPressSpeed(this) * 100).toInt()).coerceAtLeast(0) },
            set = { Prefs.setLongPressSpeed(this, speeds[it]) },
        )
        switchRow(
            getString(R.string.settings_auto_next), getString(R.string.settings_auto_next_sum),
            get = { Prefs.autoNextEpisode(this) },
            set = { Prefs.setAutoNextEpisode(this, it) },
        )
        switchRow(
            getString(R.string.settings_resume_playback),
            getString(R.string.settings_resume_playback_desc, PlaybackStore.MAX),
            get = { Prefs.resumePlayback(this) },
            set = { Prefs.setResumePlayback(this, it) },
        )
        actionRow(
            getString(R.string.settings_clear_playback),
            getString(R.string.settings_playback_count, PlaybackStore.all(this).size),
        ) { subtitle ->
            PlaybackStore.clear(this)
            subtitle.text = getString(R.string.settings_playback_count, 0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        section(getString(R.string.settings_section_music))
        val cacheCounts = intArrayOf(3, 5, 8, 10, 15)
        choiceRow(
            getString(R.string.settings_audio_cache_count),
            cacheCounts.map { getString(R.string.settings_audio_cache_n, it) }.toTypedArray(),
            get = { cacheCounts.indexOf(Prefs.audioCacheCount(this)).coerceAtLeast(0) },
            set = { Prefs.setAudioCacheCount(this, cacheCounts[it]); AudioCache.setMax(cacheCounts[it]) },
        )
        actionRow(getString(R.string.settings_clear_music_cache), Format.size(AudioCache.cacheBytes(this))) { subtitle ->
            AudioCache.clearCache(this)
            subtitle.text = Format.size(0)
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_SHORT).show()
        }

        section(getString(R.string.settings_section_about))
        // The subtitle shows the version directly: this item is probably tapped mostly to
        // see "which version am I on", so it's best visible without tapping.
        actionRow(getString(R.string.settings_about), versionLine()) { _ -> showAbout() }

        applyEnabled()
    }

    private fun applyEnabled() {
        val on = Prefs.thumbs(this)
        thumbRows.forEach { row ->
            row.alpha = if (on) 1f else 0.4f
            setEnabledDeep(row, on)
        }
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    // ---- Row construction ----

    private fun section(title: String) {
        list.addView(
            TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.accent))
                setPadding((8 * dp).toInt(), (18 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            },
        )
    }

    private fun addRow(
        title: String,
        subtitle: String?,
        end: View? = null,
        onClick: (() -> Unit)? = null,
    ): Pair<LinearLayout, TextView> {
        val subView = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            if (subtitle == null) visibility = View.GONE else text = subtitle
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (52 * dp).toInt()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            addView(
                LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(this@SettingsActivity).apply {
                            text = title
                            textSize = 15f
                            setTextColor(
                                ContextCompat.getColor(this@SettingsActivity, R.color.text_primary),
                            )
                        },
                    )
                    addView(subView)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
            )
            end?.let {
                addView(it)
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = (8 * dp).toInt()
            }
            onClick?.let { cb -> setOnClickListener { cb() } }
        }
        list.addView(row)
        return row to subView
    }

    private fun switchRow(
        title: String,
        subtitle: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
    ): View {
        val sw = SwitchCompat(this).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        val (row, _) = addRow(title, subtitle, sw) { sw.toggle() }
        return row
    }

    private fun choiceRow(
        title: String,
        options: Array<String>,
        get: () -> Int,
        set: (Int) -> Unit,
    ): View {
        lateinit var subRef: TextView
        val (row, sub) = addRow(title, options[get().coerceIn(0, options.size - 1)]) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, get()) { dlg, w ->
                    set(w)
                    subRef.text = options[w]
                    dlg.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
        subRef = sub
        return row
    }

    // ---- Privileged access (root / Shizuku) ----

    /**
     * Title + a description, used as the custom title for dialogs that need both a
     * description and a choice list. Hand-written layout, consistent with the rest
     * of this page (no preference library, no XML).
     */
    private fun dialogHeader(title: String, note: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((20 * dp).toInt(), (18 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        addView(
            TextView(this@SettingsActivity).apply {
                text = title
                textSize = 19f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            },
        )
        addView(
            TextView(this@SettingsActivity).apply {
                text = note
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                setPadding(0, (8 * dp).toInt(), 0, 0)
            },
        )
    }

    /**
     * The status is reported by the **actual uid obtained**, not by the mode the user
     * picked — the Shizuku service itself can be started by adb (shell, uid 2000)
     * or by root (uid 0), so the same "Shizuku" option has completely different
     * capabilities on different devices; hard-coding "runs as shell" would lie.
     */
    private fun privilegedSubtitle(): String = when {
        Privileged.active == Privileged.ROOT -> getString(R.string.priv_state_root)
        Privileged.active == Privileged.SHIZUKU && Privileged.uid == 0 ->
            getString(R.string.priv_state_shizuku_root)
        Privileged.active == Privileged.SHIZUKU ->
            getString(R.string.priv_state_shizuku, Privileged.uid)
        else -> getString(R.string.priv_state_off)
    }

    /**
     * ★ Authorisation must happen here, driven by this user action: Android 10+
     * blocks starting Activities from the background, so Magisk's authorisation
     * dialog cannot pop up from the background and degrades to a notification —
     * appearing as "tapped and nothing happened".
     */
    private fun choosePrivileged(mode: Int, sub: TextView) {
        // Each branch leaves a trace: the cases that exit in the pre-checks used to only
        // show a toast, leaving logcat blank when investigating, so we couldn't tell
        // "user didn't tap" from "tapped but blocked at the first check".
        Log.i(PRIV_TAG, "choose(mode=$mode) ${Privileged.diagnostics(this)}")
        if (mode == Privileged.OFF) {
            Privileged.disable(this)
            sub.text = privilegedSubtitle()
            return
        }
        if (mode == Privileged.SHIZUKU) {
            if (!Privileged.shizukuInstalled(this)) {
                Log.w(PRIV_TAG, "choose: shizuku not installed")
                Toast.makeText(this, R.string.priv_shizuku_not_installed, Toast.LENGTH_LONG).show()
                return
            }
            if (!Privileged.shizukuRunning()) {
                Log.w(PRIV_TAG, "choose: shizuku binder not alive")
                Toast.makeText(this, R.string.priv_err_shizuku_absent, Toast.LENGTH_LONG).show()
                return
            }
            if (!Privileged.shizukuGranted()) {
                Log.i(PRIV_TAG, "choose: not granted yet, requesting")
                requestShizuku(sub)
                return
            }
        }
        connectPrivileged(mode, sub)
    }

    /** Shizuku's authorisation result comes back asynchronously; remove the listener once used to avoid stacking them across settings page visits. */
    private fun requestShizuku(sub: TextView) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    connectPrivileged(Privileged.SHIZUKU, sub)
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.priv_err_shizuku_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        runCatching { Shizuku.requestPermission(SHIZUKU_REQ) }
            .onSuccess { Log.i(PRIV_TAG, "requestPermission sent") }
            .onFailure {
                Log.w(PRIV_TAG, "requestPermission threw", it)
                Shizuku.removeRequestPermissionResultListener(listener)
                Toast.makeText(this, "${getString(R.string.priv_err_shizuku_failed)}\n\n$it", Toast.LENGTH_LONG).show()
            }
    }

    /** [Privileged.enable] blocks until the user finishes the authorisation dialog, so it must run on a background thread. */
    private fun connectPrivileged(mode: Int, sub: TextView) {
        Toast.makeText(this, R.string.priv_connecting, Toast.LENGTH_SHORT).show()
        Thread {
            val err = Privileged.enable(applicationContext, mode)
            val diag = if (err == null) null else Privileged.diagnostics(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sub.text = privilegedSubtitle()
                if (err == null) {
                    Toast.makeText(this, R.string.priv_granted, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // On failure use a dialog instead of a toast: the cause may span several lines,
                // and this is the user's only clue to investigate / report to me; a flash
                // that disappears is no clue at all.
                AlertDialog.Builder(this)
                    .setTitle(R.string.settings_privileged)
                    .setMessage("$err\n\n$diag")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- About ----

    /**
     * The version comes from PackageManager rather than `BuildConfig` — the latter
     * requires explicitly enabling `buildFeatures.buildConfig` to generate an entire
     * class, not worth it for two fields (Connections is the same story).
     * `versionName` is nullable in the framework signature; when missing, only
     * versionCode is shown.
     */
    private fun versionLine(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        getString(R.string.about_version, pi.versionName ?: "", PackageInfoCompat.getLongVersionCode(pi))
    }.getOrDefault("")

    /**
     * About: version + licence + project URL. The URL can be **both opened and copied**
     * — not every device has a browser installed (the combination of a pure file
     * manager + TV box is not uncommon), so only offering "open" is a dead end.
     */
    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_about)
            .setMessage(getString(R.string.about_body, versionLine(), PROJECT_URL))
            .setPositiveButton(R.string.about_open) { _, _ -> openProject() }
            .setNeutralButton(R.string.about_copy) { _, _ -> copyProjectUrl() }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openProject() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) }
            .onFailure {
                // When no app can open the link, don't just say "can't open": first put
                // the URL into the clipboard so the user still has a next step
                copyProjectUrl()
                Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_LONG).show()
            }
    }

    private fun copyProjectUrl() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("twig", PROJECT_URL))
        Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show()
    }

    private fun actionRow(title: String, subtitle: String, onClick: (TextView) -> Unit): View {
        lateinit var subRef: TextView
        val (row, sub) = addRow(title, subtitle) { onClick(subRef) }
        subRef = sub
        return row
    }
}
