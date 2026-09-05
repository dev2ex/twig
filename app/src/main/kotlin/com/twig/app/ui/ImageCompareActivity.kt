package com.twig.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.Format
import com.twig.app.R
import com.twig.app.databinding.ActivityImageCompareBinding
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Side-by-side image comparison: two images placed side by side, with linked zoom / pan.
 *
 * Decoding goes through [decodeImage] + [decodeRegion] (the same set as the image viewer), so zooming in
 * still re-decodes a hi-res region — "zoom in to compare details" is the primary use of this page; being
 * blurry defeats the purpose.
 *
 * Synchronisation uses **normalised image coordinates** ([ZoomableImageView.viewport]): even images of different
 * sizes (the most common case being the same photo re-saved at a different resolution) have to align the same
 * region of content; syncing by viewport pixel offsets would immediately skew.
 *
 * In portrait we stack top and bottom rather than side by side: the horizontal gesture stays free for image
 * panning, without competing with "switch sides"; half-screen-wide photos would also be unreadable.
 */
class ImageCompareActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageCompareBinding
    private var fileA: XFile? = null
    private var fileB: XFile? = null
    private var decA: Decoded? = null
    private var decB: Decoded? = null
    private var sync = true
    private var itemSync: MenuItem? = null
    // ★ One token per side: with a shared one, a hi-res chunk request from B would mark the chunk A is about to fill in as stale
    private var tokenA = 0
    private var tokenB = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityImageCompareBinding.inflate(layoutInflater)
        setContentView(b.root)
        NavBarTint.apply(this, android.graphics.Color.BLACK) // both sides of this page are pure-black image-viewing areas

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.toolbar.title = title
        b.toolbar.setNavigationOnClickListener { finish() }
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(title))
        buildMenu()
        applyOrientation()

        val a = fileFrom(EXTRA_A_SCHEME, EXTRA_A_PATH, EXTRA_A_SIZE, EXTRA_A_TIME)
        val c = fileFrom(EXTRA_B_SCHEME, EXTRA_B_PATH, EXTRA_B_SIZE, EXTRA_B_TIME)
        if (a == null || c == null) return finish()
        fileA = a
        fileB = c

        // Default to displaying at the original image's true pixels, only shrinking once it overflows the container — when comparing,
