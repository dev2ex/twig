package com.twig.app

import android.content.Context
import java.io.File

/**
 * Capacity management for the app's cache directory.
 *
 * Places that pile things into `cacheDir`:
 * - `arc/` — archives that must land on disk before they can be read (RAR: junrar only
 *   accepts local files; nested packages with high compression: seeking through the
 *   outer decompression stream degrades into repeated full decompression); see
 *   [ui.PaneViewModel]'s localArchive.
 * - `open/` — materialised copies for "open with another app"; see [OpenFiles.materialize].
 * - `restic/` — restic repository index ciphertext; see [ResticCache] (content-addressed,
 *   never expires).
 * - `thumbs/` — thumbnails with their own LRU (see `Thumbs.trim`), not managed here.
 *
 * The first two previously **only grew**. Browsing several large rars on SMB could push
 * `cacheDir` up to several GB; the system only clears cacheDir when storage is tight, and
 * until then the user sees "this file manager is taking 5 GB" in system settings. Here we
 * give them a cap, and trim oldest-first by last-access time when exceeded.
 */
object CacheDirs {

    const val ARCHIVES = "arc"
    const val OPEN = "open"
    const val RESTIC = "restic"

    /** Cap on the materialised cache (each subdirectory is counted independently). */
    private const val CAP = 512L * 1024 * 1024

    /** After exceeding the cap, trim down to this water level; don't delete one file at a time only to immediately exceed again. */
    private const val LOW_WATER = 8 / 10.0

    fun dir(ctx: Context, name: String): File =
        File(ctx.cacheDir, name).apply { mkdirs() }

    /**
     * Trim on demand: when the total exceeds [CAP], delete from oldest to newest by
     * `lastModified` (refreshed on read, approximates LRU) until 80% water level.
     * The caller just needs to invoke this once **after writing** — both call sites
     * are already doing a large file copy, so one more listFiles() is negligible.
     *
     * [keep] is the file just written and about to be used, which must never be deleted:
     * when a single file already exceeds the cap (e.g. a 1 GB rar with a 512 MB cap),
     * without excluding it the loop would keep deleting until that file is gone too,
     * leaving the caller holding a path pointing at a deleted file.
     */
    fun trim(dir: File, keep: File? = null, cap: Long = CAP) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= cap) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val len = f.length()
            if (!f.delete()) continue
            total -= len
            if (total <= cap * LOW_WATER) break
        }
    }

    /** Current usage (settings page display / diagnostics). */
    fun bytes(ctx: Context, name: String): Long =
        File(ctx.cacheDir, name).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
