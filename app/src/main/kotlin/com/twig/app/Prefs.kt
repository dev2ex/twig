package com.twig.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Lightweight preferences (SharedPreferences). */
object Prefs {
    private const val FILE = "twig_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_DENSITY = "row_density"
    private const val KEY_TEXT_SIZE = "row_text_size"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Row height density: 0=compact 1=normal (default) 2=roomy. Only affects row
     * height / icon size; font size is controlled by [textSize]. */
    fun density(ctx: Context): Int = sp(ctx).getInt(KEY_DENSITY, 1)

    /**
     * ★ Before changing row height, **pin [textSize] to its current value**: when it
     * was never set it tracks row height (so the split doesn't shift the look), and
     * without this pin adjusting row height would bump the font one notch too — but
     * the whole point of the split is that they're independent. The value pinned is
     * the row height level from *before* the change, so the user sees no visible jump.
     */
    fun setDensity(ctx: Context, level: Int) {
        val e = sp(ctx).edit()
        if (!sp(ctx).contains(KEY_TEXT_SIZE)) e.putInt(KEY_TEXT_SIZE, density(ctx))
        e.putInt(KEY_DENSITY, level.coerceIn(0, 2)).apply()
    }

    /**
     * List font size: 0=small 1=medium (the default, inherited from [density]) 2=large.
     *
     * ★ When it was never set it **tracks [density]** — they used to be the same
     * setting (changing row height changed both), and giving font size a hardcoded
     * default on the split would make upgrading users jump one notch on the spot
     * (someone who picked "roomy" would end up with smaller text). But this only
     * applies to the **default lookup**: once the user touches either one (sets font
     * size explicitly, or gets pinned by [setDensity] while changing row height),
     * they are fully independent — **changing row height never changes font size**.
     */
    fun textSize(ctx: Context): Int = sp(ctx).getInt(KEY_TEXT_SIZE, density(ctx))

    fun setTextSize(ctx: Context, level: Int) {
        sp(ctx).edit().putInt(KEY_TEXT_SIZE, level.coerceIn(0, 2)).apply()
    }

    /**
     * Last options for directory compare (JSON, encoding in `CompareSession.optionsToJson`);
     * empty = use defaults. Stored as a single JSON blob rather than split across
     * keys: these options are only read/written as a unit by the compare page —
     * splitting would just produce a bunch of keys to keep in sync, and adding an
     * option would mean changing three places.
     */
    fun compareOptions(ctx: Context): String = sp(ctx).getString("compare_options", "") ?: ""

    fun setCompareOptions(ctx: Context, json: String) {
        sp(ctx).edit().putString("compare_options", json).apply()
    }

    private const val KEY_REMEMBER = "remember_location"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_IMG_AUTOFIT = "img_autofit"
    private const val KEY_TERM_KEEP_AWAKE = "terminal_keep_awake"

    /** Keep screen on for the terminal page, default off (avoid pointless battery drain). */
    fun terminalKeepAwake(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_TERM_KEEP_AWAKE, false)