// what you most want to see is "how big / how sharp this one really is"; FIT would upscale small images so they look just as clear as large ones, which is misleading
        b.imageA.initialMode = ZoomableImageView.MODE_FIT_ACTUAL
        b.imageB.initialMode = ZoomableImageView.MODE_FIT_ACTUAL
        wireView(b.imageA, b.imageB) { decA }
        wireView(b.imageB, b.imageA) { decB }
        load()
    }

    private fun fileFrom(schemeKey: String, pathKey: String, sizeKey: String, timeKey: String): XFile? {
        val scheme = intent.getStringExtra(schemeKey) ?: return null
        val path = intent.getStringExtra(pathKey) ?: return null
        return XFile(
            scheme, path, isDir = false,
            size = intent.getLongExtra(sizeKey, 0L),
            lastModified = intent.getLongExtra(timeKey, 0L),
        )
    }

    /** Shared wiring for both sides: viewport changes push to the other side; on zoom in, re-decode a hi-res region. */
    private fun wireView(self: ZoomableImageView, other: ZoomableImageView, dec: () -> Decoded?) {
        self.onViewport = {
            if (sync) self.viewport()?.let { other.applyViewport(it) }
        }
        self.onNeedHiRes = { rect, scale -> loadHiRes(self, dec(), rect, scale, self === b.imageA) }
    }

    private fun load() {
        val a = fileA ?: return
        val c = fileB ?: return
        lifecycleScope.launch {
            val (da, db) = withContext(Dispatchers.IO) {
                runCatching { decodeImage(this@ImageCompareActivity, a) }.getOrNull() to
                    runCatching { decodeImage(this@ImageCompareActivity, c) }.getOrNull()
            }
            decA = da
            decB = db
            da?.let { b.imageA.setImage(it.bmp, it.actual) }
            db?.let { b.imageB.setImage(it.bmp, it.actual) }
            b.infoA.text = sideInfo(getString(R.string.compare_side_left), a, da)
            b.infoB.text = sideInfo(getString(R.string.compare_side_right), c, db)
            b.toolbar.subtitle = summary(da, db)
        }
    }

    /** One side's info bar: `left · 1920×1080 · 2.3 MB · time`; if it can't be decoded, say so — don't leave a black square for the user to guess. */
    private fun sideInfo(side: String, f: XFile, d: Decoded?): String {
        val dim = if (d == null) getString(R.string.img_cmp_undecodable)
        else "${(d.bmp.width * d.actual).toInt()}×${(d.bmp.height * d.actual).toInt()}"
        return "$side · $dim · ${Format.size(f.size)} · ${Format.time(f.lastModified)}"
    }

    /** The subtitle states only the conclusion: whether the sizes match, which side has more pixels — that's the first thing you want to know when looking at them side by side. */
    private fun summary(da: Decoded?, db: Decoded?): String {
        if (da == null || db == null) return ""
        val pa = (da.bmp.width * da.actual).toLong() * (da.bmp.height * da.actual).toLong()
        val pb = (db.bmp.width * db.actual).toLong() * (db.bmp.height * db.actual).toLong()
        return when {
            pa == pb -> getString(R.string.img_cmp_same_size)
            pa > pb -> getString(R.string.img_cmp_bigger, getString(R.string.compare_side_left))
            else -> getString(R.string.img_cmp_bigger, getString(R.string.compare_side_right))
        }
    }

    private fun loadHiRes(view: ZoomableImageView, d: Decoded?, rect: RectF, scale: Float, isA: Boolean) {
        if (d == null || d.actual <= 1f) return // original has no more pixels to dig out
        val vw = view.width.coerceAtLeast(1)
        val vh = view.height.coerceAtLeast(1)
        val token = if (isA) ++tokenA else ++tokenB
        lifecycleScope.launch {
            val bmp = runCatching {
                // The display bitmap already has EXIF rotation baked in; we don't apply autoFit here, total rotation is exifRot
                withContext(Dispatchers.IO) { decodeRegion(d, d.exifRot, rect, scale, vw, vh) }
            }.getOrNull()
            if (token != (if (isA) tokenA else tokenB) || bmp == null) return@launch
            view.setHiRes(bmp, rect)
        }
    }

    private fun buildMenu() {
        val m = b.toolbar.menu
        m.showIcons()
        itemSync = m.add(getString(R.string.img_cmp_sync)).apply {
            isCheckable = true
            isChecked = true
            icon = ContextCompat.getDrawable(this@ImageCompareActivity, R.drawable.ic_sync)?.mutate()
                ?.apply { setTint(ContextCompat.getColor(this@ImageCompareActivity, R.color.white)) }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                sync = !sync
                it.isChecked = sync
                // Re-align: when toggled off and on, both sides should immediately return to the same viewport
                if (sync) b.imageA.viewport()?.let { v -> b.imageB.applyViewport(v) }
                true
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        b.panes.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Both sides each take half of the main axis: width in landscape, height in portrait
        for (side in listOf(b.sideA, b.sideB)) {
            side.layoutParams = LinearLayout.LayoutParams(
                if (land) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                if (land) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                1f,
            )
        }
        b.divider.layoutParams = LinearLayout.LayoutParams(
            if (land) dp(1) else LinearLayout.LayoutParams.MATCH_PARENT,
            if (land) LinearLayout.LayoutParams.MATCH_PARENT else dp(1),
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_A_SCHEME = "a_scheme"
        private const val EXTRA_A_PATH = "a_path"
        private const val EXTRA_A_SIZE = "a_size"
        private const val EXTRA_A_TIME = "a_time"
        private const val EXTRA_B_SCHEME = "b_scheme"
        private const val EXTRA_B_PATH = "b_path"
        private const val EXTRA_B_SIZE = "b_size"
        private const val EXTRA_B_TIME = "b_time"
        private const val EXTRA_TITLE = "title"

        fun start(ctx: Context, left: XFile, right: XFile, title: String) {
            ctx.startActivity(
                Intent(ctx, ImageCompareActivity::class.java)
                    .putExtra(EXTRA_A_SCHEME, left.scheme)
                    .putExtra(EXTRA_A_PATH, left.path)
                    .putExtra(EXTRA_A_SIZE, left.size)
                    .putExtra(EXTRA_A_TIME, left.lastModified)
                    .putExtra(EXTRA_B_SCHEME, right.scheme)
                    .putExtra(EXTRA_B_PATH, right.path)
                    .putExtra(EXTRA_B_SIZE, right.size)
                    .putExtra(EXTRA_B_TIME, right.lastModified)
                    .putExtra(EXTRA_TITLE, title),
            )
        }
    }
}
