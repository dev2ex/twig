package com.twig.app.ui

import com.twig.app.TextCodec

/** A single caption: start/end time (ms) and text. */
data class SubCue(val start: Int, val end: Int, val text: String)

/**
 * Minimal subtitle parser: SRT / ASS / SSA.
 * We parse them ourselves (instead of handing them to MediaPlayer) so that any
 * source (SMB, etc.) just needs an openInput() to read the bytes, and so we can
 * handle the GBK-encoded subtitle files that are common domestically.
 */
object SubtitleParser {

    /** Supported external subtitle extensions (lowercase). */
    val EXTENSIONS = setOf("srt", "ass", "ssa")

    fun parse(bytes: ByteArray): List<SubCue> {
        val text = decode(bytes)
        return if (text.contains("Dialogue:")) parseAss(text) else parseSrt(text)
    }

    /** BOM → strict UTF-8 → the user-ordered encodings from settings, see [com.twig.app.TextCodec]. */
    private fun decode(b: ByteArray): String = TextCodec.decode(b).text

    // ---- SRT ----

    private val srtTime =
        Regex("""(\d+):(\d+):(\d+)[,.](\d{1,3})\s*-->\s*(\d+):(\d+):(\d+)[,.](\d{1,3})""")

    private fun parseSrt(s: String): List<SubCue> {
        val cues = ArrayList<SubCue>()
        for (block in s.replace("\r", "").split(Regex("\n{2,}"))) {
            val lines = block.trim().lines()
            val ti = lines.indexOfFirst { srtTime.containsMatchIn(it) }
            if (ti < 0) continue
            val g = srtTime.find(lines[ti])!!.groupValues
            val text = lines.drop(ti + 1).joinToString("\n")
                .replace(Regex("<[^>]{1,16}>"), "") // strip <i>/<b>/<font …> etc. tags
                .trim()
            if (text.isEmpty()) continue
            cues.add(SubCue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), text))
        }
        return cues.sortedBy { it.start }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Int =
        h.toInt() * 3600_000 + m.toInt() * 60_000 + s.toInt() * 1000 + frac.padEnd(3, '0').toInt()

    // ---- ASS / SSA ----
    // The Format: line inside [Events] decides field order (V4+/V4/variants differ); follow it to find Start/End/Text.

    private val assTime = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
    private val assDrawing = Regex("""\\p[1-9]""") // {\p1} vector drawing, not text
    private val assTags = Regex("""\{[^}]*\}""")

    private fun parseAss(s: String): List<SubCue> {
        val cues = ArrayList<SubCue>()
        // Default V4+ layout: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fieldCount = 10
        for (raw in s.lineSequence()) {
            val line = raw.trim()
            when {
                // Only [Events]' Format counts (it has a Start field; [V4 Styles]' Format does not)
                line.startsWith("Format:", ignoreCase = true) && line.contains("Start", ignoreCase = true) -> {
                    val fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                    fieldCount = fields.size
                    startIdx = fields.indexOf("start").takeIf { it >= 0 } ?: 1
                    endIdx = fields.indexOf("end").takeIf { it >= 0 } ?: 2
                    textIdx = fields.indexOf("text").takeIf { it >= 0 } ?: (fieldCount - 1)
                }
                line.startsWith("Dialogue:", ignoreCase = true) -> {
                    val parts = line.substringAfter(':').split(",", limit = fieldCount)
                    if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
                    val start = assMs(parts[startIdx]) ?: continue
                    val end = assMs(parts[endIdx]) ?: continue
                    val rawText = parts[textIdx]
                    if (assDrawing.containsMatchIn(rawText)) continue
                    val text = rawText
                        .replace(assTags, "") // strip {\pos…}{\fad…} etc. effect tags, keep plain text only
                        .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                        .trim()
                    if (text.isEmpty()) continue
                    cues.add(SubCue(start, end, text))
                }
            }
        }
        return cues.sortedBy { it.start }
    }

    private fun assMs(t: String): Int? {
        val g = assTime.find(t.trim())?.groupValues ?: return null
        return g[1].toInt() * 3600_000 + g[2].toInt() * 60_000 + g[3].toInt() * 1000 +
            g[4].padEnd(3, '0').toInt()
    }
}
