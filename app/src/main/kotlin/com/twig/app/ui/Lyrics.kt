package com.twig.app.ui

import com.twig.app.OpenFiles
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.nio.charset.Charset

/**
 * 一条歌词;[timeMs] < 0 表示无时间戳(纯文本行)。
 * [texts] 可含多行:同一时间戳的双语歌词(原文+译文)合并成一条、以及文本内的换行符拆分后,
 * 各占一行堆叠显示。
 */
data class LyricLine(val timeMs: Long, val texts: List<String>)

/** 解析后的歌词。[synced] 表示带时间戳(可同步高亮滚动)。 */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 歌词来源(优先级):同目录同名 `.lrc` → 内嵌 ID3v2 USLT(mp3)/ FLAC vorbis comment LYRICS。
 * 全部手写解析,任何一步失败静默返回 null(不影响播放)。阻塞 IO,须在工作线程调用。
 */
object LyricsLoader {

    fun load(file: XFile): Lyrics? {
        sidecar(file)?.let { return it }
        return runCatching { embedded(file) }.getOrNull()
    }

    // ---- 同目录 .lrc ----

    private fun sidecar(file: XFile): Lyrics? = runCatching {
        val fs = FsRegistry.of(file)
        val base = file.name.substringBeforeLast('.').lowercase()
        val lrc = fs.list(XFile(file.scheme, file.parentPath, isDir = true))
            .firstOrNull { !it.isDir && it.extension == "lrc" && it.name.substringBeforeLast('.').lowercase() == base }
            ?: return null
        if (lrc.size > 512L * 1024) return null
        val text = fs.openInput(lrc).use { OpenFiles.readAllBytes(it, 256 * 1024) }.let { decodeText(it) }
        parseLrc(text)
    }.getOrNull()

    // ---- LRC ----

