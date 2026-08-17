package com.twig.app.ui

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** 一条字幕:起止时间(ms)与文本。 */
data class SubCue(val start: Int, val end: Int, val text: String)

/**
 * 极简字幕解析器:SRT / ASS / SSA。
 * 自己解析(而非交给 MediaPlayer)的好处:任何来源(SMB 等)直接 openInput 读字节即可,
 * 且可处理国内常见的 GBK 编码字幕。
 */
object SubtitleParser {

    /** 支持的外挂字幕扩展名(小写)。 */
    val EXTENSIONS = setOf("srt", "ass", "ssa")

    fun parse(bytes: ByteArray): List<SubCue> {
        val text = decode(bytes)
        return if (text.contains("Dialogue:")) parseAss(text) else parseSrt(text)
    }

    /** BOM 优先;否则严格试 UTF-8,失败回退 GBK。 */
    private fun decode(b: ByteArray): String = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            String(b, 3, b.size - 3, Charsets.UTF_8)
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: Exception) {
            String(b, charset("GBK"))
        }
    }

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
                .replace(Regex("<[^>]{1,16}>"), "") // 去 <i>/<b>/<font …> 等标签
                .trim()
            if (text.isEmpty()) continue
            cues.add(SubCue(ms(g[1], g[2], g[3], g[4]), ms(g[5], g[6], g[7], g[8]), text))
        }
        return cues.sortedBy { it.start }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Int =
        h.toInt() * 3600_000 + m.toInt() * 60_000 + s.toInt() * 1000 + frac.padEnd(3, '0').toInt()

    // ---- ASS / SSA ----
    // [Events] 里的 Format: 行决定字段顺序(V4+/V4/变体各不同),按它定位 Start/End/Text。

    private val assTime = Regex("""(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
    private val assDrawing = Regex("""\\p[1-9]""") // {\p1} 矢量绘图,不是文字
    private val assTags = Regex("""\{[^}]*\}""")

    private fun parseAss(s: String): List<SubCue> {
        val cues = ArrayList<SubCue>()
        // 默认 V4+ 布局:Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fieldCount = 10
        for (raw in s.lineSequence()) {
            val line = raw.trim()
            when {
                // 只认 [Events] 的 Format(含 Start 字段;[V4 Styles] 的 Format 没有)
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
                        .replace(assTags, "") // 去 {\pos…}{\fad…} 等特效标签,只留纯文本
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
