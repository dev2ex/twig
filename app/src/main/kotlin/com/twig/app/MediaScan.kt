package com.twig.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import com.twig.fs.local.LocalFileSystem

/**
 * Tells the system media library about local file create/modify/delete.
 *
 * Why it's needed: MediaStore only knows what it has scanned. The filesystem layer
 * that `java.io` writes to is not auto-detected — so after "copy an image from SMB
 * to DCIM", the gallery shows nothing until the next boot-time rescan (or the user
 * opens some other app and waits). Conversely, if a delete/rename isn't announced,
 * the gallery keeps a stale entry that errors on open.
 *
 * The hook is [LocalFileSystem.changed] (the single exit for all local writes), so
 * copy/move/extract/editor save/WiFi-share upload are all covered automatically —
 * call sites don't need to change.
 *
 * Two deliberate limits:
 * - **Only `/storage` paths are reported**. The media library wouldn't accept
 *   app-private directories or cacheDir temp files (thumbnails, 7z packing
 *   intermediates) anyway; reporting those is just a wasted IPC round-trip.
 * - **Batched**. Copying a directory fires one callback per file; opening a scan
 *   connection each time would burn IPC during the transfer. We coalesce, then
 *   send once after [QUIET_MS] of silence, or earlier if [MAX_BATCH] is reached.
 */
object MediaScan {

    private const val QUIET_MS = 800L
    private const val MAX_BATCH = 500

    private val main = Handler(Looper.getMainLooper())
    private val pending = LinkedHashSet<String>()
    private var app: Context? = null

    /** Installs the hook. Called once at process startup by [TwigApp]. */
    fun install(ctx: Context) {
        app = ctx.applicationContext
        LocalFileSystem.changed = { path -> enqueue(path) }
    }

    /** Manual notification for paths the hook doesn't cover (e.g. files written by
     * other modules via plain `java.io`). */
    fun notifyChanged(path: String) = enqueue(path)

    private fun enqueue(path: String) {
        if (!scannable(path)) return
        val flushNow: Boolean
        synchronized(pending) {
            pending += path
            flushNow = pending.size >= MAX_BATCH
        }
        main.removeCallbacks(flushTask)
        if (flushNow) main.post(flushTask) else main.postDelayed(flushTask, QUIET_MS)
    }

    private val flushTask = Runnable { flush() }

    private fun flush() {
        val ctx = app ?: return
        val batch: Array<String>
        synchronized(pending) {
            if (pending.isEmpty()) return
            batch = pending.toTypedArray()
            pending.clear()
        }
        // The scan runs inside MediaProvider; we only initiate it. If a path no
        // longer exists, MediaProvider removes the old entry — exactly what delete
        // /rename want. Failure (no permission, provider absent) must not affect the
        // file operation itself.
        runCatching { MediaScannerConnection.scanFile(ctx, batch, null, null) }
    }

    /** The media library only covers the external storage volumes; other paths
     * (app-private dir, /data, /system) are never reported. */
    private fun scannable(path: String): Boolean {
        if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) return false
        val priv = app?.getExternalFilesDir(null)?.absolutePath
        if (priv != null && path.startsWith(priv)) return false // our own patch under Android/data
        return true
    }
}
