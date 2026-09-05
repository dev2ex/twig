package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Thumbnail engine: in-memory LruCache + on-disk cache (`cacheDir/thumbs`, 100 MB LRU cap).
 *
 * Cache key = md5(name:size:mtime) — path-independent, so the same file hit under a
 * different mount point / path still hits the same cache, and changing the file invalidates it.
 *
 * Generation rules (max edge length [MAX_EDGE]):
 * - Images (network sources): **always try the EXIF embedded thumbnail first** (just reads
 *   the first 256 KB; bandwidth negligible, and unaffected by the "prefer embedded
 *   thumbnail" toggle — that toggle only governs the quality/speed trade-off for local
 *   files, where full decoding is already cheap; on the network trying the embedded
 *   image first is harmless). If it hits, use it without touching the network. With no
 *   embedded image (not a jpg, or jpg without one) the "generate thumbnails for network
 *   files" toggle kicks in — off means give up (that's by design, not a bug), on means
 *   download and decode the whole image. **This is the key to network thumbnail speed**:
 *   most camera/phone JPGs carry an embedded image, so the first try hits often, and
 *   blindly downloading the whole image based on the toggle alone is why many people
 *   think it's "very slow".
 * - Images (local/SAF): "prefer embedded thumbnail" on — try embedded first; off —
 *   decode a downsampled full image directly (local cost is small; unlike network, no
 *   bandwidth/latency concerns). If the original is already ≤256, use the decode result
 *   directly without saving a separate thumbnail file (saves disk and a second lossy pass).
 * - Video frame: take the frame at 1/[VIDEO_FRAME_DIVISOR] of the duration rather than
 *   the start ([pickRepresentativeFrame]) — the start is usually a black fade-in or
 *   title logo, identical across every episode of the same show, not representative.
 *   Fall back to time 0 only if that fails. Local/SAF uses
 *   `MediaMetadataRetriever.setDataSource(String)` — the retriever can seek to any time
 *   directly, no extra prep needed. Network sources also obey the "generate for network
 *   files" toggle ([genVideoNetworkFrame]): precisely read the file head [VIDEO_HEAD_CAP]
 *   (containing ftyp, the keyframe data near time 0, final fallback when 1/10 isn't
 *   available) + the moov body itself (container metadata, commonly either "faststart"
 *   at the head or "live-recording, moov patched in at the end" at the tail; precise
 *   offset/size come from [scanTopBoxes] reading only the 8/16-byte box header and
 *   jumping by box size, NOT a guess at a tail size: long/high-bitrate videos can have
 *   moovs over a dozen MB, guess too small and the whole thing can't be fetched;
 *   fallback is to guess `VIDEO_TAIL_FALLBACK_CAP` when scan fails) + the keyframe data
 *   at the target time (precisely computed by [findKeyframeOffset] via **exact moov
 *   sample-table parsing** to compute the true byte offset, then download a small
 *   targeted window — the older approach of "estimate by linear mdat size ratio" had
 *   up to 9 MB error on VBR videos (uneven bitrate across segments), a fixed window
 *   couldn't cover it at all — that was the real reason "grabbing 1/10 often failed
 *   and fell back to a black opening"; cross-checked with ffprobe's real keyframe
 *   positions, the sample-table-derived offset matched exactly). A "retry at another
 *   time when black frame" layer used to exist, but with precise sample-table
 *   positioning the hit rate is already high, so the retry only adds cost with no
 *   benefit, and was removed.
 *   The MP4 precise path above **requires ISO BMFF moov/mdat** (ISO 14496-12); MKV/AVI
 *   and other containers don't expose moov. For those: if the source supports efficient
 *   random read ([FileSystem.randomAccessEfficient], SMB pread / WebDAV Range), hand
 *   a **true random access + block cache** data source ([NetVideoDataSource]) to MMR so
 *   it can demux + seek itself (the internal MediaExtractor natively supports MKV;
 *   HEVC goes through hardware MediaCodec), reading only the bytes it needs; sources
 *   without efficient random read (FTP/SFTP) only get the head fed, basically only
 *   time 0 (may be black), but never pull the whole file down.
 *   **The MP4 precise path deliberately does NOT use [FileSystem.openRandom] and let
 *   the retriever seek on demand** — tried it, the retriever scatters many small
 *   seek+read calls, and when moov is at the tail, if the source degenerates to
 *   "reopen + skip" that's essentially transmitting the whole file; so for MP4 we
 *   precisely fetch the segments we need (fixed call count). MKV via [NetVideoDataSource]
 *   uses a block cache + cumulative read cap to constrain the same cost.
 *   Also discovered that no-arg `frameAtTime` (equivalent to
 *   `getFrameAtTime(-1, OPTION_CLOSEST_SYNC)`) combined with a custom `MediaDataSource`
 *   cleanly returns null fairly often (data is clearly complete, no exception, no
 *   timeout) — switched entirely to explicit time points + two OPTION attempts; no more
 *   no-arg version.
 * - Audio covers (mp3/flac/m4a, etc.): try the embedded cover first (ID3 APIC / FLAC
 *   PICTURE / m4a covr, parsed by MMR — local gets a path, network gets an on-demand
 *   random-access data source); no embedded cover → fall back to cover/folder/front/
 *   albumart.{jpg|png|webp} in the same directory (common album-folder convention; the
 *   directory-cache the lookup result so a directory with dozens of songs only does
 *   the directory listing / cover download once). Network sources obey the "generate
 *   for network files" toggle.
 * - PDF first page: requires a real, locally random-accessible file (PdfRenderer needs
 *   an fd); network sources fail synchronously in [eligible], never entering the thread
 *   pool queue.
 * - **App icons (APK files / "Apps" tree entries) are NOT produced here**: icons come
 *   from [FileIcons] asking the PackageManager directly (also async + cached), unaffected
 *   by the thumbnail toggle and always shown. Routing them through the thumbnail
 *   pipeline just regenerates the same image, burns another chunk of disk cache, and
 *   uses another slot in the generation queue — pointless.
 *
 * Network protocol overhead itself also drags on speed — unrelated to the thumbnail
 * logic here but worth knowing: `FtpFileSystem` openInput/list every time means connect
 * and disconnect on demand (every file re-handshakes + re-logs), no connection pool.
 * SFTP (persistent SSH connection) / WebDAV (OkHttp connection pool) / SMB (persistent
 * smb-io connection) all reuse connections, usually much faster than FTP. Background
 * concurrency is fixed at 2 ([executor]) — not strictly serial, but not high either.
 *
 * First failure only carries a [FAIL_COOLDOWN_MS] cooldown, no immediate blacklist:
 * network failures are often one-off (weak-network timeout, connection reset — SMB's
 * pre-0.45.4 thread-serialization bug hit this often), so once the cooldown passes the
 * next bind retries, rather than being unable to ever generate again because of a
 * single network hiccup. But if it fails **again** after the cooldown (i.e.
 * [BLACKLIST_THRESHOLD] consecutive failures), the file itself is probably not
 * decodable, not network noise — especially a video failure will eat the full
 * [VIDEO_TIMEOUT_MS] (15s); under pure in-memory cooldown, every restart / cooldown
 * expiration will burn 15s on the same file. In that case, write to the persistent
 * [blacklist] (`cacheDir/thumbs_blacklist`, stores the key rather than path so a
 * replaced/fixed file naturally gets a new key and isn't wrongly blamed), and skip
 * forever after, until the user manually clears cache ([clearCache]).
 *
 * When a directory collapses, `PaneViewModel` calls [cancelPending] to drop not-yet-started
 * tasks (under that directory, including still-expanded subdirectories / archives,
 * recursive) from the thread-pool queue; already-running tasks are not interrupted
 * — they finish normally and still land in the cache (effectively prewarming; next
 * expansion hits immediately).
 */
object Thumbs {

    private const val MAX_EDGE = 256
    private const val HEAD_BYTES = 256 * 1024 // EXIF embedded thumbnail: read the file head only.
    private const val NET_DECODE_CAP = 64L * 1024 * 1024 // Size cap for decoding whole network images.
    private const val DISK_CAP = 100L * 1024 * 1024 // Disk cache cap (LRU-trim to 80% when exceeded).
    private const val FAIL_COOLDOWN_MS = 60_000L // Failure cooldown: don't retry within, allow retry after.

    /** Read buffer for whole network downloads. **This is empirically the key
     * bottleneck for SMB (and other network sources) thumbnails**: Kotlin's
     * `InputStream.readBytes()` defaults to 8KB chunks, which for a network stream
     * means a 5 MB photo requires 600+ round-trips — each one a `NativeSmbClient.exec()`
     * thread-scheduling round-trip plus an SMB2 Read request/response; multiplied by
     * hundreds that's hundreds of ms to several seconds of latency amplification even
     * on a LAN. Bumping to 256 KB cuts the round-trips to a handful of dozen. */
    private const val NET_READ_CHUNK = 256 * 1024
    private val JPG = setOf("jpg", "jpeg")

    private val mem = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** key -> last failure timestamp; no retry within the cooldown window (avoids repeatedly opening a bad file / slow network while scrolling), allowed after — network failures are often one-off, a file shouldn't be permanently blacklisted. */
    private val failed = Collections.synchronizedMap(HashMap<String, Long>())

    /** key -> consecutive failure count (cleared on success); used to decide whether to escalate into [blacklist]. */
    private val failCount = Collections.synchronizedMap(HashMap<String, Int>())

    /** Persistent blacklist written after [BLACKLIST_THRESHOLD] consecutive failures — single failures still retry via the [FAIL_COOLDOWN_MS] cooldown (might just be network noise), but if it fails again after cooldown expires, the file itself is probably undecodable (e.g. unsupported codec, corrupt file), and a single video decode failure burns the full [VIDEO_TIMEOUT_MS] (15s) — under pure in-memory cooldown, every app restart / cooldown expiration will eat another 15s on the same file. After persistence it's no longer affected by restart / cooldown, only clearing cache manually ([clearCache]) gives it another chance. The key includes size+mtime, so a replaced/fixed file naturally gets a new key and isn't falsely blamed. */
    private const val BLACKLIST_THRESHOLD = 2
    private const val BLACKLIST_FILE = "thumbs_blacklist"
    private var blacklist: MutableSet<String>? = null

    private fun blacklistFile(ctx: Context) = File(ctx.cacheDir, BLACKLIST_FILE)

    private fun loadBlacklist(ctx: Context): MutableSet<String> {
        blacklist?.let { return it }
        synchronized(this) {
            blacklist?.let { return it }
            val loaded = runCatching {
                blacklistFile(ctx).readLines().filter { it.isNotBlank() }
            }.getOrNull() ?: emptyList()
            val set = Collections.synchronizedSet(loaded.toMutableSet())
            blacklist = set
            return set
        }
    }

    private fun addToBlacklist(ctx: Context, key: String) {
        val set = loadBlacklist(ctx)
        if (!set.add(key)) return
        runCatching { blacklistFile(ctx).appendText("$key\n") }
    }

    private fun recentlyFailed(ctx: Context, key: String): Boolean {
        if (loadBlacklist(ctx).contains(key)) return true
        val at = failed[key] ?: return false
        return System.currentTimeMillis() - at < FAIL_COOLDOWN_MS
    }

    /** key -> views waiting for fill-back (main thread only); when a task is already running, later views just queue. */
    private val waiters = HashMap<String, MutableList<ImageView>>()

    /** key -> submitted but possibly not-yet-running tasks (main thread only, same constraints as [waiters]).
     * When a directory collapses, used to drop not-yet-started tasks from the thread-pool queue ([cancelPending]); tasks already running are not affected — don't force-interrupt them, let them finish and clean up their entry normally. */
    private val queued = HashMap<String, Runnable>()

    /** 2 threads in parallel, FIFO queue (first submitted, first processed). */
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "twig-thumbs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Last time the disk cache was trimmed. Was originally a one-shot boolean: the whole
     * process trimmed exactly once, so the 100 MB cap was effectively useless for long
     * sessions browsing through large media directories until the next cold start.
     * Switched to interval-based re-running.
     */
    @Volatile private var lastTrimAt = 0L
    private const val TRIM_INTERVAL_MS = 10 * 60 * 1000L

    fun canThumb(f: XFile): Boolean =
        if (f.isDir) {
            // Directories normally have no thumbnail, but media-server
            // episodes/albums/photo-albums **are themselves directories**, and they
            // have ready-made posters (see [hasCover]). Sorting is unaffected: in
            // `SortRules.groupOf` isDir is the higher-priority bucket, so directories
            // still come before files and still take a full row.
            hasCover(f)
        } else {
            OpenFiles.isImage(f) || OpenFiles.isVideo(f) || OpenFiles.isAudio(f) ||
                f.extension == "pdf" || hasCover(f)
        }

    /** Whether this entry's source can provide its own cover (Jellyfin / Emby poster). */
    /** Source has its own cover (media-server poster); whether a directory produces an image and whether a file uses the poster aspect ratio both depend on this. */
    fun hasCover(f: XFile): Boolean =
        runCatching { FsRegistry.of(f) is com.twig.core.CoverSource }.getOrDefault(false)

    /** Pre-check whether this file has any chance of generating successfully; network-source PDFs inevitably fail (require a real, locally random-accessible file), not worth queuing in the background thread pool — otherwise they'd block behind slower network image downloads in the same pool, just queueing pointlessly. Videos obey the "generate thumbnails for network files" toggle (just like images, going over the network means real bandwidth), unlike PDFs which are fundamentally unable to do it. */
    private fun eligible(ctx: Context, file: XFile): Boolean {
        val localish = file.scheme == "file" || file.scheme == "saf"
        return when {
            // ★ Covers are NOT constrained by the "generate thumbnails for network
            // files" toggle — same reasoning as reading the EXIF embedded image for
            // network images: that toggle blocks "downloading the media file's own
            // bytes for one thumbnail" (a video costs several MB), whereas posters
            // are server-side, on-demand, already resized small (tens of KB) — same
            // magnitude as the JSON the list page already pulls. If we gated them,
            // after adding a media server the list would just be a sea of generic
            // file icons — this whole feature would be pointless.
            hasCover(file) -> true
            OpenFiles.isImage(file) -> true
            OpenFiles.isVideo(file) -> localish || Prefs.thumbsNetwork(ctx)
            OpenFiles.isAudio(file) -> localish || Prefs.thumbsNetwork(ctx)
            file.extension == "pdf" -> localish
            else -> false
        }
    }

    /**
     * Asynchronously bind a thumbnail; on memory-cache hit fill immediately, otherwise
     * keep the placeholder icon and load / generate in the background.
     *
     * @param growPx Once the image arrives, allow this cell to **grow to this width**
     *   (px); 0 = keep the caller-given size. Used for directories: most directories
     *   have no cover, and growing on bind would make a screenful of folder icons
     *   individually huge (users have reported "looks really big"), so **stay small
     *   first, then grow once the cover actually arrives** — [fillAspect] already
     *   changes `layoutParams` in a `post{}`, so adjusting the width in the same
     *   pass doesn't cost an extra layout.
     */
    fun bind(view: ImageView, file: XFile, growPx: Int = 0) {
        view.setTag(R.id.thumb_grow, growPx)
        if (!canThumb(file)) return
        val key = keyOf(file)
        mem.get(key)?.let { fill(view, it, key); return }
        val ctx = view.context.applicationContext
        if (recentlyFailed(ctx, key)) return
        if (!eligible(ctx, file)) { failed[key] = System.currentTimeMillis(); return }
        view.tag = key
        waiters[key]?.let { it.add(view); return }
        waiters[key] = arrayListOf(view)
        val job = Runnable {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTrimAt > TRIM_INTERVAL_MS) {
                lastTrimAt = now
                runCatching { trim(diskDir(ctx)) }
            }
            val bmp = runCatching { load(ctx, file, key) }.getOrNull()
            if (bmp == null) {
                failed[key] = System.currentTimeMillis()
                val count = (failCount[key] ?: 0) + 1
                failCount[key] = count
                if (count >= BLACKLIST_THRESHOLD) addToBlacklist(ctx, key)
            } else {
                failed.remove(key)
                failCount.remove(key)
                mem.put(key, bmp)
            }
            main.post {
                queued.remove(key)
                val views = waiters.remove(key) ?: return@post
                if (bmp != null) views.forEach { v -> if (v.tag == key) fill(v, bmp, key) }
            }
        }
        queued[key] = job
        executor.execute(job)
    }

    /** Called when a directory collapses: drop not-yet-started generation tasks for [files] from the thread pool queue, so we're not queuing work for rows that are no longer visible. Tasks already running are unaffected (not force-interrupted). */
    fun cancelPending(files: Collection<XFile>) {
        for (f in files) {
            val key = keyOf(f)
            val job = queued[key] ?: continue
            if (executor.remove(job)) {
                queued.remove(key)
                waiters.remove(key)
            }
        }
    }

    /** Disk cache usage (shown on the settings page). */
    fun cacheBytes(ctx: Context): Long = diskDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearCache(ctx: Context) {
        mem.evictAll()
        failed.clear()
        failCount.clear()
        synchronized(this) { blacklist = mutableSetOf() }
        blacklistFile(ctx).delete()
        synchronized(dirCoverBytes) { dirCoverBytes.clear() }
        diskDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Single-file "refresh thumbnail": clear memory/disk cache and failure/blacklist marks; the next bind will regenerate. User-visible forced-refresh entry (menu item), unlike [clearCache]'s full wipe this only affects this one file. */
    fun invalidate(ctx: Context, file: XFile) {
        val key = keyOf(file)
        mem.remove(key)
        failed.remove(key)
        failCount.remove(key)
        val set = loadBlacklist(ctx)
        if (set.remove(key)) {
            runCatching {
                blacklistFile(ctx).writeText(set.joinToString("") { "$it\n" })
            }
        }
        runCatching { File(diskDir(ctx), key).delete() }
    }

    /** Directory "refresh thumbnails": a background thread walks recursively, calling [invalidate] on each file; on completion [onDone] runs on the main thread (usually used to refresh the list display). Walking a network directory can be slow and must not happen on the main thread — reuse [executor]. */
    fun invalidateDir(ctx: Context, dir: XFile, onDone: () -> Unit = {}) {
        executor.execute {
            runCatching { walkInvalidate(ctx, dir) }
            main.post(onDone)
        }
    }

    private fun walkInvalidate(ctx: Context, dir: XFile) {
        // ★ Directories themselves may have covers (media-server episodes/seasons/
        // albums/photo-albums/collections/libraries, see [hasCover]), and their key is
        // `scheme:path:mtime` — recursing only children would never clear a poster
        // change on the server; symptom: "Refresh thumbnails does nothing for shows/music".
        invalidate(ctx, dir)
        val list = runCatching { FsRegistry.of(dir).list(dir) }.getOrDefault(emptyList())
        for (f in list) {
            if (f.isDir) walkInvalidate(ctx, f) else invalidate(ctx, f)
        }
    }

    // ---- Internals ----

    /** Tree-style rows (with an `infoBox` sibling view) adjust height to the image's aspect ratio — no crop, or top-crop; see [fillAspect]; grid cells and other contexts keep the original fixed square + CENTER_CROP. */
    private fun fill(view: ImageView, bmp: Bitmap, key: String) {
        view.setPadding(0, 0, 0, 0)
        val box = (view.parent as? ViewGroup)?.findViewById<View>(R.id.infoBox)
        if (box == null) {
            view.tag = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
        } else {
            fillAspect(view, bmp, box, key)
        }
    }


    /** Tree-list thumbnail: width unchanged, height computed from the image's aspect ratio — landscape images get shorter and show fully (no crop); portrait images get taller, capped at the right-side info area's ([box]) current height; over the cap, top-crop fills it (never shorter than the current square width, since a portrait ratio naturally yields height ≥ width). Wait for [box] to finish this pass's layout ([View.post]) before reading its measured height — avoid reading the previous bind's stale value; use [key] as the tag check, if the view was recycled to another file before `post` fires, give up (prevents cross-pollination of images). */
    private fun fillAspect(view: ImageView, bmp: Bitmap, box: View, key: String) {
        val lp = view.layoutParams
        // Directory covers (movie/show posters are 2:3 portrait): at bind time still the small-icon size, only grow when the image actually arrives.
        val grow = (view.getTag(R.id.thumb_grow) as? Int) ?: 0
        val w = if (grow > 0) grow else lp.width
        val bw = bmp.width
        val bh = bmp.height
        if (w <= 0 || bw <= 0 || bh <= 0) {
            view.tag = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
            return
        }
        val marker = "aspect:$key"
        view.tag = marker
        view.post {
            if (view.tag != marker) return@post
            view.tag = null
            // The "grow" path needs a more generous height cap, otherwise a 2:3 poster
            // (natural = 1.5w) gets clamped back to a square by maxOf(box.height, w)
            // and top/bottom get MATRIX-cropped — defeating the point of a portrait.
            // Still keep an upper bound (2× width) so an extreme long-strip image
            // can't blow a row up to half the screen.
            val maxH = if (grow > 0) maxOf(box.height, grow * 2) else maxOf(box.height, w)
            val natural = (w.toLong() * bh / bw).toInt().coerceAtLeast(1)
            val h = minOf(natural, maxH)
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                view.layoutParams = lp
            }
            if (natural <= maxH) {
                view.scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                val scale = w.toFloat() / bw
                view.scaleType = ImageView.ScaleType.MATRIX
                view.imageMatrix = Matrix().apply { setScale(scale, scale) }
            }
            view.setImageBitmap(bmp)
        }
    }

    private fun keyOf(f: XFile): String {
        // Files use "name:size:mtime" — path-independent, so opening the same file
        // from a different source still hits.
        // ★ Directories CANNOT use this: they have no byte size (always 0), and
        // mtime is often the same across siblings, so a whole layer of
        // episodes/albums would share one key and all covers look identical.
        // Directories switch to scheme+path.
        // ★★ Media-server **files** also can't use this (2026-08-19): the same movie
        // appears in both "Continue watching" and the Movies library, with identical
        // name/size/mtime — but the two locations need **different** images (the
        // former is a horizontal still, the latter a vertical poster). Sharing one
        // key means they overwrite each other; symptom: "the same film looks
        // horizontal sometimes and vertical other times". Their paths carry virtual
        // directory prefixes so they're already distinct; "same file from another
        // source still hits" doesn't apply to GUID-path virtual trees anyway.
        val seed = if (f.isDir || hasCover(f)) {
            "${f.scheme}:${f.path}:${f.lastModified}"
        } else {
            "${f.name}:${f.size}:${f.lastModified}"
        }
        val d = MessageDigest.getInstance("MD5").digest(seed.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun diskDir(ctx: Context): File = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    private fun load(ctx: Context, file: XFile, key: String): Bitmap? {
        val disk = File(diskDir(ctx), key)
        if (disk.isFile) {
            BitmapFactory.decodeFile(disk.path)?.let {
                disk.setLastModified(System.currentTimeMillis()) // Disk LRU: record one use.
                return it
            }
        }
        val raw = generate(ctx, file) ?: return null
        // Image itself is already ≤256: no need to save a separate thumbnail file
        // (saves disk + avoids a second lossy JPEG pass), hand the decode result
        // straight to the memory cache.
        if (OpenFiles.isImage(file) && maxOf(raw.width, raw.height) <= MAX_EDGE) return raw
        val bmp = scaleTo(raw)
        val png = bmp.hasAlpha()
        val bytes = ByteArrayOutputStream().let { bos ->
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, bos)
            bos.toByteArray()
        }
        runCatching { disk.writeBytes(bytes) }
        return bmp
    }

    private fun generate(ctx: Context, file: XFile): Bitmap? = when {
        // Source-supplied poster first: for movies/shows it's both faster and better-
        // looking than "download several MB and extract a frame"; for directories
        // (episodes/albums/photo-albums) it's the only way to produce an image at all.
        hasCover(file) -> genCover(file) ?: genFallback(ctx, file)
        else -> genFallback(ctx, file)
    }

    /** Server-side pre-made poster; returns null on miss (no primary image / request failed), caller falls back. */
    private fun genCover(file: XFile, maxEdge: Int = MAX_EDGE): Bitmap? = runCatching {
        val src = FsRegistry.of(file) as? com.twig.core.CoverSource ?: return null
        src.openCover(file, maxEdge)?.use { BitmapFactory.decodeStream(it) }
    }.getOrElse {
        Log.w("twig", "thumbs: cover failed for ${file.name}: ${it.message}")
        null
    }

    private fun genFallback(ctx: Context, file: XFile): Bitmap? = when {
        OpenFiles.isImage(file) -> genImage(ctx, file)
        OpenFiles.isVideo(file) -> genVideo(ctx, file)
        OpenFiles.isAudio(file) -> genAudio(file)
        file.extension == "pdf" -> genPdf(ctx, file)
        else -> null
    }

    private fun genImage(ctx: Context, file: XFile): Bitmap? {
        val localish = file.scheme == "file" || file.scheme == "saf"
        if (!localish) {
            // Network sources: the embedded thumbnail only reads tens of KB of the
            // head, near-zero cost — always try it first. Not constrained by the
            // "prefer embedded thumbnail" toggle (that toggle is the
            // quality/speed trade-off for local files, where full decode is already
            // cheap). For network there's no reason not to try: most camera/phone
            // JPGs carry an embedded thumbnail, hitting first avoids downloading
            // the full image — the key to network thumbnail speed. Downloading the
            // full image just based on the "generate for network files" toggle is
            // why many people think it's "very slow".
            embedded(file)?.let { return it }
            if (!Prefs.thumbsNetwork(ctx)) return null // Toggle off and no embedded image: don't download.
            if (file.size > NET_DECODE_CAP) return null
            val bytes = FsRegistry.of(file).openInput(file).use { readCapped(it, NET_DECODE_CAP, file.size) }
            val bmp = decodeSampled { ByteArrayInputStream(bytes) } ?: return null
            return rotate(bmp, if (file.extension in JPG) orientationOf(bytes) else 0)
        }
        if (Prefs.thumbsEmbedded(ctx)) embedded(file)?.let { return it }
        val fs = FsRegistry.of(file)
        val bmp = decodeSampled { fs.openInput(file) } ?: return null
        return rotate(bmp, if (file.extension in JPG) orientationOf(head(file)) else 0)
    }

    /** jpg's EXIF embedded thumbnail (reads the file head only); null if not present. */
    private fun embedded(file: XFile): Bitmap? {
        if (file.extension !in JPG) return null
        val bytes = head(file) ?: return null
        return runCatching {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            exif.thumbnail?.let { t ->
                BitmapFactory.decodeByteArray(t, 0, t.size)
                    ?.let { rotate(it, orientationDegrees(exif)) }
            }
        }.getOrNull()
    }

    /** Precisely read two segments for network video: the file head [VIDEO_HEAD_CAP] (containing ftyp, and when moov is at the tail, the keyframe data immediately after) + the moov body itself — the moov's true offset/size are computed exactly by [scanMoovBox] scanning box headers, NOT guessed at a fixed tail size: long/high-bitrate videos can have moovs (sample tables, roughly proportional to frame count) of over a dozen MB, a fixed 4 MB guess is enough for small files but completely misses large ones — that's the real reason "most mp4s generate but a few (especially long/large ones) don't". When the scan fails (malformed structure), fall back to guessing [VIDEO_TAIL_FALLBACK_CAP]. */
    private const val VIDEO_HEAD_CAP = 8L * 1024 * 1024
    private const val VIDEO_TAIL_FALLBACK_CAP = 4L * 1024 * 1024
    private const val VIDEO_MOOV_CAP = 64L * 1024 * 1024 // Sane upper bound on moov size; give up if absurdly large, don't download forever.

    /** Below this, slimming the moov ([planSlimMoov]) is not worth the extra round trips — the whole thing is one sequential read anyway. */
    private const val VIDEO_MOOV_SLIM_MIN = 4L * 1024 * 1024

    /** While slimming, non-trak moov children up to this size are kept verbatim (mvhd, iods, mvex … are all tiny); anything larger — in practice udta with an embedded cover — is stubbed out like the audio traks. */
    private const val VIDEO_MOOV_KEEP_BOX = 256L * 1024

    /** Bytes read from the front of a trak to decide whether it is the video one: hdlr sits behind tkhd (+ optional edts) + mdia/mdhd, a few hundred bytes in. */
    private const val VIDEO_TRAK_PROBE = 16L * 1024

    /** MediaMetadataRetriever timeout: encountering a truncated / malformed container (network video only fed the first [VIDEO_HEAD_CAP], especially when moov isn't fully read) can probe the format at the native layer for a long time, or even hang — it has no cancel API. Previously genVideo ran directly on [executor]'s 2 twig-thumbs threads, so one stuck video tied up half the pool, dragging image thumbnails to a halt too — symptom: "wait forever, not sure if it's still generating". Now it's moved to its own [videoExecutor]; the main pool only waits [VIDEO_TIMEOUT_MS] before giving up — timeouts are treated as failures (go through [FAIL_COOLDOWN_MS] retry), and twig-thumbs is no longer dragged down; the stuck call is left on the dedicated twig-video thread to its fate (daemon thread, doesn't block process exit).
     *
     * **Empirical pitfall**: after adding the black-frame retry with on-demand data windows for candidate time points, 6 seconds often wasn't enough — each candidate requires a fresh download of a [VIDEO_MID_WINDOW] window, and the network time for several candidates easily exceeds 6s, causing many videos to time out (worse than "fall back to a black opening", now there's no thumbnail at all). The cap is now relaxed to 15 seconds — this wait only occupies [videoExecutor]'s own thread, not a slot in the twig-thumbs shared pool, so lengthening doesn't affect other files' thumbnail concurrency. */
    private const val VIDEO_TIMEOUT_MS = 15_000L
    private val videoExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "twig-video").apply { isDaemon = true }
    }

    private fun genVideo(ctx: Context, file: XFile): Bitmap? = try {
        videoExecutor.submit(Callable { genVideoBlocking(ctx, file) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: video timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: video failed ${file.name}: $e")
        null
    }

    /** Target time point: take the frame at 1/[VIDEO_FRAME_DIVISOR] of the duration rather than the start — the start is usually a black fade-in / title logo. Used to have a "if black frame, retry at another time" layer (isMostlyBlack), but once [findKeyframeOffset] precisely parsed the keyframe's true byte offset, the hit rate is already very high, so that retry layer only adds the cost of "always downloading several extra windows" with no real gain, and was removed (confirmed by user testing). */
    private const val VIDEO_FRAME_DIVISOR = 10L

    /** Safety margin before/after the keyframe byte offset precisely computed by [findKeyframeOffset] — just enough for the decoder to grab SPS/PPS or probe slightly further back, no need for a big margin like the ratio-estimate fallback. */
    private const val VIDEO_KEYFRAME_MARGIN = 128L * 1024
    private const val VIDEO_KEYFRAME_WINDOW = 8L * 1024 * 1024 // 4K/60fps I-frames can reach several MB; give a bigger window to avoid the fallback read.

    /** [findKeyframeOffset] parse-failure fallback: estimate the offset by mdat size ratio, leave a half-window margin on each side to absorb uneven bitrate (empirically this error can reach 9 MB+, the fallback itself
     * is unreliable — just a last resort when there's nothing better). */
    private const val VIDEO_MID_WINDOW = 8L * 1024 * 1024

    private fun genVideoBlocking(ctx: Context, file: XFile): Bitmap? {
        val frame = try {
            if (file.extension == "avi") {
                // The system MediaMetadataRetriever does not support AVI demuxing
                // (local or network, both), so we have to bypass it and use
                // GlFrameGrabber (see its comments).
                genVideoAviFrame(ctx, file)
            } else if (file.extension == "m2ts") {
                // Real BDAV M2TS uses 192-byte packets, MMR support is
                // device-dependent and not guaranteed — use GlFrameGrabber +
                // M2tsStrippingDataSource uniformly, same path as the player.
                genVideoM2tsFrame(ctx, file)
            } else if (file.scheme == "file") {
                withRetriever { r ->
                    r.setDataSource(file.path)
                    pickRepresentativeFrame(r, file, durationMsOf(r))
                }
            } else {
                genVideoNetworkFrame(file)
            }
        } catch (e: Exception) {
            Log.w("twig", "thumbs: video decode failed ${file.name}: $e")
            null
        }
        frame?.setHasAlpha(false)
        return frame
    }

    private fun genVideoAviFrame(ctx: Context, file: XFile): Bitmap? {
        // ★ The same [MediaSources.extractors] the player uses, not the default factory: AVI needs
        // its MPEG-4 video repaired before the decoder sees it (packed bitstream split apart,
        // stuffing chunks dropped), and a file whose first frames are 1-byte `7f` stuffing kills
        // the codec outright — which is what "this one never produced a thumbnail" was.
        if (file.scheme == "file") {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(file.path)))
            return GlFrameGrabber.grab(
                ctx, mediaItem, null, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS, MediaSources.extractors(),
            )
        }
        val mediaItem = MediaItem.fromUri("twig:///media.avi")
        return FsRegistry.of(file).openRandom(file).use { raw ->
            // idx1 is usually at the file's tail, and seeking to the target time
            // requires reading it first — a bare RandomSource means many small
            // reads back and forth, easily burning the budget on the network; wrap
            // with a read-ahead cache (same one the player uses).
            val src = BufferedRandomSource(raw)
            val factory = DataSource.Factory { RandomSourceDataSource(src) }
            GlFrameGrabber.grab(
                ctx, mediaItem, factory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS, MediaSources.extractors(),
            )
        }
    }

    private fun genVideoM2tsFrame(ctx: Context, file: XFile): Bitmap? {
        val local = file.scheme == "file"
        val shared = if (local) null else BufferedRandomSource(FsRegistry.of(file).openRandom(file))
        try {
            val rawPacketSize = detectM2tsPacketSize(file, shared)
            val baseFactory: DataSource.Factory = if (shared != null) {
                DataSource.Factory { RandomSourceDataSource(shared) }
            } else {
                FileDataSource.Factory()
            }
            val mediaItem = if (local) {
                MediaItem.fromUri(Uri.fromFile(File(file.path)))
            } else {
                MediaItem.fromUri("twig:///media.ts")
            }
            return if (rawPacketSize != RAW_M2TS_PACKET_SIZE) {
                // Some tools save ordinary 188-byte TS streams with a .m2ts
                // extension — no stripping needed, default sniff.
                GlFrameGrabber.grab(ctx, mediaItem, baseFactory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS)
            } else {
                val totalRawLength = if (local) File(file.path).length() else file.size
                val strippingFactory = DataSource.Factory {
                    M2tsStrippingDataSource(baseFactory.createDataSource(), totalRawLength)
                }
                GlFrameGrabber.grab(
                    ctx, mediaItem, strippingFactory, VIDEO_FRAME_DIVISOR, VIDEO_TIMEOUT_MS, newM2tsExtractorsFactory(),
                )
            }
        } finally {
            shared?.close()
        }
    }

    /** Use a fresh MediaMetadataRetriever instance per frame grab (setDataSource can only be called once; precise MKV fallback to random-access requires a new instance); release after use. */
    private inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try {
            return block(r)
        } finally {
            runCatching { r.release() }
        }
    }

    /** Try at 1/[VIDEO_FRAME_DIVISOR] of the duration, fall back to time 0 only on failure; [prepareFor] is called before each attempt (network sources use it to download the data window near the target time on demand; local files don't need it, pass an empty impl — the retriever can seek directly to any time).
     *
     * No-arg `frameAtTime` (i.e. `getFrameAtTime(-1, OPTION_CLOSEST_SYNC)`) with a custom `MediaDataSource` empirically often cleanly returns null (no exception, no timeout, data confirmed complete) — so this path uses explicit time points + two OPTION attempts only, never the no-arg version. */
    private fun pickRepresentativeFrame(
        r: MediaMetadataRetriever,
        file: XFile,
        durationMs: Long?,
        prepareFor: (targetUs: Long) -> Unit = {},
    ): Bitmap? {
        val targetUs = if (durationMs != null && durationMs > 0) durationMs * 1000 / VIDEO_FRAME_DIVISOR else 0L
        prepareFor(targetUs)
        var frame = sequenceOf(
            targetUs to MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            targetUs to MediaMetadataRetriever.OPTION_CLOSEST,
        ).firstNotNullOfOrNull { (t, opt) -> runCatching { r.getFrameAtTime(t, opt) }.getOrNull() }
        if (frame == null && targetUs != 0L) {
            frame = runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
        }
        if (frame == null) Log.w("twig", "thumbs: video frameAtTime null (all attempts) ${file.name}")
        return frame
    }

    private fun genVideoNetworkFrame(file: XFile): Bitmap? {
        val fs = FsRegistry.of(file)
        val size = file.size
        return fs.openRandom(file).use { src ->
            // First sniff a small segment to detect the container: ISO BMFF (mp4/mov)
            // has ftyp as its first box (bytes 4..8); Matroska/WebM (MKV) starts with
            // EBML magic 1A45DFA3.
            val sniff = readAtCapped(src, 0, 64)
            if (sniff.isEmpty()) {
                Log.w("twig", "thumbs: video head empty ${file.name}")
                return@use null
            }
            val isoBmff = sniff.size >= 12 && String(sniff, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val isMatroska = sniff.size >= 4 && sniff[0] == 0x1A.toByte() && sniff[1] == 0x45.toByte() &&
                sniff[2] == 0xDF.toByte() && sniff[3] == 0xA3.toByte()
            when {
                isoBmff -> withRetriever { r -> genFrameMp4(r, file, src, size) }
                isMatroska && fs.randomAccessEfficient() ->
                    // MKV first tries precise EBML parsing (SeekHead → Cues → target
                    // Cluster), downloading only init + Cues + target cluster (usually
                    // ~2 MB, fixed number of seeks); falls back to letting MMR demux +
                    // seek itself only when parsing fails (no Cues index, etc.).
                    withRetriever { r -> genFrameMkv(r, file, src, size) }
                        ?: withRetriever { r -> genFrameRandomAccess(r, file, src, size) }
                fs.randomAccessEfficient() ->
                    // Other containers (AVI, etc.) don't have offline parsing written:
                    // hand a block-cached true random-access data source to MMR to let
                    // it demux + seek itself (FTP/SFTP without efficient random access
                    // don't take this path — avoid crushing their bandwidth).
                    withRetriever { r -> genFrameRandomAccess(r, file, src, size) }
                else ->
                    // Non-MP4 sources without efficient random access: only feed the
                    // head, basically only get time 0 (may be black).
                    withRetriever { r ->
                        r.setDataSource(headOnlySource(readAtCapped(src, 0, minOf(VIDEO_HEAD_CAP, size)), size))
                        pickRepresentativeFrame(r, file, durationMsOf(r))
                    }
            }
        }
    }

    /** MKV (Matroska/WebM, EBML container) network frame grab: precisely parses the target keyframe's Cluster, downloading only init (EBML header + SeekHead + Info + Tracks) + the Cues index + the target Cluster, three segments, fed to MMR. Same rationale as the MP4 sample-table path: deterministic, minimum bytes, fixed number of seeks, never let MMR scatter-seek over a slow network (that's why "MKV is slow / large files can't generate"). Parse failure (no Cues index, malformed structure) returns null, caller falls back to random access. */
    private fun genFrameMkv(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        val plan = runCatching { planMkv(src, size) }.getOrNull()
        if (plan == null) {
            Log.w("twig", "thumbs: mkv plan failed ${file.name}")
            return null
        }
        val cluster = readAtCapped(src, plan.clusterStart, plan.clusterLen)
        if (cluster.size < 16) return null
        // Take only "that one video keyframe block" from the target cluster, zero out
        // all timestamps, synthesize as: cluster header + Timestamp(0) + keyframe block.
        // The synthesized file fed to MMR has only one frame and it's at t=0 — there's
        // no other frame to decode, no seek/forward-decode — totally avoids "decoding
        // a non-keyframe/following frame produces a green screen".
        // The keyframe block is precisely located by scanning cluster-internal block
        // headers (check track is a video track + keyframe flag), not relying on
        // CueRelativePosition (some files don't write that field; defaulting to 0
        // would hit Timestamp and produce a green screen).
        val kfBlock = extractVideoKeyframeBlock(cluster, plan.videoTrack) ?: return null

        // Synthesize a self-contained mini MKV: EBML header + Segment (unknown size) +
        // Info + Tracks + a small Cluster containing only the keyframe block. SeekHead
        // is NOT included (its offsets point into the original file and would be wrong
        // in the synthesized version).
        val bos = ByteArrayOutputStream(kfBlock.size + plan.tracksEnd + 64)
        bos.write(plan.head, 0, plan.ebmlEnd)
        bos.write(SEGMENT_UNKNOWN_HEADER)
        bos.write(plan.head, plan.infoStart, plan.infoEnd - plan.infoStart)
        bos.write(plan.head, plan.tracksStart, plan.tracksEnd - plan.tracksStart)
        bos.write(CLUSTER_UNKNOWN_HEADER)
        bos.write(TIMESTAMP_ZERO) // Timestamp = 0
        bos.write(kfBlock)
        val synth = bos.toByteArray()

        r.setDataSource(inMemorySource(synth))
        val t0 = SystemClock.elapsedRealtime()
        val frame = runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            ?: runCatching { r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST) }.getOrNull()
        Log.d(
            "twig",
            "thumbs: mkv ${file.name} size=$size synth=${synth.size} kf=${kfBlock.size} " +
                "frame=${frame != null} ms=${SystemClock.elapsedRealtime() - t0}",
        )
        return frame
    }

    /** Scan the Cluster for the first "video track keyframe" block (SimpleBlock with the keyframe flag / BlockGroup without ReferenceBlock), zero out its in-block relative timestamp (the 2 bytes after the track number) and return the block **verbatim**. Taking only this one block: the synthesized file has just one frame at t=0, MMR has no other frame to decode (cure for green screen). Returns null if none found. */
    private fun extractVideoKeyframeBlock(cluster: ByteArray, videoTrack: Long): ByteArray? {
        val ch = ebmlHeader(cluster, 0) ?: return null
        if (ch.id != CLUSTER_ID) return null
        val hdrLen = ch.bodyStart.toInt()
        val bodyEnd = if (ch.unknownSize) cluster.size else minOf((ch.bodyStart + ch.contentSize).toInt(), cluster.size)
        var p = hdrLen
        while (p < bodyEnd) {
            val e = ebmlHeader(cluster, p) ?: break
            val end = (e.bodyStart + e.contentSize).toInt()
            if (end > bodyEnd || end <= p) break
            when (e.id) {
                SIMPLEBLOCK_ID -> {
                    // [track vint][timecode 2B][flags 1B]...;keyframe = flags & 0x80
                    val tv = ebmlVint(cluster, e.bodyStart.toInt(), keepMarker = false)
                    if (tv != null) {
                        val tcPos = e.bodyStart.toInt() + tv.second
                        if (tcPos + 3 <= cluster.size) {
                            val track = tv.first
                            val flags = cluster[tcPos + 2].toInt() and 0xFF
                            if (track == videoTrack && (flags and 0x80) != 0) {
                                val block = cluster.copyOfRange(p, end)
                                zeroBlockTimecode(block, (e.bodyStart.toInt() - p) + tv.second)
                                return block
                            }
                        }
                    }
                }
                BLOCKGROUP_ID -> {
                    // Contains Block (0xA1) and no ReferenceBlock (0xFB) = keyframe.
                    var q = e.bodyStart.toInt()
                    var blockPos = -1
                    var hasRef = false
                    while (q < end) {
                        val x = ebmlHeader(cluster, q) ?: break
                        if (x.id == BLOCK_ID) blockPos = q
                        if (x.id == REFERENCEBLOCK_ID) hasRef = true
                        q = (x.bodyStart + x.contentSize).toInt()
                        if (q <= x.start.toInt()) break
                    }
                    if (blockPos >= 0 && !hasRef) {
                        val bx = ebmlHeader(cluster, blockPos)!!
                        val tv = ebmlVint(cluster, bx.bodyStart.toInt(), keepMarker = false)
                        if (tv != null && tv.first == videoTrack) {
                            val block = cluster.copyOfRange(p, end)
                            zeroBlockTimecode(block, (bx.bodyStart.toInt() - p) + tv.second)
                            return block
                        }
                    }
                }
            }
            p = end
        }
        return null
    }

    /** Zero out the 2-byte relative timestamp in a block (SimpleBlock, or Block inside a BlockGroup) that follows the track number. */
    private fun zeroBlockTimecode(block: ByteArray, tcOffset: Int) {
        if (tcOffset + 1 < block.size) {
            block[tcOffset] = 0
            block[tcOffset + 1] = 0
        }
    }

    private val CLUSTER_UNKNOWN_HEADER = byteArrayOf(
        0x1F, 0x43, 0xB6.toByte(), 0x75, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )
    private val TIMESTAMP_ZERO = byteArrayOf(0xE7.toByte(), 0x81.toByte(), 0x00) // Timestamp element, value = 0.

    /** Segment element header + "unknown size" (0x01 followed by seven 0xFF): the synthesized stream ends at the data source's EOF. */
    private val SEGMENT_UNKNOWN_HEADER = byteArrayOf(
        0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )

    /** Pure in-memory data source (for synthesized MKV); readAt always hits memory. */
    private fun inMemorySource(data: ByteArray) = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(len, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() = Unit
    }

    // ---- MKV (EBML) precise location: SeekHead → Cues → target Cluster ----

    /** Pre-staged material for synthesizing a "self-contained mini MKV": EBML header + Info + Tracks (all in [head], with their respective start/end offsets) + the absolute range of the target Cluster. After synthesis only this small segment is fed to MMR, so it can't scan the original file's massive number of Clusters (that's the root cause of REMUX being slow). */
    private data class MkvPlan(
        val head: ByteArray,
        val ebmlEnd: Int,
        val infoStart: Int,
        val infoEnd: Int,
        val tracksStart: Int,
        val tracksEnd: Int,
        val clusterStart: Long,
        val clusterLen: Long,
        val videoTrack: Long,
        val targetUs: Long,
    )

    private const val EBML_ID = 0x1A45DFA3L
    private const val SEGMENT_ID = 0x18538067L
    private const val SEEKHEAD_ID = 0x114D9B74L
    private const val SEEK_ID = 0x4DBBL
    private const val SEEKID_ID = 0x53ABL
    private const val SEEKPOS_ID = 0x53ACL
    private const val INFO_ID = 0x1549A966L
    private const val TIMESTAMPSCALE_ID = 0x2AD7B1L
    private const val DURATION_ID = 0x4489L
    private const val TRACKS_ID = 0x1654AE6BL
    private const val CUES_ID = 0x1C53BB6BL
    private const val CUEPOINT_ID = 0xBBL
    private const val CUETIME_ID = 0xB3L
    private const val CUETRACKPOS_ID = 0xB7L
    private const val CUECLUSTERPOS_ID = 0xF1L
    private const val CUERELPOS_ID = 0xF0L
    private const val CUETRACK_ID = 0xF7L
    private const val CLUSTER_ID = 0x1F43B675L
    private const val TRACKENTRY_ID = 0xAEL
    private const val TRACKNUMBER_ID = 0xD7L
    private const val TRACKTYPE_ID = 0x83L
    private const val TIMESTAMP_ID = 0xE7L
    private const val SIMPLEBLOCK_ID = 0xA3L
    private const val BLOCKGROUP_ID = 0xA0L
    private const val BLOCK_ID = 0xA1L
    private const val REFERENCEBLOCK_ID = 0xFBL

    private const val MKV_HEAD_CAP = 64L * 1024 // EBML header + SeekHead + Info + Tracks usually all fit within.
    private const val MKV_CUES_CAP = 16L * 1024 * 1024
    private const val MKV_CLUSTER_CAP = 24L * 1024 * 1024

    /** Parse the absolute byte ranges of the init / Cues / target Cluster three segments and the target time (µs);
     * returns null when there's no SeekHead/Cues or the structure is malformed (fallback to random access). */
    private fun planMkv(src: RandomSource, size: Long): MkvPlan? {
        val head = readAtCapped(src, 0, minOf(MKV_HEAD_CAP, size))
        if (head.size < 8) return null

        // EBML header + Segment header.
        var p = 0
        var h = ebmlHeader(head, p) ?: return null
        if (h.id != EBML_ID) return null
        p = (h.bodyStart + h.contentSize).toInt() // Skip past EBML header body.
        h = ebmlHeader(head, p) ?: return null
        if (h.id != SEGMENT_ID) return null
        val segBase = h.bodyStart // All Segment-internal offsets are relative to here.

        // Scan Segment top-level children (within the head buffer), collecting SeekHead/Info/Tracks and the first Cluster.
        var seekHead: EbmlBox? = null
        var info: EbmlBox? = null
        var tracks: EbmlBox? = null
        var q = segBase.toInt()
        while (q < head.size) {
            val e = ebmlHeader(head, q) ?: break
            if (e.id == CLUSTER_ID) break // Stop at the first Cluster; the metadata in front is already collected.
            when (e.id) {
                SEEKHEAD_ID -> seekHead = e
                INFO_ID -> info = e
                TRACKS_ID -> tracks = e
            }
            if (e.unknownSize) break
            q = (e.bodyStart + e.contentSize).toInt()
            if (q <= e.start.toInt()) break
        }
        val tk = tracks ?: return null
        val sh = seekHead ?: return null
        val inf = info ?: return null
        // Info/Tracks must lie entirely within the head buffer (so they can be carved out for synthesis); otherwise abandon the precise path.
        val infoEnd = (inf.bodyStart + inf.contentSize).toInt()
        val tracksEnd = (tk.bodyStart + tk.contentSize).toInt()
        if (infoEnd > head.size || tracksEnd > head.size) return null

        // SeekHead → Segment-relative offset of Cues.
        val cuesRel = parseSeekHead(head, sh, CUES_ID) ?: return null
        val cuesAbs = segBase + cuesRel
        if (cuesAbs < 0 || cuesAbs >= size) return null

        // Info → timestampScale (ns/tick, default 1e6) + duration (tick).
        var scale = 1_000_000L
        var durationTicks = 0.0
        if (info != null) {
            val parsed = parseInfo(head, info)
            scale = parsed.first
            durationTicks = parsed.second
        }

        // Read Cues element (random-access read).
        val cuesHdrBuf = readAtCapped(src, cuesAbs, 16)
        val ch = ebmlHeader(cuesHdrBuf, 0) ?: return null
        if (ch.id != CUES_ID || ch.unknownSize) return null
        val cuesBodyStart = cuesAbs + ch.bodyStart
        val cuesLen = ch.contentSize
        if (cuesLen <= 0 || cuesLen > MKV_CUES_CAP) return null
        // Video track number: Cues indexes each track separately (including sparse subtitle
        // indexing); pick only the video track's CuePoint, otherwise we might end up at a
        // subtitle track's CueRelativePosition pointing at a subtitle block rather than a
        // keyframe (empirically the cause of "green screen / can't decode").
        val videoTrack = parseVideoTrackNumber(head, tk)
        val cuesData = readAtCapped(src, cuesBodyStart, cuesLen)
        val cuePoints = parseCues(cuesData, videoTrack) // (timeTicks, clusterSegRelPos, relPos), video track only.
        if (cuePoints.isEmpty()) return null

        val lastTime = cuePoints.last().time
        val targetTicks = (if (durationTicks > 0) durationTicks / VIDEO_FRAME_DIVISOR else lastTime.toDouble() / VIDEO_FRAME_DIVISOR).toLong()
        var chosen = cuePoints.first()
        for (cp in cuePoints) {
            if (cp.time <= targetTicks) chosen = cp else break
        }
        val clusterAbs = segBase + chosen.clusterPos
        if (clusterAbs < 0 || clusterAbs >= size) return null

        // Target Cluster header → real size.
        val clHdrBuf = readAtCapped(src, clusterAbs, 16)
        val cl = ebmlHeader(clHdrBuf, 0) ?: return null
        if (cl.id != CLUSTER_ID) return null
        val clusterLen = if (cl.unknownSize) MKV_CLUSTER_CAP else minOf(cl.bodyStart + cl.contentSize, MKV_CLUSTER_CAP)

        val ebmlEnd = (ebmlHeader(head, 0)!!.let { it.bodyStart + it.contentSize }).toInt()
        val targetUs = targetTicks * scale / 1000L
        return MkvPlan(
            head, ebmlEnd, inf.start.toInt(), infoEnd, tk.start.toInt(), tracksEnd,
            clusterAbs, clusterLen, videoTrack, targetUs,
        )
    }

    private data class MkvCue(val time: Long, val clusterPos: Long, val relPos: Long)

    /** The TrackNumber of the Track with TrackType==1 (video) inside Tracks; defaults to 1 if not found. */
    private fun parseVideoTrackNumber(buf: ByteArray, tracks: EbmlBox): Long {
        var p = tracks.bodyStart.toInt()
        val end = (tracks.bodyStart + tracks.contentSize).toInt().coerceAtMost(buf.size)
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == TRACKENTRY_ID) {
                var r = e.bodyStart.toInt()
                val re = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var num = -1L
                var type = -1L
                while (r < re) {
                    val x = ebmlHeader(buf, r) ?: break
                    when (x.id) {
                        TRACKNUMBER_ID -> num = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        TRACKTYPE_ID -> type = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                    }
                    r = (x.bodyStart + x.contentSize).toInt()
                }
                if (type == 1L && num >= 0) return num
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return 1L
    }

    /** EBML element header: id (with the length-marker bit preserved) + content size + header length; [start] is the element's start, [bodyStart] is the content's start. */
    private data class EbmlBox(val id: Long, val start: Long, val bodyStart: Long, val contentSize: Long, val unknownSize: Boolean)

    private fun ebmlHeader(buf: ByteArray, p: Int): EbmlBox? {
        val idv = ebmlVint(buf, p, keepMarker = true) ?: return null
        val szPos = p + idv.second
        val szv = ebmlVint(buf, szPos, keepMarker = false) ?: return null
        val unknown = szv.third // all-ones = unknown size.
        return EbmlBox(idv.first, p.toLong(), (szPos + szv.second).toLong(), szv.first, unknown)
    }

    /** Read one EBML vint. keepMarker=true for element IDs (keep the length-marker bit), false for sizes.
     * Returns (value, byte count, is-all-ones = unknown-size). */
    private fun ebmlVint(buf: ByteArray, p: Int, keepMarker: Boolean): Triple<Long, Int, Boolean>? {
        if (p < 0 || p >= buf.size) return null
        val b0 = buf[p].toInt() and 0xFF
        var mask = 0x80
        var length = 1
        while (length <= 8 && (b0 and mask) == 0) { mask = mask shr 1; length++ }
        if (length > 8 || p + length > buf.size) return null
        var v = (if (keepMarker) b0 else (b0 and (mask - 1))).toLong()
        for (i in 1 until length) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
        val allOnes = if (keepMarker) false else v == (1L shl (7 * length)) - 1
        return Triple(v, length, allOnes)
    }

    private fun ebmlUint(buf: ByteArray, p: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (buf[p + i].toLong() and 0xFF)
        return v
    }

    /** Look up the Segment-relative offset of [wantId] inside the SeekHead. */
    private fun parseSeekHead(buf: ByteArray, sh: EbmlBox, wantId: Long): Long? {
        var p = sh.bodyStart.toInt()
        val end = (sh.bodyStart + sh.contentSize).toInt().coerceAtMost(buf.size)
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == SEEK_ID) {
                var r = e.bodyStart.toInt()
                val re = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var sid = -1L
                var spos = -1L
                while (r < re) {
                    val x = ebmlHeader(buf, r) ?: break
                    when (x.id) {
                        SEEKID_ID -> sid = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        SEEKPOS_ID -> spos = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                    }
                    r = (x.bodyStart + x.contentSize).toInt()
                }
                if (sid == wantId && spos >= 0) return spos
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return null
    }

    private fun parseInfo(buf: ByteArray, info: EbmlBox): Pair<Long, Double> {
        var p = info.bodyStart.toInt()
        val end = (info.bodyStart + info.contentSize).toInt().coerceAtMost(buf.size)
        var scale = 1_000_000L
        var dur = 0.0
        while (p < end) {
            val e = ebmlHeader(buf, p) ?: break
            when (e.id) {
                TIMESTAMPSCALE_ID -> scale = ebmlUint(buf, e.bodyStart.toInt(), e.contentSize.toInt())
                DURATION_ID -> {
                    val n = e.contentSize.toInt()
                    val bits = ebmlUint(buf, e.bodyStart.toInt(), n)
                    dur = if (n == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return scale to dur
    }

    /** Parse Cues → CuePoints **only for the [videoTrack] track** (time tick, Cluster's Segment-relative offset, keyframe block's offset within Cluster), in ascending time order. One CuePoint can contain multiple CueTrackPositions (video / subtitle each), the video-track one must be picked. */
    private fun parseCues(buf: ByteArray, videoTrack: Long): List<MkvCue> {
        val out = ArrayList<MkvCue>()
        var p = 0
        while (p < buf.size) {
            val e = ebmlHeader(buf, p) ?: break
            if (e.id == CUEPOINT_ID) {
                var q = e.bodyStart.toInt()
                val qe = (e.bodyStart + e.contentSize).toInt().coerceAtMost(buf.size)
                var time = -1L
                var pos = -1L
                var rel = 0L
                while (q < qe) {
                    val x = ebmlHeader(buf, q) ?: break
                    when (x.id) {
                        CUETIME_ID -> time = ebmlUint(buf, x.bodyStart.toInt(), x.contentSize.toInt())
                        CUETRACKPOS_ID -> {
                            var s = x.bodyStart.toInt()
                            val se = (x.bodyStart + x.contentSize).toInt().coerceAtMost(buf.size)
                            var track = -1L
                            var cpos = -1L
                            var crel = 0L
                            while (s < se) {
                                val y = ebmlHeader(buf, s) ?: break
                                when (y.id) {
                                    CUETRACK_ID -> track = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                    CUECLUSTERPOS_ID -> cpos = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                    CUERELPOS_ID -> crel = ebmlUint(buf, y.bodyStart.toInt(), y.contentSize.toInt())
                                }
                                s = (y.bodyStart + y.contentSize).toInt()
                            }
                            if (track == videoTrack && cpos >= 0 && pos < 0) { pos = cpos; rel = crel }
                        }
                    }
                    q = (x.bodyStart + x.contentSize).toInt()
                }
                if (time >= 0 && pos >= 0) out.add(MkvCue(time, pos, rel))
            }
            p = (e.bodyStart + e.contentSize).toInt()
        }
        return out
    }

    /** MP4/MOV (ISO BMFF) network frame grab: parse the moov sample table to precisely locate the keyframe, only download the required segments. */
    private fun genFrameMp4(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        // moov (sample-table metadata, etc.) is commonly at the file head (faststart)
        // or at the file tail (live-recording, moov patched in at the end). The head
        // plays a second role: regardless of where moov is, the keyframe data near
        // time 0 always follows the leading ftyp, so the head is essential (final
        // fallback when 1/10 isn't available). moov/mdat's precise offset/size come
        // from scanning box headers with [scanTopBoxes]; then the sample table
        // ([findKeyframeOffset]) precisely locates the target time's keyframe byte
        // offset, and we only download that small slice.
        var frame: Bitmap? = null
        run {
            val boxes = scanTopBoxes(src, size)
            val moov = boxes["moov"]
            // The head's second role only exists when moov is at the tail. With moov at the
            // head (faststart) the head read is a byte-for-byte duplicate of moov's first
            // VIDEO_HEAD_CAP bytes, which the moov read fetches again anyway — 8 MB of pure
            // duplicate traffic on every large file. Read only up to moov's start there
            // (ftyp, a few dozen bytes).
            val headCap = if (moov != null && moov.first in 1 until VIDEO_HEAD_CAP) moov.first else VIDEO_HEAD_CAP
            val head = readAtCapped(src, 0, minOf(headCap, size))
            if (head.isEmpty()) {
                Log.w("twig", "thumbs: video head empty ${file.name}")
                return null
            }
            // Segments pinned into the data source, in the file's real layout (a slimmed
            // moov contributes several, an ordinary one contributes exactly itself).
            val metaRegions: List<Pair<Long, ByteArray>>
            val meta: ByteArray
            if (moov != null && moov.second in 1..VIDEO_MOOV_CAP) {
                val slim = if (moov.second >= VIDEO_MOOV_SLIM_MIN) planSlimMoov(src, moov.first, moov.second) else null
                if (slim != null) {
                    meta = slim.compact
                    metaRegions = slim.regions
                    Log.d(
                        "twig",
                        "thumbs: mp4 moov slimmed ${moov.second} -> ${slim.bytes} bytes ${file.name}",
                    )
                } else {
                    meta = readAtCapped(src, moov.first, moov.second)
                    metaRegions = listOf(moov.first to meta)
                }
            } else {
                val metaStart = maxOf(0L, size - minOf(VIDEO_TAIL_FALLBACK_CAP, size))
                meta = readAtCapped(src, metaStart, size - metaStart)
                metaRegions = listOf(metaStart to meta)
                Log.w(
                    "twig",
                    "thumbs: video ${file.name} moov scan failed(size=${moov?.second}), " +
                        "fallback tail metaStart=$metaStart got=${meta.size}",
                )
            }

            // Pre-fetch head + moov into NetVideoDataSource; the target keyframe window
            // is pinned after we have the duration. Key: missing positions fall back
            // to random-access reads (rather than the old `return -1` hard-EOF) — a
            // 4K/60fps I-frame can be larger than the prefetch window (empirically, a
            // 34 GB DoVi mp4's keyframe >2 MB gets truncated and undecodable); the
            // fallback reads supply those extra keyframe bytes; MP4 uses moov for
            // direct seek (not sequential scan like MKV), so fallback reads are rare
            // and still fast.
            val ds = NetVideoDataSource(src, size, listOf(0L to head) + metaRegions)
            r.setDataSource(ds)

            val durationMs = runCatching {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }.getOrNull()
            val mdat = boxes["mdat"]

            frame = pickRepresentativeFrame(r, file, durationMs) { targetUs ->
                val exact = findKeyframeOffset(meta, targetUs)
                val winStart: Long
                val winLen: Long
                if (exact != null) {
                    winStart = maxOf(0L, exact - VIDEO_KEYFRAME_MARGIN)
                    winLen = minOf(VIDEO_KEYFRAME_WINDOW, size - winStart)
                } else if (mdat != null) {
                    // Sample-table parse failed: estimate by mdat size ratio (fallback, imprecise).
                    val targetByte = (mdat.first + mdat.second / VIDEO_FRAME_DIVISOR)
                        .coerceIn(mdat.first, mdat.first + mdat.second)
                    winStart = maxOf(mdat.first, targetByte - VIDEO_MID_WINDOW / 2)
                    winLen = minOf(VIDEO_MID_WINDOW, mdat.first + mdat.second - winStart)
                } else return@pickRepresentativeFrame
                ds.pin(winStart, readAtCapped(src, winStart, winLen))
            }
            Log.d(
                "twig",
                "thumbs: mp4 ${file.name} size=$size head=${head.size} moov=$moov " +
                    "durationMs=$durationMs frame=${frame != null} ${ds.stats()}",
            )
        }
        return frame
    }

    /** Non-MP4 containers (AVI, etc., or MKV precise-parse failure) network frame grab: pure random access, let MMR demux + seek itself (the internal MediaExtractor natively supports MKV). */
    private fun genFrameRandomAccess(r: MediaMetadataRetriever, file: XFile, src: RandomSource, size: Long): Bitmap? {
        val ds = NetVideoDataSource(src, size, emptyList())
        r.setDataSource(ds)
        val durationMs = durationMsOf(r)
        val t0 = SystemClock.elapsedRealtime()
        val frame = pickRepresentativeFrame(r, file, durationMs)
        Log.d(
            "twig",
            "thumbs: video ${file.name} random-access size=$size durationMs=$durationMs " +
                "frame=${frame != null} ms=${SystemClock.elapsedRealtime() - t0} ${ds.stats()}",
        )
        return frame
    }

    private fun durationMsOf(r: MediaMetadataRetriever): Long? = runCatching {
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull()

    /** Data source serving only the file head [head] (fallback when the source isn't MP4 and lacks efficient random access — basically can only get time 0). */
    private fun headOnlySource(head: ByteArray, size: Long) = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
            if (position >= head.size) return -1
            val n = minOf(len, (head.size - position).toInt())
            System.arraycopy(head, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = size
        override fun close() = Unit
    }

    /** Network video data source: first serves the prefetched [pinned] in-memory segments (MKV precise path's init / Cues / target Cluster); positions that miss fall back to random-access reads on the underlying [RandomSource] (256 KB block LRU cache coalesces adjacent/repeated reads). This way even if MMR reads positions we didn't prefetch, it won't fail to decode by returning -1 (= EOF) — previously the MKV precise path always fell back because MMR would read beyond the three prefetched segments and get hard-EOFed. Aborts only when cumulative fallback reads exceed [LIVE_CAP] (to prevent files without Cues from triggering a full-file scan).
     * [stats] exposes the miss positions / fallback bytes for diagnosing "what regions does MMR actually still need".
     * close() is a no-op — the underlying src is closed by the outer use{}; readAt is locked (MMR callbacks don't guarantee the same thread). */
    private class NetVideoDataSource(
        private val src: RandomSource,
        private val totalSize: Long,
        pinned: List<Pair<Long, ByteArray>>,
    ) : MediaDataSource() {
        private val regions = ArrayList(pinned.filter { it.second.isNotEmpty() })
        /** Append another prefetched segment after construction (the MP4 target keyframe window is downloaded only after we have the duration/offset). */
        fun pin(start: Long, data: ByteArray) = synchronized(lock) { if (data.isNotEmpty()) regions.add(start to data) }
        private val blockBits = 18
        private val blockSize = 1 shl blockBits
        private val cache = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
        }
        private var liveBytes = 0L
        private var missCount = 0
        private val missLog = StringBuilder()
        private val lock = Any()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, len: Int): Int = synchronized(lock) {
            if (position >= totalSize) return -1
            if (len <= 0) return 0
            for ((start, data) in regions) {
                if (position >= start && position < start + data.size) {
                    val off = (position - start).toInt()
                    val n = minOf(len, data.size - off)
                    System.arraycopy(data, off, buffer, offset, n)
                    return n
                }
            }
            // Missed the prefetched region: fall back to random-access reads (block cache).
            if (missCount < 20) missLog.append(position).append('+').append(len).append(' ')
            missCount++
            if (liveBytes > LIVE_CAP) return -1
            val blockIndex = position shr blockBits
            val block = cache[blockIndex] ?: run {
                val start = blockIndex shl blockBits
                val want = minOf(blockSize.toLong(), totalSize - start).toInt()
                val buf = ByteArray(want)
                var got = 0
                while (got < want) {
                    val n = src.readAt(start + got, buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                val b = if (got == want) buf else buf.copyOf(got)
                liveBytes += b.size
                cache[blockIndex] = b
                b
            }
            val within = (position - (blockIndex shl blockBits)).toInt()
            if (within >= block.size) return -1
            val n = minOf(len, block.size - within)
            System.arraycopy(block, within, buffer, offset, n)
            n
        }

        fun stats(): String = "miss=$missCount live=${liveBytes / 1024}KB firstMiss=[${missLog.trim()}]"

        override fun getSize(): Long = totalSize
        override fun close() = Unit

        companion object {
            private const val MAX_BLOCKS = 24 // 24 × 256KB = 6MB sliding cache window.
            private const val LIVE_CAP = 160L * 1024 * 1024 // Fallback-read cap; abort when exceeded.
        }
    }

    // ---- MP4 box-tree parsing (used only for precisely locating the keyframe byte offset inside the downloaded moov) ----

    private data class MBox(val type: String, val start: Int, val end: Int, val bodyStart: Int)

    /** List direct child boxes in [from, to) (headers only, no recursion). */
    private fun mBoxChildren(buf: ByteArray, from: Int, to: Int): List<MBox> {
        val list = ArrayList<MBox>()
        var pos = from
        while (pos + 8 <= to) {
            var size = readU32(buf, pos)
            val type = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var bodyStart = pos + 8
            if (size == 1L) {
                if (pos + 16 > to) break
                size = readU64(buf, pos + 8)
                bodyStart = pos + 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < 8) break
            val end = (pos + size).toInt().coerceAtMost(to)
            list.add(MBox(type, pos, end, bodyStart))
            pos += size.toInt()
        }
        return list
    }

    private fun readU32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xFF) shl 24) or ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or (buf[off + 3].toLong() and 0xFF)

    private fun readU64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    /** [meta] is the complete moov box (including its own 8-byte header). Parses trak/mdia/stbl to find the video track's sample tables (stts: time→sample #; stss: sync sample table; stsc/stco (or co64): sample→chunk→file offset; stsz: sample size) and precisely computes the byte offset of the keyframe closest to [targetTimeUs] in the file — cross-checked against real keyframe positions pulled by ffprobe, matches exactly. Returns null if the structure doesn't support it (missing box, multi-track selection failed, etc.); caller falls back to the older ratio-estimation fallback. */
    internal fun findKeyframeOffset(meta: ByteArray, targetTimeUs: Long): Long? = runCatching {
        val top = mBoxChildren(meta, 8, meta.size)
        val trak = top.filter { it.type == "trak" }.firstOrNull { isVideoTrak(meta, it) } ?: return null
        val mdia = mBoxChildren(meta, trak.bodyStart, trak.end).first { it.type == "mdia" }
        val mdiaChildren = mBoxChildren(meta, mdia.bodyStart, mdia.end)
        val mdhd = mdiaChildren.first { it.type == "mdhd" }
        val timescale = mdhdTimescale(meta, mdhd)
        val minf = mdiaChildren.first { it.type == "minf" }
        val stbl = mBoxChildren(meta, minf.bodyStart, minf.end).first { it.type == "stbl" }
        val stblChildren = mBoxChildren(meta, stbl.bodyStart, stbl.end)
        val stts = stblChildren.first { it.type == "stts" }
        val stsc = stblChildren.first { it.type == "stsc" }
        val stsz = stblChildren.firstOrNull { it.type == "stsz" }
        val stco = stblChildren.firstOrNull { it.type == "stco" }
        val co64 = stblChildren.firstOrNull { it.type == "co64" }
        val stss = stblChildren.firstOrNull { it.type == "stss" }
        if (stco == null && co64 == null) return null

        val targetSampleTime = targetTimeUs * timescale / 1_000_000L
        val sampleIndex1 = sampleIndexAtTime(meta, stts, targetSampleTime)
        val syncIndex1 = if (stss != null) nearestSyncSample(meta, stss, sampleIndex1) else sampleIndex1
        byteOffsetForSample(meta, stsc, stco, co64, stsz, syncIndex1)
    }.getOrNull()

    /** [trakHead] holds bytes from the very start of a trak box and may be truncated ([VIDEO_TRAK_PROBE]) — hdlr sits a few hundred bytes in, and [mBoxChildren] clamps children to what is present. */
    private fun isVideoTrak(trakHead: ByteArray): Boolean =
        trakHead.size > 8 && isVideoTrak(trakHead, MBox("trak", 0, trakHead.size, 8))

    private fun isVideoTrak(meta: ByteArray, trak: MBox): Boolean = runCatching {
        val mdia = mBoxChildren(meta, trak.bodyStart, trak.end).first { it.type == "mdia" }
        val hdlr = mBoxChildren(meta, mdia.bodyStart, mdia.end).first { it.type == "hdlr" }
        String(meta, hdlr.bodyStart + 8, 4, Charsets.ISO_8859_1) == "vide"
    }.getOrDefault(false)

    /** Timescale in mdhd (version 0 or 1). */
    private fun mdhdTimescale(meta: ByteArray, mdhd: MBox): Long {
        val version = meta[mdhd.bodyStart].toInt() and 0xFF
        val off = if (version == 1) mdhd.bodyStart + 20 else mdhd.bodyStart + 12
        return readU32(meta, off)
    }

    /** stts (time-to-sample): accumulate by (sample_count, sample_delta) to find the sample whose time covers the target sample time, returns the 1-based sample number. */
    private fun sampleIndexAtTime(meta: ByteArray, stts: MBox, targetSampleTime: Long): Long {
        var p = stts.bodyStart + 4
        val count = readU32(meta, p).toInt()
        p += 4
        var idx = 1L
        var accum = 0L
        repeat(count) {
            val sampleCount = readU32(meta, p)
            val sampleDelta = readU32(meta, p + 4)
            p += 8
            if (sampleDelta == 0L) return idx
            val span = sampleCount * sampleDelta
            if (accum + span > targetSampleTime) {
                val extra = (targetSampleTime - accum) / sampleDelta
                return idx + minOf(extra, sampleCount - 1)
            }
            accum += span
            idx += sampleCount
        }
        return idx - 1
    }

    /** stss (sync sample table, ascending 1-based sample-number list): find the largest sample number <= target (i.e. the nearest preceding keyframe); if the target is earlier than the first keyframe, use the first. */
    private fun nearestSyncSample(meta: ByteArray, stss: MBox, sampleIndex1: Long): Long {
        var p = stss.bodyStart + 4
        val count = readU32(meta, p).toInt()
        p += 4
        var best = -1L
        repeat(count) {
            val s = readU32(meta, p)
            p += 4
            if (s <= sampleIndex1) best = s else return if (best >= 0) best else s
        }
        return if (best >= 0) best else sampleIndex1
    }

    /** Per stsc (sample→chunk mapping) + stco/co64 (chunk→file offset) + stsz (sample size), compute the absolute file byte offset of the sample at 1-based index [sampleIndex1]. */
    private fun byteOffsetForSample(
        meta: ByteArray,
        stsc: MBox,
        stco: MBox?,
        co64: MBox?,
        stsz: MBox?,
        sampleIndex1: Long,
    ): Long? {
        var p = stsc.bodyStart + 4
        val stscCount = readU32(meta, p).toInt()
        p += 4
        val firstChunks = LongArray(stscCount)
        val samplesPerChunks = LongArray(stscCount)
        for (i in 0 until stscCount) {
            firstChunks[i] = readU32(meta, p)
            samplesPerChunks[i] = readU32(meta, p + 4)
            p += 12
        }
        val totalChunks = if (stco != null) readU32(meta, stco.bodyStart + 4) else readU32(meta, co64!!.bodyStart + 4)

        var sampleCounter = 1L
        var chunkIndex1 = -1L
        var firstSampleInChunk = -1L
        loop@ for (i in 0 until stscCount) {
            val nextFirstChunk = if (i + 1 < stscCount) firstChunks[i + 1] else totalChunks + 1
            var c = firstChunks[i]
            while (c < nextFirstChunk) {
                if (sampleCounter + samplesPerChunks[i] > sampleIndex1) {
                    chunkIndex1 = c
                    firstSampleInChunk = sampleCounter
                    break@loop
                }
                sampleCounter += samplesPerChunks[i]
                c++
            }
        }
        if (chunkIndex1 < 0) return null

        val chunkOffset = if (stco != null) {
            readU32(meta, stco.bodyStart + 8 + ((chunkIndex1 - 1) * 4).toInt())
        } else {
            readU64(meta, co64!!.bodyStart + 8 + ((chunkIndex1 - 1) * 8).toInt())
        }

        var offsetInChunk = 0L
        if (stsz != null) {
            val sampleSize = readU32(meta, stsz.bodyStart + 4)
            offsetInChunk = if (sampleSize != 0L) {
                (sampleIndex1 - firstSampleInChunk) * sampleSize
            } else {
                val q = stsz.bodyStart + 12
                var acc = 0L
                for (s in firstSampleInChunk until sampleIndex1) acc += readU32(meta, q + ((s - 1) * 4).toInt())
                acc
            }
        }
        return chunkOffset + offsetInChunk
    }

    /** Read at most [cap] bytes starting at [start] in [src] (position-based variant of [readCapped]). */
    private fun readAtCapped(src: RandomSource, start: Long, cap: Long): ByteArray {
        if (cap <= 0) return ByteArray(0)
        val bos = ByteArrayOutputStream(minOf(cap, NET_READ_CHUNK.toLong()).toInt())
        val buf = ByteArray(NET_READ_CHUNK)
        var total = 0L
        while (total < cap) {
            val n = src.readAt(start + total, buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
            if (n <= 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    /** Result of [planSlimMoov]: [compact] is a self-contained moov holding only the boxes we kept (for our own sample-table parsing), [regions] are the segments to pin into [NetVideoDataSource] **in the file's real layout**, and [bytes] is what the plan actually downloaded. */
    internal class SlimMoov(val compact: ByteArray, val regions: List<Pair<Long, ByteArray>>, val bytes: Long)

    /** Download only the part of a large moov that a frame grab needs.
     *
     * A moov's sample tables are roughly proportional to sample count **per track**, so a
     * multi-audio release pays for tracks nobody is about to decode: measured on a 28 GB
     * 4K/60fps HEVC release with 8 audio tracks, moov is 42.76 MB of which the video trak is
     * 9.70 MB — the other 33 MB (audio sample tables + a 1.16 MB udta cover) is downloaded,
     * parsed and then ignored. Over SMB that alone blew past [VIDEO_TIMEOUT_MS], which is
     * what "this mp4 never produces a thumbnail" actually was.
     *
     * The trick is that we do **not** have to hand MMR a shorter moov — the file layout must
     * stay byte-identical, since moov's own size is what tells MMR where mdat starts. Instead
     * every child box we don't want is served as an 8-byte `free` box header **of the same
     * size**: a standard ISO BMFF skip box, so MMR jumps straight over it and never reads the
     * body. Only the header is downloaded; the body stays a hole. (A miss inside the hole is
     * harmless anyway — [NetVideoDataSource] falls back to a positioned read.)
     *
     * Returns null when the structure isn't the plain one this assumes (64-bit moov header,
     * children that don't tile the box exactly, no video trak); the caller then downloads the
     * whole moov as before. Verified on the desktop: the frame decoded from the slimmed
     * layout is byte-identical to the one from the full moov.
     */
    internal fun planSlimMoov(src: RandomSource, moovStart: Long, moovSize: Long): SlimMoov? = runCatching {
        val moovEnd = moovStart + moovSize
        val moovHeader = readAtCapped(src, moovStart, 8)
        if (moovHeader.size < 8) return null
        // A 64-bit size extension would put the first child at +16; rare enough for a moov
        // (it would have to exceed 4 GB) that we just decline instead of handling it.
        if (readU32(moovHeader, 0) == 1L) return null

        var pos = moovStart + 8
        var downloaded = moovHeader.size.toLong()
        val children = ArrayList<Triple<String, Long, Long>>()
        var guard = 0
        while (pos + 8 <= moovEnd && guard++ < 64) {
            val hdr = ByteArray(8)
            if (readAtExact(src, pos, hdr, 8) < 8) return null
            downloaded += 8
            var boxSize = readU32(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (boxSize == 1L || boxSize == 0L) return null // 64-bit / to-end child: not worth handling.
            if (boxSize < 8 || pos + boxSize > moovEnd) return null
            children.add(Triple(type, pos, boxSize))
            pos += boxSize
        }
        // The children must tile the moov exactly; anything else means we misread the
        // structure and must not start declaring parts of it `free`.
        if (pos != moovEnd || children.isEmpty()) return null

        val videoTrakStart = children.firstOrNull { (type, start, boxSize) ->
            if (type != "trak") return@firstOrNull false
            val probe = readAtCapped(src, start, minOf(VIDEO_TRAK_PROBE, boxSize))
            downloaded += probe.size
            isVideoTrak(probe)
        }?.second ?: return null

        val regions = ArrayList<Pair<Long, ByteArray>>()
        val body = ByteArrayOutputStream()
        regions.add(moovStart to moovHeader)
        for ((type, start, boxSize) in children) {
            val keep = start == videoTrakStart || (type != "trak" && boxSize <= VIDEO_MOOV_KEEP_BOX)
            if (keep) {
                val bytes = readAtCapped(src, start, boxSize)
                if (bytes.size.toLong() != boxSize) return null
                downloaded += bytes.size
                regions.add(start to bytes)
                body.write(bytes)
            } else {
                // Same size, type `free` — MMR skips the body, we never fetch it.
                val stub = ByteArray(8)
                writeU32(stub, 0, boxSize)
                stub[4] = 'f'.code.toByte(); stub[5] = 'r'.code.toByte()
                stub[6] = 'e'.code.toByte(); stub[7] = 'e'.code.toByte()
                regions.add(start to stub)
            }
        }

        // The compact copy is only ever parsed by us ([findKeyframeOffset] walks its
        // children and reads the video trak's sample tables); chunk offsets inside are
        // absolute file positions, so dropping boxes around them changes nothing.
        val compact = ByteArray(8 + body.size())
        writeU32(compact, 0, compact.size.toLong())
        System.arraycopy(moovHeader, 4, compact, 4, 4)
        System.arraycopy(body.toByteArray(), 0, compact, 8, body.size())
        SlimMoov(compact, regions, downloaded)
    }.getOrNull()

    private fun writeU32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    /** Scan top-level boxes (ftyp/moov/mdat/free/…): read only box headers (8 bytes, or 16 if a 64-bit size extension is present), use the box size to jump straight to the next box header, never read box bodies — the cost is a few dozen-byte random-access reads. Returns a type→(offset, size) map (one scan grabs moov and mdat along the way); on a structurally broken file, returns whatever was scanned so far (which may exclude moov/mdat). */
    private fun scanTopBoxes(src: RandomSource, fileSize: Long): Map<String, Pair<Long, Long>> {
        val result = HashMap<String, Pair<Long, Long>>()
        var pos = 0L
        var guard = 0
        while (pos + 8 <= fileSize && guard++ < 64) {
            val hdr = ByteArray(8)
            if (readAtExact(src, pos, hdr, 8) < 8) break
            var boxSize = ((hdr[0].toLong() and 0xFF) shl 24) or ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or (hdr[3].toLong() and 0xFF)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (boxSize == 1L) {
                val ext = ByteArray(8)
                if (readAtExact(src, pos + 8, ext, 8) < 8) break
                boxSize = ext.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                headerLen = 16L
            } else if (boxSize == 0L) {
                boxSize = fileSize - pos
            }
            if (boxSize < headerLen) break
            result[type] = pos to boxSize
            pos += boxSize
        }
        return result
    }

    private fun readAtExact(src: RandomSource, pos: Long, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = src.readAt(pos + total, buf, total, len - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    // ---- Audio cover ----

    /** Candidate stems (priority order) and extensions for the same-directory cover file. */
    private val COVER_STEMS = listOf("cover", "folder", "front", "albumart")
    private val COVER_EXTS = setOf("jpg", "jpeg", "png", "webp")
    private const val COVER_BYTES_CAP = 32L * 1024 * 1024 // Cover image size cap, to guard against misconfig pointing at a huge file.

    /** "scheme:directory-path" → raw bytes of the same-directory cover file (null = confirmed missing; negative results are cached too).
     * One album directory has dozens of songs / the player, notification, playlist are multiple callers; they all only list the directory and download once. We cache the bytes rather than the decoded result — file-manager thumbnails want 256px, the player/notification/playlist want 1024px ([audioCover]); the different sizes can't share a single bitmap, but they can each decode from the same bytes without re-listing and re-downloading. LRU cap is 32 directories, session-memory only. */
    private val dirCoverBytes = object : LinkedHashMap<String, ByteArray?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray?>?) = size > 32
    }

    private fun resolveDirCoverBytes(file: XFile): ByteArray? {
        val dirKey = "${file.scheme}:${file.parentPath}"
        synchronized(dirCoverBytes) { if (dirCoverBytes.containsKey(dirKey)) return dirCoverBytes[dirKey] }
        val bytes = runCatching {
            val fs = FsRegistry.of(file)
            fs.list(XFile(file.scheme, file.parentPath, isDir = true))
                .filter {
                    !it.isDir && it.extension in COVER_EXTS &&
                        it.name.substringBeforeLast('.').lowercase() in COVER_STEMS
                }
                .sortedBy { COVER_STEMS.indexOf(it.name.substringBeforeLast('.').lowercase()) }
                .firstNotNullOfOrNull { c ->
                    runCatching { fs.openInput(c).use { readCapped(it, COVER_BYTES_CAP, c.size) } }.getOrNull()
                }
        }.getOrNull()
        synchronized(dirCoverBytes) { dirCoverBytes[dirKey] = bytes }
        return bytes
    }

    /** Audio cover: take the embedded cover first, fall back to the same-directory cover image. MMR can hang on truncated/slow data sources (no cancel API), so like the video path it's submitted to [videoExecutor] with a time limit and doesn't occupy the twig-thumbs pool. */
    private fun genAudio(file: XFile): Bitmap? = try {
        videoExecutor.submit(Callable { genAudioBlocking(file) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: audio timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: audio failed ${file.name}: $e")
        null
    }

    private fun genAudioBlocking(file: XFile): Bitmap? {
        embeddedAudioCover(file)?.let { return it }
        return dirCover(file)
    }

    /** Full-size audio cover (embedded APIC/covr/PICTURE preferred, falling back to the same-directory cover file), for the music player main UI / frosted background / notification ([MusicService]) / playlist ([PlaylistActivity]). Downsamples to [maxEdge] rather than the thumbnail's 256. The three callers each have a single-thread queue — if MMR hangs (no cancel API, same risk as [genVideo]/[genAudio], network audio in particular tends to hit it), it permanently blocks that queue and every subsequent track is left waiting forever for its cover (this is the real pitfall behind "loading covers is slow" — it's not just slow, it can actually deadlock); therefore, like [genAudio], it's submitted to [videoExecutor] with a time limit instead of being a naked blocking call. */
    fun audioCover(file: XFile, maxEdge: Int = 1024): Bitmap? = try {
        videoExecutor.submit(Callable { audioCoverBlocking(file, maxEdge) }).get(VIDEO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        Log.w("twig", "thumbs: audioCover timeout (${VIDEO_TIMEOUT_MS}ms) ${file.name}")
        null
    } catch (e: Exception) {
        Log.w("twig", "thumbs: audioCover failed ${file.name}: $e")
        null
    }

    private fun audioCoverBlocking(file: XFile, maxEdge: Int): Bitmap? {
        // The source (media server) already carries an album cover as a ready-made small image — use it, while the path below would have to drag in the whole song's bytes just to find the embedded image.
        genCover(file, maxEdge)?.let { return it }
        val pic = runCatching {
            if (file.scheme == "file") {
                withRetriever { r -> r.setDataSource(file.path); r.embeddedPicture }
            } else {
                FsRegistry.of(file).openRandom(file).use { src ->
                    withRetriever { r ->
                        r.setDataSource(NetVideoDataSource(src, file.size, emptyList()))
                        r.embeddedPicture
                    }
                }
            }
        }.getOrNull()
        if (pic != null) decodeSampledTo({ ByteArrayInputStream(pic) }, maxEdge)?.let { return it }
        // Fall back to same-directory cover/folder/front/albumart — reuse [resolveDirCoverBytes]'s directory-level cache so that switching songs within the same directory doesn't need to re-list + re-download.
        return resolveDirCoverBytes(file)?.let { b -> decodeSampledTo({ ByteArrayInputStream(b) }, maxEdge) }
    }

    private fun decodeSampledTo(open: () -> InputStream, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { open().use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
    }

    /** MMR reads the embedded cover (mp3 ID3 APIC / FLAC PICTURE / m4a covr). Local files get the path directly; other sources get a random-access data source so MMR reads on demand — mp3/flac covers sit in the file header, but m4a's covr lives in moov (possibly at the tail), and [NetVideoDataSource]'s block cache + read cap keep the traffic bounded. */
    private fun embeddedAudioCover(file: XFile): Bitmap? {
        val pic = runCatching {
            if (file.scheme == "file") {
                withRetriever { r ->
                    r.setDataSource(file.path)
                    r.embeddedPicture
                }
            } else {
                FsRegistry.of(file).openRandom(file).use { src ->
                    withRetriever { r ->
                        r.setDataSource(NetVideoDataSource(src, file.size, emptyList()))
                        r.embeddedPicture
                    }
                }
            }
        }.getOrNull() ?: return null
        return decodeSampled { ByteArrayInputStream(pic) }
    }

    /** Same-directory cover image (256px thumbnail use); bytes come from [resolveDirCoverBytes]'s directory-level cache. */
    private fun dirCover(file: XFile): Bitmap? =
        resolveDirCoverBytes(file)?.let { b -> decodeSampled { ByteArrayInputStream(b) } }

    /**
     * PDF needs a seekable fd (PdfRenderer has no streaming interface), so network sources inherently can't do it ([eligible] already filters them out). ★ But SAF entries are actually local files and the provider can hand back an fd — previously this only matched `scheme == "file"`, so every SAF pdf ended up in the generation queue only to return null, burning a slot and incurring the 60s failure cooldown for nothing.
     */
    private fun genPdf(ctx: Context, file: XFile): Bitmap? {
        val opened = when (file.scheme) {
            "file" -> runCatching {
                ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull()
            com.twig.app.SafFileSystem.SCHEME ->
                runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(file.path), "r") }.getOrNull()
            else -> null
        } ?: return null
        opened.use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val scale = MAX_EDGE.toFloat() / maxOf(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp.setHasAlpha(false)
                    return bmp
                }
            } finally {
                renderer.close()
            }
        }
    }

    // ---- Decoding helpers ----

    private fun decodeSampled(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { open().use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { open().use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
    }

    /** Read at most [cap] bytes from [ins] using a [NET_READ_CHUNK]-sized buffer — dedicated to network streams, fewer round-trips. */
    private fun readCapped(ins: InputStream, cap: Long, sizeHint: Long = 0L): ByteArray {
        val bos = ByteArrayOutputStream(if (sizeHint in 1..cap) sizeHint.toInt() else NET_READ_CHUNK)
        val buf = ByteArray(NET_READ_CHUNK)
        var total = 0L
        while (total < cap) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), cap - total).toInt())
            if (n < 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    private fun head(file: XFile): ByteArray? = runCatching {
        FsRegistry.of(file).openInput(file).use { ins ->
            val cap = if (file.size in 1 until HEAD_BYTES.toLong()) file.size.toInt() else HEAD_BYTES
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val n = ins.read(buf, off, cap - off)
                if (n < 0) break
                off += n
            }
            if (off == cap) buf else buf.copyOf(off)
        }
    }.getOrNull()

    private fun orientationOf(headBytes: ByteArray?): Int {
        if (headBytes == null) return 0
        return runCatching {
            orientationDegrees(ExifInterface(ByteArrayInputStream(headBytes)))
        }.getOrDefault(0)
    }

    private fun orientationDegrees(exif: ExifInterface): Int = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun scaleTo(bmp: Bitmap): Bitmap {
        val edge = maxOf(bmp.width, bmp.height)
        if (edge <= MAX_EDGE) return bmp
        val s = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * s).toInt().coerceAtLeast(1),
            (bmp.height * s).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= DISK_CAP * 8 / 10) break
        }
    }
}
