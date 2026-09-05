package com.twig.app.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Player page foreground palette: derives text/icon colors from "the background color the text
 * actually sits on" (the blurred background's average color, already including the theme overlay)
 * instead of always using pure white.
 *
 * Pure white on a dark blur has roughly 13:1 contrast and carries none of the background's hue,
 * so it looks like another layer floating above the background — the layering feels stiff. The
 * approach here: **take the background's hue + very low saturation** (same color family as the
 * background), **derive brightness from the target contrast** (WCAG relative-luminance ratio,
 * a tunable "clear enough but not harsh"). Saturation is also scaled by the background's own
 * saturation — a gray cover gets gray text, not a tint that wasn't asked for.
 */
object MusicTint {

    /** A set of foreground layer colors (opaque: blending is baked in, doesn't rely on alpha over an unknown background). */
    data class Palette(
        val primary: Int,   // track title, primary action icons
        val secondary: Int, // artist, time, secondary icons (inactive shuffle/repeat/heart, etc.)
        val tertiary: Int,  // album, index, unplayed waveform, non-current lyrics
        val faint: Int,     // "no lyrics yet" style hints
    )

    // Target contrast (tune these four numbers for softer/sharper). primary at 6:1 is slightly
    // below WCAG AAA body text (7:1) and above AA (4.5:1); much softer than pure white on a dark
    // blur (13~15:1). Lowering these = darker text in dark theme, lighter in light theme (both
    // directions pull toward the background); same knob both ways.
    private const val C_PRIMARY = 6.0
    private const val C_SECONDARY = 4.6
    private const val C_TERTIARY = 3.1
    private const val C_FAINT = 2.4

    fun of(bg: Int): Palette {
        val hsv = FloatArray(3)
        Color.colorToHSV(bg, hsv)
        val hue = hsv[0]
        // Grayer background → weaker foreground tint (pure gray base → pure gray text)
        val satScale = (hsv[1] / 0.25f).coerceIn(0f, 1f)
        val lb = luminance(bg)
        val lighter = lb < 0.18 // dark base → lighter foreground; light base → darker foreground
        fun tone(ratio: Double, sat: Float) = solve(hue, sat * satScale, lb, lighter, ratio)
        return Palette(
            primary = tone(C_PRIMARY, 0.10f),
            secondary = tone(C_SECONDARY, 0.14f),
            tertiary = tone(C_TERTIARY, 0.18f),
            faint = tone(C_FAINT, 0.20f),
        )
    }

    /**
     * Ensures [color] (cover accent) has at least [minRatio] contrast against [bg]: the accent
     * may share the same hue and brightness as the background (the background is itself a blur
     * of this cover), in which case the play button / played waveform would blend in. Only adjust
     * brightness as needed; hue and saturation are preserved.
     */
    fun readable(color: Int, bg: Int, minRatio: Double = 3.0): Int {
        if (contrast(luminance(color), luminance(bg)) >= minRatio) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val lb = luminance(bg)
        return solve(hsv[0], hsv[1], lb, lighter = lb < 0.30, ratio = minRatio)
    }

    /** Is this background considered "light" — should status/nav bar icons be dark? Once the blur is no longer darkened, light covers are actually very light. */
    fun isLight(bg: Int): Boolean = luminance(bg) > 0.40

    /** The actual color when a translucent overlay [over] is composited on [base] (the list page's blur also has a scrim layer on top). */
    fun blend(base: Int, over: Int): Int {
        val a = Color.alpha(over) / 255f
        fun mix(b: Int, o: Int) = (b * (1 - a) + o * a).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(base), Color.red(over)),
            mix(Color.green(base), Color.green(over)),
            mix(Color.blue(base), Color.blue(over)),
        )
    }

    /** Average color of a blurred-background bitmap (downscaled to 16×16 and averaged). */
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
     * With hue [hue] and saturation [sat] fixed, binary-searches HSV's V for the color whose
     * relative luminance satisfies "contrast with [lb] = [ratio]" (relative luminance is
     * monotonic in V, so binary search works). When the target luminance exceeds [0,1], it
     * naturally clamps to that direction's extreme (white/black) and does not fail.
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

    /** WCAG relative luminance. */
    private fun luminance(c: Int): Double =
        0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))

    private fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
}
