package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import com.twig.app.OpenFiles
import com.twig.app.SafFileSystem
import com.twig.app.ShareSourceFileSystem
import com.twig.app.StreamProvider
import com.twig.core.XFile
import java.io.File
import java.util.concurrent.Executors

/**
 * One open PDF document, plus the single worker thread that every PdfRenderer call runs on.
 *
 * ★ **Serial rendering is a physical limit, not a tuning choice**: PdfRenderer is not
 * thread-safe, and it allows only **one page open at a time** — `openPage` throws
 * IllegalStateException while another page is still open. Handing pages to a pool the way
 * [Thumbs] does for images would only produce crashes, so everything is funnelled through
 * [post] onto one thread.
 *
 * ★ PdfRenderer needs a **seekable fd**, which is the same constraint [Thumbs] renders PDF
 * thumbnails under: only `file`, SAF and `content://` entries can be opened (the last one is
 * how another app's ACTION_VIEW arrives, and [open] materialises the providers that can only
 * stream). Network / in-archive PDFs still go to an external app.
 *
 * ★ Bitmaps handed to `render` must be ARGB_8888 (PdfRenderer rejects other configs), and
 * the page is drawn **over** whatever the bitmap already holds without clearing it — so
 * every target is erased to white first, exactly as `Thumbs.genPdf` does.
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount

    /** Page sizes in PDF points (1/72"), index-aligned. See [measureFrom] for why they start out uniform. */
    private val pw = FloatArray(pageCount)
    private val ph = FloatArray(pageCount)

    @Volatile private var closed = false

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }

    init {
        // Measure page 0 up front and assume the whole document matches it, so the strip has a
        // usable height immediately. Nearly every PDF really is uniform; [measureFrom] corrects
        // the rest in the background rather than making the first frame wait on a 500-page walk.
        renderer.openPage(0).use { p ->
            pw.fill(p.width.toFloat())
            ph.fill(p.height.toFloat())
        }
    }

    fun pageWidth(i: Int): Float = pw[i]

    fun pageHeight(i: Int): Float = ph[i]

    /** Widest page in the document — the strip's own width, every page is centred inside it. */
    fun maxWidth(): Float = pw.max()

    /** Run [body] on the render thread; dropped once [close] has been called. */
    private fun post(body: () -> Unit) {
        if (closed) return
        runCatching { exec.execute { if (!closed) runCatching { body() } } }
    }

    /**
     * Measure real page sizes starting at [from], in batches, calling [onBatch] with the
     * exhausted index after each one.
     *
     * ★ Deliberately re-posted per batch instead of looping over every page in one task: the
     * executor is FIFO and shared with rendering, so a single 500-page walk would hold the
     * render thread for a few hundred ms and the first screen would stay blank. Batching lets
     * page renders interleave.
     */
    fun measureFrom(from: Int = 1, batch: Int = 32, onBatch: (Int) -> Unit) {
        if (from >= pageCount) return
        post {
            val end = minOf(from + batch, pageCount)
            for (i in from until end) {
                runCatching {
                    renderer.openPage(i).use { p ->
                        pw[i] = p.width.toFloat()
                        ph[i] = p.height.toFloat()
                    }
                }
            }
            onBatch(end)
            measureFrom(end, batch, onBatch)
        }
    }

    /**
     * Render page [index] at [scale] px-per-point, but only the [w]×[h] px sub-rectangle whose
     * top-left sits at ([left], [top]) **in the scaled page's own pixel space**.
     *
     * Rendering a sub-rectangle rather than the whole page is what makes zoom affordable: a
     * full A4 page at 8× would be ~130 MB of bitmap, while a viewport-sized tile stays bounded
     * no matter how far in the user zooms.
     */
    fun renderTile(
        index: Int,
        scale: Float,
        left: Float,
        top: Float,
        w: Int,
        h: Int,
        wanted: (() -> Boolean)? = null,
        onDone: (Bitmap?) -> Unit,
    ) {
        post {
            // ★ Checked here, on the render thread, immediately before the work: the queue is
            // FIFO and a fling can outrun it by several pages, so by the time a request reaches
            // the front the page is often long gone. Rendering it anyway delays the page the
            // user is actually looking at.
            if (wanted != null && !wanted()) {
                onDone(null)
                return@post
            }
            val bmp = runCatching {
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                val m = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-left, -top)
                }
                renderer.openPage(index).use { p ->
                    p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                b.setHasAlpha(false)
                b
            }.getOrNull()
            onDone(bmp)
        }
    }

    /**
     * Horizontal content box of **one page** — the `[left, right]` fractions of the page width
     * that actually carry ink. Null when nothing usable was found (a dark or full-bleed page),
     * in which case the caller falls back to plain fit-width.
     *
     * ★ Measured per page, from the page being read, rather than shared across the document.
     * The trade that buys is real and worth knowing: a title page and a body page can crop to
     * different zoom levels, so re-cropping on a different page changes the zoom. It is scoped
     * to the double-tap for exactly that reason — the crop is measured when you ask for it and
     * then held, so scrolling never moves the zoom under you.
     */
    fun contentBoxOf(index: Int, onDone: (FloatArray?) -> Unit) {
        post { onDone(runCatching { scanPage(index) }.getOrNull()) }
    }

    /**
     * Search pages for [query], visiting them in [order] and reporting each page's hits as they
     * are found. Rectangles come back in page points, the same space [renderTile] scales from.
     *
     * ★ Only callable when [TEXT_SUPPORTED]. `PdfRenderer` gained text APIs in Android 15 with
     * **no SDK-extension backport** (`PdfRendererPreV` has them further back, but it is a
     * separate class that would also have to take over rendering) — so on older systems there is
     * no text to search and the UI hides the entry entirely rather than offering a dead one.
     *
     * ★ Batched and self-reposting for the same reason [measureFrom] is: this shares the one
     * render thread with page drawing, and searching a long document in a single task would
     * freeze scrolling until it finished. [cancelled] is polled between pages so a changed query
     * or a closed search bar stops the walk almost immediately instead of running to the end.
     */
    /**
     * Select the text lying between two points on [page], both given in page points, and report
     * the selected string together with the rectangles covering it.
     *
     * ★ Selection is per page because `selectContent` is: there is no boundary type that spans
     * sheets. A selection that runs off the bottom of one page therefore stops there, which is
     * also what most readers do.
     *
     * ★ Passing the same point twice is how a word is selected — the platform widens a degenerate
     * boundary pair out to the word under it. That is what a long-press relies on.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun selectText(page: Int, sx: Int, sy: Int, ex: Int, ey: Int, onDone: (String, List<RectF>) -> Unit) {
        post {
            val selection = runCatching {
                renderer.openPage(page).use { p ->
                    p.selectContent(
                        SelectionBoundary(Point(sx, sy)),
                        SelectionBoundary(Point(ex, ey)),
                    )
                }
            }.getOrNull()
            val contents = selection?.selectedTextContents.orEmpty()
            onDone(contents.joinToString("") { it.text }, contents.flatMap { it.bounds })
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun searchPages(
        order: IntArray,
        query: String,
        batch: Int = 6,
        cancelled: () -> Boolean,
        onHits: (page: Int, hits: List<List<RectF>>) -> Unit,
        onDone: () -> Unit,
    ) {
        searchBatch(order, 0, query, batch, cancelled, onHits, onDone)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun searchBatch(
        order: IntArray,
        at: Int,
        query: String,
        batch: Int,
        cancelled: () -> Boolean,
        onHits: (page: Int, hits: List<List<RectF>>) -> Unit,
        onDone: () -> Unit,
    ) {
        if (at >= order.size) {
            onDone()
            return
        }
        post {
            if (cancelled()) {
                onDone()
                return@post
            }
            val end = minOf(at + batch, order.size)
            for (k in at until end) {
                if (cancelled()) {
                    onDone()
                    return@post
                }
                val page = order[k]
                val hits = runCatching {
                    renderer.openPage(page).use { p -> p.searchText(query).map { it.bounds } }
                }.getOrNull()
                if (!hits.isNullOrEmpty()) onHits(page, hits)
            }
            searchBatch(order, end, query, batch, cancelled, onHits, onDone)
        }
    }

    /** Content columns of one page, as [left, right] fractions of the page width; null when the page has no detectable ink. */
    private fun scanPage(index: Int): FloatArray? {
        val aspect = ph[index] / pw[index]
        val w = SCAN_WIDTH
        val h = (w * aspect).toInt().coerceIn(1, 4 * SCAN_WIDTH)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply { setScale(w / pw[index], h / ph[index]) }
            renderer.openPage(index).use { p ->
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            // Paper colour first: a scan's background is a noisy grey, not #FFFFFF, so a fixed
            // "is it white" test would call the entire page ink and crop nothing. Take the
            // brightness 20% of pixels sit at or above — on any text page that is solidly paper.
            val hist = IntArray(256)
            for (v in px) hist[lum(v)]++
            var seen = 0
            var paper = 255
            val cut = px.size / 5
            for (v in 255 downTo 0) {
                seen += hist[v]
                if (seen >= cut) { paper = v; break }
            }
            // A dark or full-bleed page has no margins to trim; saying so beats inventing a box.
            if (paper < 96) return null
            val ink = paper - 32

            // ★ Skip a band at the top and bottom of the sheet. Running heads and page numbers
            // very often sit out in the *outer* margin, and a single one of them makes the
            // content box span the full page width — the margin on that side then never gets
            // trimmed, which is not what the reader was asking to fill the screen with.
            val y0 = (h * EDGE_BAND).toInt()
            val y1 = (h * (1 - EDGE_BAND)).toInt().coerceAtLeast(y0 + 1)

            // ★ A column is content only when it holds a real run of ink. This was
            // `maxOf(1, h / 200)`, which for a scan this size is literally **one pixel** — so a
            // speck of scanner dust or JPEG ringing near the sheet edge pinned the box to the
            // full page width and defeated cropping entirely.
            val need = maxOf(2, (y1 - y0) / 100)
            var left = -1
            var right = -1
            for (x in 0 until w) {
                var n = 0
                var y = y0
                while (y < y1) {
                    if (lum(px[y * w + x]) < ink && ++n >= need) break
                    y++
                }
                if (n >= need) {
                    if (left < 0) left = x
                    right = x
                }
            }
            if (left < 0) return null
            // ★ Cap the trim at [MAX_CROP_SIDE] per side — at most half the sheet in total.
            // Margin detection is a heuristic run over a 160px scan, and when it misreads a page
            // (a mostly-blank one, a scan with a dark gutter) an uncapped box zooms into a narrow
            // strip and leaves the reader somewhere unrecognisable with no obvious way back. The
            // cap bounds how wrong a bad guess can look.
            return floatArrayOf(
                (left / w.toFloat()).coerceAtMost(MAX_CROP_SIDE),
                ((right + 1) / w.toFloat()).coerceAtLeast(1f - MAX_CROP_SIDE),
            )
        } finally {
            bmp.recycle()
        }
    }

    private fun lum(c: Int): Int =
        (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8

    fun close() {
        if (closed) return
        closed = true
        // Tear down on the render thread: closing the renderer out from under an in-flight
        // openPage/render is a native crash, and the executor is the only thing that knows
        // when the last one finished.
        runCatching {
            exec.execute {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
            exec.shutdown()
        }
    }

    companion object {
        /** Width in px each page is rendered at for margin detection — enough to separate a glyph from the margin, small enough to be free. */
        private const val SCAN_WIDTH = 160

        /** Fraction of the page height ignored at the top and bottom when looking for horizontal content — the running-head band. */
        private const val EDGE_BAND = 0.06f

        /** Most of the page width that may be trimmed from either side; both sides together can therefore never exceed half the sheet. */
        private const val MAX_CROP_SIDE = 0.25f

        /**
         * Whether this device can read a PDF's text at all.
         *
         * ★ Android 15 (API 35) is a hard floor, not a conservative guess: `searchText` and
         * friends are listed as `sdks=None` in the platform's api-versions, i.e. they were never
         * backported through the SDK extensions. The extension-backported route is the separate
         * `PdfRendererPreV` class, which would have to take over rendering too — deliberately not
         * taken, see [searchPages].
         */
        val TEXT_SUPPORTED: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        private const val TAG = "twig-pdf"

        /**
         * Open [file] for rendering, from **any** source. **Blocking** — call from a worker
         * thread. Returns null only when the bytes turn out not to be a readable PDF, or the
         * source could not be read at all.
         *
         * Two steps, in this order:
         *  1. a seekable fd if one can be had ([fdOf]) — a local file, SAF, another app's
         *     provider, or our own [StreamProvider] proxy fd for sources with real positional
         *     reads (SMB/WebDAV/FTP/SFTP), which lets PdfRenderer seek without downloading;
         *  2. otherwise one bounded copy into the cache ([OpenFiles.materialize]).
         *
         * ★ Step 2 is not a rare edge case, it is the whole reason in-archive PDFs work: an
         * entry inside a *compressed* archive has no positional read at all (openRandom
         * degrades to "reopen and decompress from byte 0"), and a PDF reader seeks constantly —
         * the trailer first, then all over the object table. One copy beats an unbounded number
         * of full decompressions; see docs/lessons/archives.md.
         *
         * ★ Every failure is logged rather than swallowed. This used to return null through
         * three silent `runCatching`s, and a PDF that would not open told nobody why.
         */
        fun open(ctx: Context, file: XFile): PdfDoc? {
            fromFd(fdOf(ctx, file))?.let { return it }
            val local = runCatching { OpenFiles.materialize(ctx, file) }
                .onFailure { Log.w(TAG, "materialize failed: ${file.scheme}:${file.path}", it) }
                .getOrNull() ?: return null
            return fromFd(
                runCatching {
                    ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)
                }.onFailure { Log.w(TAG, "cannot open the cached copy $local", it) }.getOrNull(),
            )
        }

        /**
         * Read-only fd for [file]: local files open straight, another app's `content://` goes
         * through its own provider, and anything else (archives, network, apps tree, …) goes
         * through **our** [StreamProvider] — which already decides proxy-fd vs. materialize by
         * whether the source has real positional reads, so that policy lives in one place.
         * Null when no fd could be obtained; [open] then falls back to a plain copy.
         */
        private fun fdOf(ctx: Context, file: XFile): ParcelFileDescriptor? = when (file.scheme) {
            "file" -> runCatching {
                ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
            }.onFailure { Log.w(TAG, "cannot open local ${file.path}", it) }.getOrNull()
            SafFileSystem.SCHEME, ShareSourceFileSystem.SCHEME ->
                runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(file.path), "r") }
                    .onFailure { Log.w(TAG, "no fd from provider ${file.path}", it) }.getOrNull()
            else ->
                runCatching {
                    ctx.contentResolver.openFileDescriptor(StreamProvider.uriFor(ctx, file), "r")
                }.onFailure { Log.w(TAG, "no fd for ${file.scheme}:${file.path}", it) }.getOrNull()
        }

        /** Hand [pfd] to PdfRenderer, closing it again on any failure — the fd is ours from here on. */
        private fun fromFd(pfd: ParcelFileDescriptor?): PdfDoc? {
            if (pfd == null) return null
            val renderer = runCatching { PdfRenderer(pfd) }
                .onFailure { Log.w(TAG, "PdfRenderer rejected the fd", it) }.getOrNull()
            if (renderer == null || renderer.pageCount <= 0) {
                runCatching { renderer?.close() }
                runCatching { pfd.close() }
                return null
            }
            return runCatching { PdfDoc(pfd, renderer) }.getOrElse {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
                null
            }
        }
    }
}
