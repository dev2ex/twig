package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.databinding.ActivityImageViewerBinding
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Image viewer = slideshow on the same screen (modelled on SambaGallery):
 * a single zoomable view + swipe-to-page; keep the current image while switching / auto-playing, show a spinner in
 * the top-right until the new image is ready. Full-screen immersive; single tap toggles floating bars; double tap
 * cycles zoom modes; shuffle playback.
 *
 * Two entry points:
 * - [start]: a fixed list (tapping a single image from the file list, sibling images already in memory), index
 *   starts from 0 and doesn't change.
 * - [startSlideshow]: takes only the directory, and this Activity uses [scanImages] to recursively scan in the
 *   background — displays the first image found, keeps playing while continuing to scan and append; no need to
 *   wait for the entire tree to finish scanning (the benefit is especially noticeable for network sources);
 *   the toolbar subtitle shows scan progress. Neither entry point stuffs a large list into Intent extras
 *   ([pendingImages] is passed within the process), to avoid thousands of images' path / filename arrays
 *   blowing past the binder 1MB single-transaction limit (TransactionTooLargeException).
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageViewerBinding

    private var images = mutableListOf<XFile>()  // original discovery order; only appended, never reordered — source of truth for real index
    private var order = mutableListOf<Int>()     // playback order (when shuffled, a permutation of images' indices)
    private var pos = 0                          // position within order; current = order[pos] is the real index
    private var shuffle = false
    private var playing = false
    private var loadToken = 0                    // prevent stale loads from overwriting
    private var autoFit = true                   // rotate the image to fit the screen orientation
    private var currentLoading = false           // the current image is loading (downloading)
    private var prefetching = false              // prefetching the next image
    private var scanDone = true                  // whether the slideshow's recursive scan has finished (always true in fixed-list mode)
    private var wantAutoPlay = false              // slideshow mode: start playing automatically once an image is ready
    private var canSelect = true                 // "Select" entry (hidden when opened from external content:// with no tree to sync)

    /** Images ticked in the viewer (deduped by path, preserves selection order); after exit they sync as multi-select on the tree, see [pendingResult]. */
    private val picked = LinkedHashMap<String, XFile>()
    private var changed = false                  // an image was deleted, the file panel needs a refresh

    /** Capacity by byte count: with the downsampling cap relaxed, a single bitmap can reach tens of MB; "6 of those" would OOM. */
    private val cache = object : LruCache<String, Decoded>(
        ((Runtime.getRuntime().maxMemory() / 4) / 1024).toInt().coerceAtLeast(16 * 1024),
    ) {
        override fun sizeOf(key: String, value: Decoded) = value.bmp.byteCount / 1024
    }

    private var shown: Decoded? = null // currently displayed decoded result
    private var shownRot = 0           // total rotation angle of the displayed bitmap relative to the original file (EXIF + autoFit)
    private var hiResToken = 0
    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable {
        if (scanDone && pos == order.lastIndex && !Prefs.slideshowLoop(this)) stop() else go(1)
    }

    private val current get() = order[pos]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.menu.add(R.string.action_more).apply {
            setIcon(R.drawable.ic_more_vert)
            icon?.setTint(android.graphics.Color.WHITE) // default tint doesn't show against the translucent-black background
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { showActions(); true }
        }
        b.image.onTap = { toggleControls() }
        b.image.onLongPress = { showActions() }
        b.image.onPage = { dir -> scheduleHide(); go(dir) }
        b.image.onNeedHiRes = { rect, scale -> loadHiRes(rect, scale) }
        b.btnPrev.setOnClickListener { scheduleHide(); go(-1) }
        b.btnNext.setOnClickListener { scheduleHide(); go(1) }
        b.btnPlay.setOnClickListener { scheduleHide(); if (playing) stop() else start() }
        b.btnShuffle.setOnClickListener { scheduleHide(); toggleShuffle() }
        autoFit = Prefs.imageAutoFit(this)
        b.btnFit.alpha = if (autoFit) 1f else 0.5f
        b.btnFit.setOnClickListener { scheduleHide(); toggleAutoFit() }
        scheduleHide() // floating bar is visible by default, auto-hides after 3 seconds of inactivity

        canSelect = intent.getBooleanExtra(EXTRA_ALLOW_SELECT, true)
        when (intent.getIntExtra(EXTRA_MODE, MODE_FIXED)) {
            MODE_SCAN -> {
                val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: return finish()
                val rootPath = intent.getStringExtra(EXTRA_ROOT_PATH) ?: return finish()
                wantAutoPlay = true
                shuffle = Prefs.slideshowShuffle(this)
                b.btnShuffle.alpha = if (shuffle) 1f else 0.5f
                startScan(XFile(scheme, rootPath, isDir = true))
            }
            else -> {
                val list = pendingImages
                pendingImages = null
                if (list.isNullOrEmpty()) return finish()
                images = list.toMutableList()
                order = images.indices.toMutableList()
                pos = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, images.lastIndex)
                b.btnShuffle.alpha = 0.5f
                onImagesReady()
            }
        }
    }

    private fun toggleAutoFit() {
        autoFit = !autoFit
        Prefs.setImageAutoFit(this, autoFit)
        b.btnFit.alpha = if (autoFit) 1f else 0.5f
        if (order.isNotEmpty()) cache.get(images[current].path)?.let { show(it) } // immediately redraw the current image
    }

    // ---- fullscreen immersive ----

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Device rotation doesn't recreate, but we do need to re-rotate-fit the current image to the new screen orientation
        if (order.isEmpty()) return
        cache.get(images[current].path)?.let { b.image.post { show(it) } }
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- slideshow recursive scan (scan while playing) ----

    private fun startScan(root: XFile) {
        scanDone = false // ★ Must be set to false first; otherwise the first scanTick frame sees scanDone==true and doesn't renew itself, so the total only refreshes once
        b.loading.visibility = View.VISIBLE
        handler.postDelayed(scanTick, SCAN_TICK_MS) // the title's total refreshes on a fixed cadence, decoupled from the scan speed, to avoid triggering a refresh per image
        lifecycleScope.launch {
            var started = false
            runCatching {
                scanImages(root, Prefs.slideshowMaxImages(this@ImageViewerActivity)).collect { f ->
                    val idx = images.size
                    images.add(f)
                    if (shuffle && started) {
                        order.add((pos + 1 + (0..(order.size - pos - 1)).random()), idx)
                    } else {
                        order.add(idx)
                    }
                    if (!started) {
                        started = true
                        pos = 0
                        onImagesReady()
                    }
                }
            }
            scanDone = true
            handler.removeCallbacks(scanTick)
            if (order.isNotEmpty()) updateTitle()
            if (!started) {
                if (images.isEmpty()) {
                    Toast.makeText(this@ImageViewerActivity, R.string.no_images, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    started = true
                    pos = 0
                    onImagesReady()
                }
            }
        }
    }

    /** Title's "current/total" refreshes the total on a fixed cadence (no need to wait for an image switch to see the latest scanned count).
     *  Only updates the text, doesn't touch floating-bar visibility — during scanning the floating bar still auto-hides after 3 seconds
     *  of inactivity, and the next reveal shows the latest total. */
    private val scanTick: Runnable = object : Runnable {
        override fun run() {
            if (order.isNotEmpty()) updateTitle()
            if (!scanDone) handler.postDelayed(this, SCAN_TICK_MS)
        }
    }

    /** Image list (at least the first image) is ready: begin displaying + slideshow mode auto-plays. */
    private fun onImagesReady() {
        loadCurrent()
        if (wantAutoPlay) start()
    }

    // ---- paging / loading ----

    private fun go(delta: Int) {
        if (order.isEmpty()) return
        pos = (pos + delta + order.size) % order.size
        loadCurrent() // the next auto-play tick is scheduled in onCurrentReady only after the current image finishes loading
    }

    private fun loadCurrent() {
        val idx = current
        val path = images[idx].path
        updateTitle()
        val token = ++loadToken

        cache.get(path)?.let {
            currentLoading = false; updateSpinner()
            show(it)
            onCurrentReady()
            return
        }
        // Keep the current image (don't clear it); the spinner in the top-right shows it's downloading / loading
        currentLoading = true; updateSpinner()
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeImage(this@ImageViewerActivity, images[idx]) } }.getOrNull()
            if (token != loadToken) return@launch // already switched to a different image
            currentLoading = false; updateSpinner()
            if (bmp != null) { cache.put(path, bmp); show(bmp) }
            onCurrentReady()
        }
    }

    /** Current image finished loading: only now do we schedule the next auto-play tick and prefetch the next image
     *  (don't start downloading later images until the current one has finished loading). */
    private fun onCurrentReady() {
        if (playing) scheduleNext()
        prefetchNext()
    }

    /** Display the image: rotate to fit the screen orientation (don't change the screen orientation, only rotate the image content); pass in the actual size multiplier. */
    private fun show(d: Decoded) {
        val extra = if (autoFit) screenFitRotation(d.bmp) else 0
        shown = d
        shownRot = (d.exifRot + extra) % 360
        b.image.setImage(if (extra == 0) d.bmp else rotate(d.bmp, extra), d.actual)
    }

    /** Rotate by 90° when the image's orientation doesn't match the screen's, to fill the current screen "horizontally/vertically". */
    private fun screenFitRotation(bmp: Bitmap): Int {
        val screenLandscape = resources.displayMetrics.widthPixels >= resources.displayMetrics.heightPixels
        val imgLandscape = bmp.width > bmp.height
        return if (imgLandscape == screenLandscape || bmp.width == bmp.height) 0 else 90
    }

    /** Prefetch the next image into the cache (with spinner); if the current image is still loading, don't prefetch — avoid "downloading ahead". */
    private fun prefetchNext() {
        if (currentLoading || order.isEmpty()) return
        val i = order[(pos + 1) % order.size]
        val p = images[i].path
        if (cache.get(p) != null) return
        prefetching = true; updateSpinner()
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeImage(this@ImageViewerActivity, images[i]) } }.getOrNull()
            prefetching = false; updateSpinner()
            if (bmp != null && cache.get(p) == null) cache.put(p, bmp)
        }
    }

    private fun updateSpinner() {
        b.loading.visibility = if (currentLoading || prefetching) View.VISIBLE else View.GONE
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, Prefs.slideshowIntervalMs(this))
    }

    // ---- play / shuffle ----

    private fun start() {
        playing = true
        b.btnPlay.setImageResource(R.drawable.ic_pause)
        if (Prefs.slideshowKeepAwake(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!currentLoading) scheduleNext() // current image is still loading; wait for it to be ready before starting the timer
    }

    private fun stop() {
        playing = false
        b.btnPlay.setImageResource(R.drawable.ic_play)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(advance)
    }

    private fun toggleShuffle() {
        if (images.isEmpty()) return
        val cur = current
        shuffle = !shuffle
        order = images.indices.toMutableList()
        if (shuffle) { order.shuffle(); order.remove(cur); order.add(0, cur) }
        pos = order.indexOf(cur).coerceAtLeast(0)
        b.btnShuffle.alpha = if (shuffle) 1f else 0.5f
    }

    private fun toggleControls() {
        setControlsVisible(b.toolbar.visibility != View.VISIBLE)
    }

    private fun setControlsVisible(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        b.toolbar.visibility = v
        b.controls.visibility = v
        if (show) scheduleHide() else handler.removeCallbacks(hideControls)
    }

    private val hideControls = Runnable { setControlsVisible(false) }

    /** Floating / bottom bars auto-hide after 3 seconds of inactivity; every interaction (page / play / shuffle / fit button, manual reveal) restarts the timer. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, HIDE_CONTROLS_MS)
    }

    /** Title shows the "real index" (this image's position in the original scan / browse order), not the shuffle-playback sequence number —
     *  during shuffle the sequence number jumps around, but it reflects this image's actual position in the directory. */
    private fun updateTitle() {
        val f = images.getOrNull(current) ?: return
        b.toolbar.title = "${current + 1}/${images.size}  ${f.name}"
        @Suppress("DEPRECATION") setTaskDescription(ActivityManager.TaskDescription(f.name))
    }

    // ---- action menu (long-press image / title bar "more") ----

    /**
     * The action menu for the current image. While auto-playing, opening the menu first pauses playback — otherwise
     * with the dialog open the image keeps advancing and tapping "delete" would delete a different one from the one
     * the user is looking at.
     */
    private fun showActions() {
        val file = images.getOrNull(current) ?: return
        if (playing) stop()
        handler.removeCallbacks(hideControls) // don't let the floating bar auto-hide while the menu is open
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (canSelect) {
            val on = picked.containsKey(file.path)
            labels += getString(if (on) R.string.action_unselect else R.string.action_select)
            acts += { togglePick(file) }
        }
        labels += getString(R.string.action_delete)
        acts += { confirmDelete(file) }
        labels += getString(R.string.action_share)
        acts += { runCatching { OpenFiles.share(this, file) } }
        labels += getString(R.string.image_info)
        acts += { MusicDialogs.showInfo(this, file, R.string.image_info) }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    /** Tick / untick the current image; result is published to [pendingResult] as it changes, picked up by the file panel when it returns to the foreground. */
    private fun togglePick(file: XFile) {
        if (picked.remove(file.path) == null) picked[file.path] = file
        toast(getString(R.string.title_selected_count, picked.size))
        publishResult()
    }

    private fun confirmDelete(file: XFile) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_delete, 1))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> delete(file) }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    /**
     * Delete the current image and remove it from the list. [images] is the "original discovery order"; [order] holds its
     * indices, so deleting one shifts every later index — [order] must be rebuilt entirely (anything with an index
     * greater than the deleted one decrements by 1), you can't just remove one element.
     * After deletion, [pos] stays put and points to the next image in the playback order.
     */
    private fun delete(file: XFile) {
        val idx = images.indexOfFirst { it.path == file.path && it.scheme == file.scheme }
        if (idx < 0) return
        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { FsRegistry.of(file).delete(file) }.exceptionOrNull()
            }
            if (err != null) { toast(err.message ?: getString(R.string.info_failed)); return@launch }
            cache.remove(file.path)
            picked.remove(file.path)
            images.removeAt(idx)
            order = order.filter { it != idx }.map { if (it > idx) it - 1 else it }.toMutableList()
            changed = true
            publishResult()
            if (order.isEmpty()) { finish(); return@launch }
            pos = pos.coerceAtMost(order.lastIndex)
            loadCurrent()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun publishResult() {
        pendingResult = Result(picked.values.toList(), changed)
    }

    // ---- hi-res region after zooming in (BitmapRegionDecoder) ----

    /** The base bitmap has been upscaled with interpolation; re-decode a hi-res region from the original file for the visible area and overlay it. */
    private fun loadHiRes(rect: RectF, scale: Float) {
        val d = shown ?: return
        if (d.actual <= 1f) return // original has no more pixels to dig out
        val rot = shownRot
        val vw = b.image.width.coerceAtLeast(1) // View dimensions read on the main thread before entering IO
        val vh = b.image.height.coerceAtLeast(1)
        val token = ++hiResToken
        lifecycleScope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodeRegion(d, rot, rect, scale, vw, vh) } }.getOrNull()
            if (token != hiResToken || bmp == null) return@launch
            if (shown === d && shownRot == rot) b.image.setHiRes(bmp, rect)
        }
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advance)
        handler.removeCallbacks(scanTick)
        handler.removeCallbacks(hideControls)
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SCHEME = "scheme"
        private const val EXTRA_ROOT_PATH = "root_path"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_ALLOW_SELECT = "allow_select"
        private const val MODE_FIXED = 0
        private const val MODE_SCAN = 1
        private const val SCAN_TICK_MS = 400L
        private const val HIDE_CONTROLS_MS = 3000L

        /** Pixel cap for hi-res regions: a multiple of the visible area, plus an absolute cap (≈48MB @ ARGB_8888). */
        /** In-process one-shot transfer slot for fixed-list mode: avoids passing a large list via Intent extras, which
         * triggers TransactionTooLargeException (binder's single-transaction cap is 1MB; a few thousand images' path +
         * filename array in a single directory easily exceeds it). Cleared immediately after onCreate reads it. */
        @Volatile private var pendingImages: List<XFile>? = null

        /**
         * Result from operations in the viewer: ticked images ([selection]) and whether any image was deleted ([changed]).
         * Picked up once by [PaneFragment] via [takeResult] when it returns to the foreground — the viewer is started
         * with a plain startActivity (the image list goes through [pendingImages] in-process, not through Intent),
         * and the result is returned via the same in-process slot, avoiding an extra ActivityResult contract.
         */
        class Result(val selection: List<XFile>, val changed: Boolean)

        @Volatile private var pendingResult: Result? = null

        fun takeResult(): Result? = pendingResult.also { pendingResult = null }

        /**
         * Fixed image list (e.g. sibling images in the same directory, already in memory), starts at index, doesn't auto-play.
         * [allowSelect] = false is for external content:// opens (no file tree to sync, so no "Select").
         */
        fun start(context: Context, images: List<XFile>, startIndex: Int, allowSelect: Boolean = true) {
            if (images.isEmpty()) return
            pendingImages = images
            pendingResult = null // any stale result nobody picked up last round is discarded here
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_FIXED)
                    putExtra(EXTRA_INDEX, startIndex)
                    putExtra(EXTRA_ALLOW_SELECT, allowSelect)
                },
            )
        }

        /** Slideshow: takes only the directory; the viewer recursively scans in the background, plays while scanning, and auto-plays. */
        fun startSlideshow(context: Context, dir: XFile) {
            pendingResult = null
            context.startActivity(
                Intent(context, ImageViewerActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_SCAN)
                    putExtra(EXTRA_SCHEME, dir.scheme)
                    putExtra(EXTRA_ROOT_PATH, dir.path)
                },
            )
        }
    }
}
