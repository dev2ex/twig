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
 * 当前曲目的封面美术资源缓存(单例):封面位图 + 毛玻璃背景 + 从封面提取的主色调,
 * 一次算好、播放页与列表页共享复用——避免每次进页面重算导致的"黑一下再出图"延迟。
 *
 * 封面来源沿用 [Thumbs.audioCover](内嵌封面优先,无则同目录 cover/folder/front/albumart),
 * 与文件管理器缩略图同一套逻辑。
 */
@UnstableApi
object MusicArt {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-music-art").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private const val MAX = 5 // 最近 5 首的封面/毛玻璃/主色,配合预取下一首即时出图

    // key = "trackId|dark";accessOrder LRU 保留最近 MAX 首
    private val cache = object : LinkedHashMap<String, Snapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>?): Boolean = size > MAX
    }

    /** [bgTone] = 毛玻璃背景的平均色(文字实际压在上面的颜色,供 [MusicTint] 反推前景色);无封面时 0。 */
    data class Snapshot(val cover: Bitmap?, val blur: Bitmap?, val accent: Int, val bgTone: Int = 0)

    /** 已缓存且匹配(含对应明暗的毛玻璃)时立即返回,供进页面瞬间上图;否则 null。 */
    @Synchronized
    fun snapshot(trackId: String, dark: Boolean): Snapshot? = cache[key(trackId, dark)]

    /**
     * 载入某曲目的封面资源;命中缓存(封面已算过、毛玻璃明暗匹配)则回调,否则后台算。
     * 命中在主线程同步回调("进页面瞬间上图");调用方(如 PlaylistActivity)可能在后台线程,
     * 那种情况 post 到主线程再回,避免在后台线程摸 View 崩溃。
     */
    fun load(ctx: Context, trackId: String, file: XFile, dark: Boolean, cb: (Snapshot) -> Unit) {
        synchronized(this) { cache[key(trackId, dark)] }?.let { snap ->
            if (Looper.myLooper() == Looper.getMainLooper()) cb(snap) else main.post { cb(snap) }
            return
        }
        io.execute {
            // 同一曲目其它明暗变体已算过封面/主色 → 复用,只按当前明暗重算毛玻璃
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

    /** 预取下一首封面(已缓存则跳过);结果只进缓存不回调。 */
    fun prefetch(ctx: Context, trackId: String, file: XFile, dark: Boolean) {
        synchronized(this) { if (cache.containsKey(key(trackId, dark))) return }
        load(ctx, trackId, file, dark) {}
    }

    private fun key(trackId: String, dark: Boolean) = "$trackId|$dark"

    /**
     * 从封面提取一个"鲜明可用作强调色"的颜色(AIMP 式随封面变色)。
     * 缩到 24×24,按色相分 12 桶累加"饱和度×明度"权重,取主导桶的加权平均色,
     * 再把饱和度/明度拉到适合当强调色的区间。灰度封面(无够鲜明像素)返回 0。
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
            if (s < 0.30f || v < 0.25f || v > 0.98f) continue // 太灰/太暗/过曝的忽略
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
        hsv[1] = hsv[1].coerceIn(0.55f, 1f)  // 保证够鲜艳
        hsv[2] = hsv[2].coerceIn(0.60f, 0.92f) // 亮度控制在既醒目又不刺眼的区间
        return Color.HSVToColor(hsv)
    }
}