    private val TIME = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(raw: String): Lyrics? {
        var offset = 0L
        val timedRaw = ArrayList<Pair<Long, String>>()
        val plain = ArrayList<LyricLine>()
        for (line in raw.split('\n')) {
            val l = line.trimEnd('\r')
            Regex("""\[offset:\s*([+-]?\d+)]""").find(l)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L }
            val matches = TIME.findAll(l).toList()
            val text = l.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: 0).trim()
            if (matches.isEmpty()) {
                // 忽略 [ar:] [ti:] 等 id 标签行;其余当纯文本
                if (!l.startsWith("[") && l.isNotBlank()) plain += LyricLine(-1, splitText(l.trim()))
                continue
            }
            for (m in matches) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                if (text.isNotEmpty()) timedRaw += ((mm * 60 + ss) * 1000 + ms + offset) to text
            }
        }
        if (timedRaw.isNotEmpty()) {
            // 同一时间戳的多行(双语原文+译文)合并成一条,内部换行符再拆行,各占一行
            val byTime = LinkedHashMap<Long, MutableList<String>>()
            for ((t, txt) in timedRaw.sortedBy { it.first }) {
                byTime.getOrPut(t) { ArrayList() }.addAll(splitText(txt))
            }
            val lines = byTime.map { LyricLine(it.key, it.value) }
            return Lyrics(lines, synced = true)
        }
        return if (plain.isNotEmpty()) Lyrics(plain, synced = false) else null
    }

    /**
     * 把一段文本拆成多行,去掉空行。分隔符:换行符(含字面量 "\n")、Unicode 行/段分隔符,
     * 以及 U+2009 窄空格(thin space)——不少双语歌词把原文和译文用它拼在同一行,
     * 视觉上像一行,这里拆成上下两行显示。
     */
    private fun splitText(s: String): List<String> =
        s.replace("\\n", "\n").split('\n', '\u2009', '\u2028', '\u2029')
            .map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(s) }

    // ---- 内嵌 ----

    private fun embedded(file: XFile): Lyrics? {
        val fs = FsRegistry.of(file)
        val head = fs.openInput(file).use { ins ->
            val cap = 1 shl 20 // 1MB 足够覆盖 ID3/FLAC 头部
            val buf = ByteArray(cap)
            var off = 0
            while (off < cap) { val n = ins.read(buf, off, cap - off); if (n < 0) break; off += n }
            if (off == cap) buf else buf.copyOf(off)
        }
        if (head.size >= 4 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            id3Uslt(head)?.let { return parseTextBlock(it) }
        }
        if (head.size >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) {
            flacLyrics(head)?.let { return parseTextBlock(it) }
        }
        return null
    }

    private fun parseTextBlock(text: String): Lyrics? {
        if (text.isBlank()) return null
        parseLrc(text)?.takeIf { it.synced }?.let { return it }
        val lines = text.replace("\\n", "\n").split('\n').map { it.trimEnd('\r').trim() }.filter { it.isNotEmpty() }
        return if (lines.isEmpty()) null else Lyrics(lines.map { LyricLine(-1, listOf(it)) }, synced = false)
    }

    /** ID3v2 里的 USLT(v2.3/2.4)/ ULT(v2.2)非同步歌词帧。 */
    private fun id3Uslt(b: ByteArray): String? {
        val major = b[3].toInt() and 0xFF
        val size = syncsafe(b, 6)
        var pos = 10
        val end = minOf(10 + size, b.size)
        val idLen = if (major == 2) 3 else 4
        while (pos + idLen + (if (major == 2) 3 else 6) <= end) {
            val id = String(b, pos, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val frameSize: Int
            val headerLen: Int
            if (major == 2) {
                frameSize = ((b[pos + 3].toInt() and 0xFF) shl 16) or ((b[pos + 4].toInt() and 0xFF) shl 8) or (b[pos + 5].toInt() and 0xFF)
                headerLen = 6
            } else {
                frameSize = if (major == 4) syncsafe(b, pos + 4) else be32(b, pos + 4)
                headerLen = 10
            }
            val body = pos + headerLen
            if (frameSize <= 0 || body + frameSize > b.size) break
            if (id == "USLT" || id == "ULT") {
                return decodeUsltBody(b, body, frameSize)
            }
            pos = body + frameSize
        }
        return null
    }

    /** USLT body: encoding(1) + language(3) + descriptor(以对应编码的 null 结尾) + lyrics。 */
    private fun decodeUsltBody(b: ByteArray, start: Int, len: Int): String? {
        if (len < 4) return null
        val enc = b[start].toInt() and 0xFF
        val charset = when (enc) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        var p = start + 4 // 跳过 encoding + language
        val end = start + len
        // 跳过 descriptor 直到终止符(UTF-16 用双字节)
        val wide = enc == 1 || enc == 2
        while (p < end) {
            if (wide) { if (p + 1 < end && b[p].toInt() == 0 && b[p + 1].toInt() == 0) { p += 2; break }; p += 2 }
            else { if (b[p].toInt() == 0) { p++; break }; p++ }
        }
        if (p >= end) return null
        return String(b, p, end - p, charset).trim()
    }

    /** FLAC VORBIS_COMMENT(块类型 4)里的 LYRICS / UNSYNCEDLYRICS 字段。 */
    private fun flacLyrics(b: ByteArray): String? {
        var pos = 4
        while (pos + 4 <= b.size) {
            val flag = b[pos].toInt() and 0xFF
            val last = flag and 0x80 != 0
            val type = flag and 0x7F
            val len = ((b[pos + 1].toInt() and 0xFF) shl 16) or ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val body = pos + 4
            if (body + len > b.size) break
            if (type == 4) return vorbisComment(b, body, len)
            if (last) break
            pos = body + len
        }
        return null
    }

    private fun vorbisComment(b: ByteArray, start: Int, len: Int): String? {
        var p = start
        val end = start + len
        fun le32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4; return v
        }
        if (p + 4 > end) return null
        val vlen = le32(); p += vlen // vendor string
        if (p + 4 > end) return null
        val count = le32()
        repeat(count) {
            if (p + 4 > end) return null
            val clen = le32()
            if (p + clen > end || clen < 0) return null
            val comment = String(b, p, clen, Charsets.UTF_8)
            p += clen
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS") return comment.substring(eq + 1)
            }
        }
        return null
    }

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** 猜编码:带 BOM 用之,否则试 UTF-8,失败退 GBK/系统默认。 */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('�')) return utf8
        return runCatching { String(bytes, Charset.forName("GBK")) }.getOrDefault(utf8)
    }
}
