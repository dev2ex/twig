package com.twig.app.ui

/**
 * Pure functions for the hex viewer that don't touch Views: how many bytes per row, how many digits for
 * the offset column, how to parse the hex in the search box. Lifted out so they can be tested directly in
 * plain JVM unit tests — when the layout is miscalculated, the on-screen symptom is "mysterious horizontal
 * scrolling" or "a big empty strip on the right", and it's very hard to work backward from the symptom to
 * which term was over-counted.
 */
object HexLayout {

    /** Number of hex digits for the offset column: enough to represent the whole file, small files don't need to fill the full 8. */
    fun offsetDigits(size: Long): Int {
        var d = 6
        while (d < 16 && size > (1L shl (4 * d)) - 1) d += 2
        return d
    }

    /** Two bytes per group; the gap **within** the group is narrowed to this width (see the character budget in [bytesPerRow]). */
    const val PAIR_GAP = 0.45f

    /**
     * How many bytes fit in one row. Character budget (monospace font, unit = one character width):
     *
     * ```
     * offset offDigits digits │ 1 cell │ per byte hex 2 cells + within-group PAIR_GAP / between-group 1 cell │ 1 cell │ per byte 1 cell char
     * ```
     *
     * Two bytes per group, so every 2 bytes add one narrow gap and remove one full space, which works out
     * to `2 + 1 + (PAIR_GAP + 1) / 2` cells per byte; the fixed overhead is only `offDigits + 1` — one cell
     * after the offset and one cell before the char column, then subtracting the non-existent trailing
     * between-group gap cancels one cell out.
     *
     * Round to a multiple of 2 so the last group isn't half a group. The divider pixels are subtracted from
     * [usablePx] by the caller first.
     */
    fun bytesPerRow(usablePx: Float, charW: Float, offDigits: Int, min: Int = 4, max: Int = 64): Int {
        if (charW <= 0f) return min
        val cols = usablePx / charW
        val perByte = 2f + 1f + (PAIR_GAP + 1f) / 2f
        val fit = ((cols - offDigits - 1f) / perByte).toInt().coerceIn(min, max)
        return (fit / 2 * 2).coerceAtLeast(min)
    }

    private val C_PREFIX = Regex("0[xX]")

    /**
     * Hex from the search box: ignore all non-hex characters like spaces / commas, and discard the trailing half
     * byte if odd length. The `0x` prefix must be stripped **in full first** — if you only "filter out non-hex
     * characters", that `0` in `0x4D` would be kept as a real half-nibble and the whole string would shift
     * (pasted byte sequences often carry this prefix).
     */
    fun parseHex(s: String): ByteArray {
        val digits = C_PREFIX.replace(s, "").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val n = digits.length / 2
        return ByteArray(n) {
            ((digits[it * 2].digitToInt(16) shl 4) or digits[it * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
