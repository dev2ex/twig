package com.twig.app.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 播放页前景配色:由「文字实际压在上面的那个背景色」(毛玻璃背景的平均色,已含主题遮罩)
 * 反推文字/图标颜色,而不是一律纯白。
 *
 * 纯白压在深色毛玻璃上对比度 13:1 上下,又完全没有背景的色相,看着像浮在背景之上的
 * 另一层、层次生硬。这里的做法:**取背景色的色相 + 很低的饱和度**(与背景同色系),
 * **亮度按目标对比度反解**(WCAG 相对亮度比,可控地"够看清但不刺眼")。饱和度还要
 * 按背景自身的饱和度缩放——灰底封面就给灰字,不会莫名染上一层色。
 */
object MusicTint {

    /** 一套前景层级色(不透明:混色已经算进去了,不靠 alpha 叠在未知背景上)。 */
    data class Palette(
        val primary: Int,   // 曲名、主要操作图标
        val secondary: Int, // 艺术家、时间、次级图标(未激活的随机/循环/心形等)
        val tertiary: Int,  // 专辑、序号、未播波形、非当前歌词
        val faint: Int,     // "暂无歌词"这类提示
    )

    // 目标对比度(想再柔和/再清晰只调这四个数)。primary 6:1 略低于 WCAG AAA 正文的 7:1、
    // 高于 AA 的 4.5:1;比纯白压深色毛玻璃的 13~15:1 柔和得多。降这个数 = 深色主题下字更暗、
    // 浅色主题下字更亮(都是往背景靠),两个方向同一个旋钮。
    private const val C_PRIMARY = 6.0
    private const val C_SECONDARY = 4.6
    private const val C_TERTIARY = 3.1
    private const val C_FAINT = 2.4

    fun of(bg: Int): Palette {
        val hsv = FloatArray(3)
        Color.colorToHSV(bg, hsv)
        val hue = hsv[0]
        // 背景越灰,前景的染色越弱(纯灰底 → 纯灰字)
        val satScale = (hsv[1] / 0.25f).coerceIn(0f, 1f)
        val lb = luminance(bg)
        val lighter = lb < 0.18 // 暗底 → 前景更亮;亮底 → 前景更暗
        fun tone(ratio: Double, sat: Float) = solve(hue, sat * satScale, lb, lighter, ratio)
        return Palette(
            primary = tone(C_PRIMARY, 0.10f),
            secondary = tone(C_SECONDARY, 0.14f),
            tertiary = tone(C_TERTIARY, 0.18f),
            faint = tone(C_FAINT, 0.20f),
        )
    }

    /**
     * 保证 [color](封面主色调)压在 [bg] 上至少有 [minRatio] 对比度:主色调可能正好跟
     * 背景同色同亮度(背景本来就是这张封面糊出来的),那样播放键/已播波形会糊进背景里。
     * 只按需要动亮度,色相/饱和度不变。
     */
    fun readable(color: Int, bg: Int, minRatio: Double = 3.0): Int {
        if (contrast(luminance(color), luminance(bg)) >= minRatio) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val lb = luminance(bg)
        return solve(hsv[0], hsv[1], lb, lighter = lb < 0.30, ratio = minRatio)
    }

    /** 这个背景算"亮底"吗——状态栏/导航栏图标该用深色。毛玻璃不再压暗后亮封面会真的很亮。 */
    fun isLight(bg: Int): Boolean = luminance(bg) > 0.40

    /** 带 alpha 的遮罩 [over] 叠在 [base] 上之后的实际颜色(列表页毛玻璃上还压着一层 scrim)。 */
    fun blend(base: Int, over: Int): Int {
        val a = Color.alpha(over) / 255f
        fun mix(b: Int, o: Int) = (b * (1 - a) + o * a).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(base), Color.red(over)),
            mix(Color.green(base), Color.green(over)),
            mix(Color.blue(base), Color.blue(over)),
        )
    }

    /** 毛玻璃背景位图的平均色(缩到 16×16 后取均值)。 */
    fun averageColor(bm: Bitmap): Int {
        val n = 16
        val small = runCatching { Bitmap.createScaledBitmap(bm, n, n, true) }.getOrNull() ?: return 0
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        var r = 0L; var g = 0L; var b = 0L
        for (c in px) { r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        val cnt = px.size
        return Color.rgb((r / cnt).toInt(), (g / cnt).toInt(), (b / cnt).toInt())
    }

    /**
     * 在色相 [hue]、饱和度 [sat] 固定的前提下,二分 HSV 的 V 找出相对亮度满足
     * 「与 [lb] 的对比度 = [ratio]」的颜色(相对亮度对 V 单调递增,二分成立)。
     * 目标亮度超出 [0,1] 时自然收敛到该方向的极值(白/黑),不会失败。
     */
    private fun solve(hue: Float, sat: Float, lb: Double, lighter: Boolean, ratio: Double): Int {
        val target = (if (lighter) (lb + 0.05) * ratio - 0.05 else (lb + 0.05) / ratio - 0.05)
            .coerceIn(0.0, 1.0)
        var lo = 0f; var hi = 1f
        val hsv = floatArrayOf(hue, sat, 0f)
        repeat(18) {
            val mid = (lo + hi) / 2f
            hsv[2] = mid
            if (luminance(Color.HSVToColor(hsv)) < target) lo = mid else hi = mid
        }
        hsv[2] = (lo + hi) / 2f
        return Color.HSVToColor(hsv)
    }

    private fun contrast(l1: Double, l2: Double): Double =
        (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)

    /** WCAG 相对亮度。 */
    private fun luminance(c: Int): Double =
        0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
}
