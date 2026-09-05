package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import com.twig.core.XFile
import java.util.concurrent.Executors

/**
 * Artwork cache for the current track (singleton): cover bitmap + blurred background + accent
 * color extracted from the cover — compute once, share between the player page and the list page —
 * avoiding the "black flash then image" delay from recomputing on every page entry.
 *
 * Cover source follows [Thumbs.audioCover] (embedded cover first, otherwise same-directory
 * cover/folder/front/albumart), the same logic as the file manager thumbnails.
 */
@UnstableApi
object MusicArt {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-art").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private const val MAX = 5 // keep the cover/blur/accent for the most recent 5 tracks, paired with prefetching the next one for instant display

    // key = "trackId|dark"; accessOrder LRU keeps the most recent MAX tracks
    private val cache = object : LinkedHashMap<String, Snapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean = size > MAX
    }

    /** [bgTone] = average color of the blurred background (the color text actually sits on top of,
     *  for [MusicTint] to derive foreground color); 0 when there is no cover. */
    data class Snapshot(val cover: Bitmap?, val blur: Bitmap?, val accent: Int, val bgTone: Int = 0)

    /** Returns immediately if already cached and matches (including the light/dark variant of the blur); null otherwise. */
    @Synchronized
    fun snapshot(trackId: String, dark: Boolean): Snapshot? = cache[key(trackId, dark)]

    /**
     * Loads the artwork for a track; if the cache hits (cover already computed, blur light/dark
     * matches), callback fires; otherwise it computes in the background. Cache hit callback fires
     * synchronously on the main thread ("instant image on page entry"); callers (e.g. PlaylistActivity)
     * may be on a background thread — in that case post to the main thread before invoking, to
     * avoid crashing by touching Views from a background thread.
     */
    fun load(ctx: Context, trackId: String, file: XFile, dark: Boolean, cb: (Snapshot) -> Unit) {
        synchronized(this) { cache[key(trackId, dark)] }?.let { snap ->
            if (Looper.myLooper() == Looper.getMainLooper()) cb(snap) else main.post { cb(snap) }
            return
        }
        io.execute {
            // Another light/dark variant of the same track has already computed the cover/accent
            // → reuse, only recompute the blur for the current light/dark
            val (reuseCover, reuseAccent) = synchronized(this) {
                val any = cache.entries.firstOrNull { it.key.startsWith("$trackId|") }?.value
                (any?.cover) to (any?.accent ?: 0)
            }
            val c = reuseCover ?: runCatching { Thumbs.audioCover(file, 1024) }.getOrNull()
            val a = if (reuseCover != null && reuseAccent != 0) reuseAccent else (c?.let { extractAccent(it) } ?: 0)
            val bg = c?.let { runCatching { Blur.background(it, 256, dark) }.getOrNull() }
            val tone = bg?.let { runCatching { MusicTint.averageColor(it) }.getOrDefault(0) } ?: 0
            val snap = Snapshot(c, bg, a, tone)
            synchronized(this) { cache[key(trackId, dark)] = snap }
            main.post { cb(snap) }
        }
    }

    /** Prefetch the next track's artwork (skips if already cached); result goes into the cache only, no callback. */
    fun prefetch(ctx: Context, trackId: String, file: XFile, dark: Boolean) {
        synchronized(this) { if (cache.containsKey(key(trackId, dark))) return }
        load(ctx, trackId, file, dark) {}
    }

    private fun key(trackId: String, dark: Boolean) = "$trackId|$dark"

    /**
     * Extracts a "vivid, usable as accent color" color from the cover (AIMP-style color from cover).
     * Shrinks to 24×24, bins by hue into 12 buckets accumulating "saturation × value" weight,
     * takes the weighted average of the dominant bucket, then pulls saturation/value into a range
     * suitable for an accent. Returns 0 for grayscale covers (not enough vivid pixels).
     */
    fun extractAccent(src: Bitmap): Int {
        val n = 24
        val small = runCatching { Bitmap.createScaledBitmap(src, n, n, true) }.getOrNull() ?: return 0
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val binW = DoubleArray(12)
        val binR = DoubleArray(12); val binG = DoubleArray(12); val binB = DoubleArray(12)
        val hsv = FloatArray(3)
        for (c in px) {
            Color.colorToHSV(c, hsv)
            val s = hsv[1]; val v = hsv[2]
            if (s < 0.30f || v < 0.25f || v > 0.98f) continue // too gray / too dark / overexposed: ignore
            val w = (s * v).toDouble()
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            binW[bin] += w
            binR[bin] += Color.red(c) * w; binG[bin] += Color.green(c) * w; binB[bin] += Color.blue(c) * w
        }
        var best = 0
        for (i in 1 until 12) if (binW[i] > binW[best]) best = i
        if (binW[best] <= 0.0) return 0
        val r = (binR[best] / binW[best]).toInt().coerceIn(0, 255)
        val g = (binG[best] / binW[best]).toInt().coerceIn(0, 255)
        val bl = (binB[best] / binW[best]).toInt().coerceIn(0, 255)
        Color.colorToHSV(Color.rgb(r, g, bl), hsv)
        hsv[1] = hsv[1].coerceIn(0.55f, 1f)  // ensure vivid enough
        hsv[2] = hsv[2].coerceIn(0.60f, 0.92f) // constrain brightness to a range that stands out without being harsh
        return Color.HSVToColor(hsv)
    }
}
