package com.twig.app

import android.content.Context
import android.content.res.Resources
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

/** Lightweight formatting utilities, avoiding extra dependencies. */
object Format {
    private data class DateFormats(val locale: Locale, val mode: Int, val date: DateFormat, val time: DateFormat)
    // File properties can be fetched concurrently; DateFormat is not thread-safe.
    private val dates = ThreadLocal<DateFormats>()
    private val sizeFmt = DecimalFormat("#.#")

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return "${sizeFmt.format(v)} ${units[i]}"
    }

    /**
     * Display text for size; returns null when **unknown**, callers use that to hide the
     * whole field.
     *
     * Media servers (Jellyfin/Emby) simply don't expose byte counts for entries like
     * photos — `Photo` has no `MediaSources`, and `Size` isn't in `ItemFields`, so we
     * can only probe via `Range` (see `JellyfinFileSystem.withSizes`; that step has
     * count and time gates, and stays at 0 once exceeded). Showing 0 as "0 B" is **wrong**:
     * it's not an empty file, just one we didn't ask about.
     *
     * The single entry inside single-file compression ([com.twig.fs.archive.SingleFileSystem])
     * is the second case: bz2 has no "original size" field at all, zst has one but it lives in
     * the **frame header**, so `tar --zstd -cf` (the common way to make .tar.zst) leaves it
     * empty via piped compression — at header-write time the size is still unknown. gz/xz
     * are not in this category: their length is written at the **end** of the stream and
     * back-filled after compression, so the pipe case still has it.
     *
     * ★ Criterion accepts only these two cases plus size == 0: zero-byte files really
     * exist on local / SMB, hiding those would be the other kind of wrong — at which
     * point "0 B" is exactly what should be shown.
     */
    fun sizeOrNull(file: com.twig.core.XFile): String? {
        if (file.size > 0) return size(file.size)
        if (com.twig.fs.archive.SingleFileSystem.sizeMayBeUnknown(file.scheme)) return null
        val fromMediaServer = runCatching {
            Connections.ofScheme(file.scheme)?.isMediaServer() == true
        }.getOrDefault(false)
        return if (fromMediaServer) null else size(file.size)
    }

    fun time(millis: Long, ctx: Context): String {
        if (millis <= 0) return ""
        // AppCompat's app language must not override the user's regional date order.
        val locale = Resources.getSystem().configuration.locales[0]
        val mode = Prefs.dateFormat(ctx)
        val formats = dates.get()?.takeIf { it.locale == locale && it.mode == mode }
            ?: DateFormats(
                locale, mode,
                when (mode) {
                    1 -> SimpleDateFormat("yy-MM-dd", locale)
                    2 -> SimpleDateFormat("dd.MM.yy", locale)
                    3 -> SimpleDateFormat("MM/dd/yy", locale)
                    else -> DateFormat.getDateInstance(DateFormat.SHORT, locale)
                },
                SimpleDateFormat("HH:mm", locale),
            ).also { dates.set(it) }
        // Refresh the timezone too: a running app may cross timezones without restarting.
        val zone = java.util.TimeZone.getDefault()
        formats.date.timeZone = zone
        formats.time.timeZone = zone
        val date = Date(millis)
        return "${formats.date.format(date)} ${formats.time.format(date)}"
    }

    /** Each server / repository has a unique scheme = type + hash (see PaneViewModel.schemeForConn) */
    private val SCHEME_TYPES =
        listOf("nfs", "webdav", "restic", "git", "sftp", "smb", "ftp", "dav", "s3", "jellyfin", "emby")

    /**
     * Display name for a scheme: strip the hash part of "type + hash".
     * Can't simply "take until the first non-letter" — hashes are hex and when starting
     * with a-f they'd be treated as part of the type (`sftp` + `a3f2…` would show as
     * `sftpa`). Types not in the table (file / zip / 7z / apps…) never had the hash
     * suffix to begin with, return as-is.
     */
    fun schemeLabel(scheme: String): String =
        SCHEME_TYPES.firstOrNull { scheme.startsWith(it) } ?: scheme

    /**
     * `type:/server/path` — same format as the pane's path bar. The server name uses
     * the user's label; non-server sources (zip / git / restic…) have no server name,
     * don't insert an extra slash.
     *
     * `PaneFragment` has another copy: that one prefers the local pane VM's reverse
     * lookup, so it can recognise a server the other pane hasn't expanded yet. Callers
     * that don't need that layer (the comparison page, text compare) can use this one.
     */
    fun pathLabel(f: com.twig.core.XFile): String = when {
        f.scheme == "file" -> f.path
        f.scheme == "saf" -> f.name
        else -> {
            val server = Connections.ofScheme(f.scheme)?.shortLabel().orEmpty()
            val path = if (f.path.startsWith("/")) f.path else "/${f.path}"
            val head = schemeLabel(f.scheme)
            if (server.isEmpty()) "$head:$path" else "$head:/$server$path"
        }
    }
}