    fun setTerminalKeepAwake(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_TERM_KEEP_AWAKE, on).apply()
    }

    /** Terminal font size (px), remembered after pinch-zoom; 0 = never set, fall back to a screen-density-derived default. */
    fun terminalTextSize(ctx: Context): Int = sp(ctx).getInt("terminal_text_size", 0)

    fun setTerminalTextSize(ctx: Context, px: Int) {
        sp(ctx).edit().putInt("terminal_text_size", px).apply()
    }

    /** Custom terminal font path (inside app-private dir); empty = system monospace. See TerminalFont. */
    fun terminalFont(ctx: Context): String = sp(ctx).getString("terminal_font", "") ?: ""

    fun setTerminalFont(ctx: Context, path: String) {
        sp(ctx).edit().putString("terminal_font", path).apply()
    }

    /** Terminal color scheme path (inside app-private dir); empty = termux default. See TermColors. */
    fun terminalColors(ctx: Context): String = sp(ctx).getString("terminal_colors", "") ?: ""

    fun setTerminalColors(ctx: Context, path: String) {
        sp(ctx).edit().putString("terminal_colors", path).apply()
    }

    /** Text viewer's auto-wrap, default on. */
    fun viewerWrap(ctx: Context): Boolean = sp(ctx).getBoolean("viewer_wrap", true)

    fun setViewerWrap(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("viewer_wrap", on).apply()
    }

    /** Text viewer line-number gutter, default on. */
    fun viewerLineNumbers(ctx: Context): Boolean = sp(ctx).getBoolean("viewer_line_numbers", true)

    fun setViewerLineNumbers(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("viewer_line_numbers", on).apply()
    }

    /** Text viewer font size (sp), remembered after pinch-zoom; default 13 (matches the layout initial value). */
    fun viewerTextSize(ctx: Context): Float = sp(ctx).getFloat("viewer_text_size", 13f)

    fun setViewerTextSize(ctx: Context, size: Float) {
        sp(ctx).edit().putFloat("viewer_text_size", size).apply()
    }

    /** PDF reader: one page per screen instead of continuous scrolling. Default off — continuous is the reading default. */
    fun pdfPageMode(ctx: Context): Boolean = sp(ctx).getBoolean("pdf_page_mode", false)

    fun setPdfPageMode(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("pdf_page_mode", on).apply()
    }

    /** Hex viewer font size (sp), default 12 — one notch smaller than text, fits more byte columns per screen. */
    fun hexTextSize(ctx: Context): Float = sp(ctx).getFloat("hex_text_size", 12f)

    fun setHexTextSize(ctx: Context, size: Float) {
        sp(ctx).edit().putFloat("hex_text_size", size).apply()
    }

    /** Most-recent N tracks kept in the network music disk cache (playback/waveform/seek
     * share one download, no duplicates), default 5. */
    fun audioCacheCount(ctx: Context): Int = sp(ctx).getInt("audio_cache_count", 5)

    fun setAudioCacheCount(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("audio_cache_count", n.coerceIn(1, 20)).apply()
    }

    /** Code viewer color theme (CodeHighlighter.THEMES index), default 0 = Monokai. */
    fun codeTheme(ctx: Context): Int = sp(ctx).getInt("code_theme", 0)

    fun setCodeTheme(ctx: Context, i: Int) {
        sp(ctx).edit().putInt("code_theme", i).apply()
    }

    /** Show diff as top/bottom panels instead of side-by-side (default off = side-by-side, single panel on portrait). */
    fun diffStacked(ctx: Context): Boolean = sp(ctx).getBoolean("diff_stacked", false)

    fun setDiffStacked(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("diff_stacked", on).apply()
    }

    // Terminal window-change (resize) capability, remembered per server scheme (probe result)
    const val RESIZE_UNKNOWN = 0
    const val RESIZE_OK = 1
    const val RESIZE_BROKEN = 2

    /** This server's tolerance for window-change: not yet probed / supported / one send disconnects (don't send again). */
    fun termResizeCap(ctx: Context, scheme: String): Int =
        sp(ctx).getInt("term_resize_cap_$scheme", RESIZE_UNKNOWN)

    fun setTermResizeCap(ctx: Context, scheme: String, cap: Int) {
        sp(ctx).edit().putInt("term_resize_cap_$scheme", cap).apply()
    }


    /** Temporary speed-up multiplier when holding the screen, stored as percent (200 = 2×), default 2×. */
    fun longPressSpeed(ctx: Context): Float = sp(ctx).getInt("longpress_speed", 200) / 100f

    fun setLongPressSpeed(ctx: Context, percent: Int) {
        sp(ctx).edit().putInt("longpress_speed", percent).apply()
    }

    /** Remember video playback position and resume next time, default on (records see [PlaybackStore]). */
    fun resumePlayback(ctx: Context): Boolean = sp(ctx).getBoolean("resume_playback", true)

    fun setResumePlayback(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("resume_playback", on).apply()
    }

    fun imageAutoFit(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_IMG_AUTOFIT, true)

    fun setImageAutoFit(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_IMG_AUTOFIT, on).apply()
    }


    /** Fullscreen (hide system status bar), default off. */
    fun fullscreen(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FULLSCREEN, false)

    fun setFullscreen(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_FULLSCREEN, on).apply()
    }


    /** Whether to remember the last opened location (default on). */
    fun rememberLocation(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_REMEMBER, true)

    fun setRememberLocation(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_REMEMBER, on).apply()
    }

    /** Last active pane (0=left, 1=right); takes effect together with "remember location". */
    fun activePane(ctx: Context): Int = sp(ctx).getInt("active_pane", 0)

    fun setActivePane(ctx: Context, i: Int) {
        sp(ctx).edit().putInt("active_pane", i).apply()
    }

    /** Persists a pane's last location (expanded local directories + current directory). */
    fun saveLocation(ctx: Context, pane: Int, expandedPaths: List<String>, currentPath: String?) {
        sp(ctx).edit()
            .putString("loc_exp_$pane", expandedPaths.joinToString("\n"))
            .putString("loc_cur_$pane", currentPath ?: "")
            .apply()
    }

    fun locationExpanded(ctx: Context, pane: Int): List<String> =
        sp(ctx).getString("loc_exp_$pane", null)?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    fun locationCurrent(ctx: Context, pane: Int): String? =
        sp(ctx).getString("loc_cur_$pane", null)?.takeIf { it.isNotEmpty() }

    // restic backup passwords (keyed by repo path, user optional). Values are
    // encrypted on disk with Secrets; see ConnectionStore.
    const val FILE_RESTIC_PW = "twig_restic_pw"
    private fun resticSp(ctx: Context) = ctx.getSharedPreferences(FILE_RESTIC_PW, Context.MODE_PRIVATE)

    fun resticPassword(ctx: Context, repoPath: String): String? =
        resticSp(ctx).getString(repoPath, null)?.let { com.twig.app.secure.Secrets.dec(ctx, it) }

    fun setResticPassword(ctx: Context, repoPath: String, pw: String?) {
        resticSp(ctx).edit().apply {
            if (pw == null) remove(repoPath) else putString(repoPath, com.twig.app.secure.Secrets.enc(ctx, pw))
        }.apply()
    }

    /** All restic passwords (plaintext), for backup export. */
    fun resticPasswords(ctx: Context): Map<String, String> =
        resticSp(ctx).all.keys.mapNotNull { k -> resticPassword(ctx, k)?.let { k to it } }.toMap()

    // Encrypted archive passwords (keyed by archive path, user optional; same trade-off as the restic ones).
    const val FILE_ARCHIVE_PW = "twig_archive_pw"
    private fun archiveSp(ctx: Context) = ctx.getSharedPreferences(FILE_ARCHIVE_PW, Context.MODE_PRIVATE)

    fun archivePassword(ctx: Context, archivePath: String): String? =
        archiveSp(ctx).getString(archivePath, null)?.let { com.twig.app.secure.Secrets.dec(ctx, it) }

    fun setArchivePassword(ctx: Context, archivePath: String, pw: String?) {
        archiveSp(ctx).edit().apply {
            if (pw == null) remove(archivePath) else putString(archivePath, com.twig.app.secure.Secrets.enc(ctx, pw))
        }.apply()
    }

    /** All archive passwords (plaintext), for backup export. */
    fun archivePasswords(ctx: Context): Map<String, String> =
        archiveSp(ctx).all.keys.mapNotNull { k -> archivePassword(ctx, k)?.let { k to it } }.toMap()

    // ---- Thumbnails ----

    /** Thumbnail master switch, default off; when off, the list looks exactly like the pre-thumbnail era. */
    /**
     * Auto-play the next episode when one finishes. Default **on** — marathon
     * watching is the default expectation for series; users who don't want it turn
     * it off once, but defaulting off makes the feature invisible to most people.
     */
    fun autoNextEpisode(ctx: Context): Boolean = sp(ctx).getBoolean("auto_next_ep", true)

    fun setAutoNextEpisode(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("auto_next_ep", on).apply()
    }

    fun thumbs(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_on", false)

    fun setThumbs(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_on", on).apply()
    }

    /** Grid mode: 0=off (big icons in tree) 1=media files only 2=all files. */
    fun thumbsGrid(ctx: Context): Int = sp(ctx).getInt("thumbs_grid", 0)

    fun setThumbsGrid(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("thumbs_grid", mode.coerceIn(0, 2)).apply()
    }

    /** Show filenames in grid mode, default on. */
    fun thumbsGridNames(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_grid_names", true)

    fun setThumbsGridNames(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_grid_names", on).apply()
    }

    /** Generate thumbnails for network files (download full file, decode), default off;
     * when off, still reads EXIF-embedded thumbnails. */
    fun thumbsNetwork(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_network", false)

    fun setThumbsNetwork(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_network", on).apply()
    }

    /** Prefer embedded (EXIF) thumbnails, default off (embedded ones are small and look blurry when upscaled). */
    fun thumbsEmbedded(ctx: Context): Boolean = sp(ctx).getBoolean("thumbs_embedded", false)

    fun setThumbsEmbedded(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("thumbs_embedded", on).apply()
    }

    /** Show hidden files (dotfiles and dot-directories), default off. */
    fun showHidden(ctx: Context): Boolean = sp(ctx).getBoolean("show_hidden", false)

    fun setShowHidden(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("show_hidden", on).apply()
    }

    /** Draw a subtle divider between list rows, default off. */
    fun rowDivider(ctx: Context): Boolean = sp(ctx).getBoolean("row_divider", false)

    fun setRowDivider(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("row_divider", on).apply()
    }

    /**
     * Text decoding priority: when the bytes are not Unicode, try each in this order
     * (see [TextCodec]). Default is GB18030 only — a strict superset of GBK, so it
     * still matches the old scattered "UTF-8 fails, fall back to GBK" behavior.
     *
     * ★ Empty list (user cleared all candidates) and "never set" are two different
     * things: the first stores an empty string, the second has no key at all; only
     * the second gets the default. Writing `getString(key, "GB18030")` would make it
     * pop back up after a clear-then-reboot.
     *
     * ★ `GBK` is **rewritten to GB18030 on read** rather than dropped: it was in the
     * candidate list until 0.98.0, so it sits in existing installs' preferences, and
     * the picker only keeps names that are still candidates — silently filtering it
     * out would leave those users with an empty list and no Chinese decoding at all.
     */
    fun textCharsets(ctx: Context): List<String> {
        val raw = sp(ctx).getString("text_charsets", null) ?: return listOf("GB18030")
        return raw.split(',').filter { it.isNotEmpty() }
            .map { if (it == "GBK") "GB18030" else it }
            .distinct()
    }

    fun setTextCharsets(ctx: Context, names: List<String>) {
        sp(ctx).edit().putString("text_charsets", names.joinToString(",")).apply()
    }

    /**
     * Privileged access mode: [Privileged.OFF] / [Privileged.ROOT] / [Privileged.SHIZUKU],
     * default off. Remembering the choice is so the next launch auto-connects —
     * **not** a guarantee that authorization is still valid: Magisk can be "one-shot",
     * and Shizuku re-handshakes after service restart, so restoration always retries
     * the connect and silently falls back to OFF on failure.
     */
    fun privilegedMode(ctx: Context): Int = sp(ctx).getInt("privileged_mode", Privileged.OFF)

    fun setPrivilegedMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("privileged_mode", mode).apply()
    }

    /** Signature of preferences that affect main UI layout; when it changes after
     * returning from settings, MainActivity recreates. */
    fun uiSignature(ctx: Context): String = listOf(
        density(ctx), textSize(ctx), thumbs(ctx), thumbsGrid(ctx), thumbsGridNames(ctx),
        showHidden(ctx), rowDivider(ctx),
    ).joinToString(",")

    /** Last active queue id (NOW or a named playlist), used by cold-start resume to locate the right list. */
    fun lastQueueId(ctx: Context): String? = sp(ctx).getString("last_queue_id", null)?.takeIf { it.isNotEmpty() }

    fun setLastQueueId(ctx: Context, id: String) {
        sp(ctx).edit().putString("last_queue_id", id).apply()
    }

    // ---- Slideshow ----

    /** Slideshow autoplay interval (ms), default 3000. */
    fun slideshowIntervalMs(ctx: Context): Long = sp(ctx).getLong("slideshow_interval_ms", 3000L)

    fun setSlideshowIntervalMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong("slideshow_interval_ms", ms).apply()
    }

    /** Default to shuffle when entering slideshow from a directory menu, default off (toggleable mid-playback). */
    fun slideshowShuffle(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_shuffle", false)

    fun setSlideshowShuffle(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_shuffle", on).apply()
    }

    /** After playing through in order, loop back to the start, default on; off = stop on the last slide. */
    fun slideshowLoop(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_loop", true)

    fun setSlideshowLoop(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_loop", on).apply()
    }

    /** Keep screen on during autoplay, default on. */
    fun slideshowKeepAwake(ctx: Context): Boolean = sp(ctx).getBoolean("slideshow_keep_awake", true)

    fun setSlideshowKeepAwake(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("slideshow_keep_awake", on).apply()
    }

    /** Cap on images found by recursive scan (prevents unbounded scans of huge trees), default 3000. */
    fun slideshowMaxImages(ctx: Context): Int = sp(ctx).getInt("slideshow_max_images", 3000)

    fun setSlideshowMaxImages(ctx: Context, n: Int) {
        sp(ctx).edit().putInt("slideshow_max_images", n).apply()
    }

    /**
     * Stable device identifier for this device, generated on first use and immutable after.
     *
     * Jellyfin / Emby use it to tell "which device is playing" — if it changes
     * every connection, the server accumulates a long list of one-shot device
     * records, and "now playing" sessions can't reconnect to the previous one.
     * Not using `ANDROID_ID`: that's a permission-gated privacy identifier, and
     * here we just need a random string we can recognize ourselves.
     */
    fun deviceId(ctx: Context): String {
        sp(ctx).getString("device_id", null)?.let { if (it.isNotEmpty()) return it }
        val id = java.util.UUID.randomUUID().toString()
        sp(ctx).edit().putString("device_id", id).apply()
        return id
    }

    /** Theme mode, value is AppCompatDelegate.MODE_NIGHT_*; default follow system. */
    fun themeMode(ctx: Context): Int =
        sp(ctx).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt(KEY_THEME, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
