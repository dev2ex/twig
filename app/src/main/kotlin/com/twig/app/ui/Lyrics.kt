package com.twig.app.ui

import com.twig.app.OpenFiles
import com.twig.app.TextCodec
import com.twig.core.FsRegistry
import com.twig.core.XFile

/**
 * One lyric line; [timeMs] < 0 means untimed (plain text line).
 * [texts] can contain multiple lines: bilingual lyrics with the same timestamp (original + translation) merged
 * into one entry, and embedded newlines inside the text split out, are stacked as one line each.
 */
data class LyricLine(val timeMs: Long, val texts: List<String>)

/** Parsed lyrics. [synced] means with timestamps (can highlight and scroll in sync). */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Lyrics source (priority): the source's own (media servers, see [com.twig.core.LyricsSource]) →
 * same-named `.lrc` in the parent directory → embedded ID3v2 USLT (mp3) / FLAC vorbis comment LYRICS.
 * All parsing is hand-written; any step's failure silently returns null (doesn't affect playback). Blocking IO,
 * must be called on a worker thread.
 */
object LyricsLoader {

    fun load(file: XFile): Lyrics? {
        // If the source has its own lyrics (media servers) use it: the other two require listing the parent
        // directory for a same-named .lrc, or pulling down the entire song's bytes to scan ID3 frames — both
        // expensive over the network
        remote(file)?.let { return it }
        sidecar(file)?.let { return it }
        return runCatching { embedded(file) }.getOrNull()
    }

    /** Lyrics provided directly by the source ([com.twig.core.LyricsSource], uniformly LRC text). */
    private fun remote(file: XFile): Lyrics? = runCatching {
        (FsRegistry.of(file) as? com.twig.core.LyricsSource)?.lyricsOf(file)?.let { parseLrc(it) }
    }.getOrNull()

    // ---- same-directory .lrc ----

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
                // ignore id-tag lines like [ar:] [ti:]; treat the rest as plain text
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
            // Multiple lines with the same timestamp (bilingual original + translation) are merged into one entry;
// embedded newlines are then split out, one per line
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
     * Split a block of text into multiple lines, dropping empty ones. Separators: newline (including the literal "\n"),
     * Unicode line / paragraph separators, and U+2009 thin space — many bilingual lyrics join original and translation
     * with it on the same line; visually it reads as one line, but here we split into top and bottom lines for display.
     */
    private fun splitText(s: String): List<String> =
        s.replace("\\n", "\n").split('\n', '\u2009', '\u2028', '\u2029')
            .map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(s) }

    // ---- embedded ----

    private fun embedded(file: XFile): Lyrics? {
        val fs = FsRegistry.of(file)
        val head = fs.openInput(file).use { ins ->
            val cap = 1 shl 20 // 1MB is enough to cover the ID3 / FLAC header
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

    /** Unsynchronised lyrics frame USLT (v2.3/2.4) / ULT (v2.2) in ID3v2. */
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

    /** USLT body: encoding (1) + language (3) + descriptor (null-terminated in the matching encoding) + lyrics. */
    private fun decodeUsltBody(b: ByteArray, start: Int, len: Int): String? {
        if (len < 4) return null
        val enc = b[start].toInt() and 0xFF
        val charset = when (enc) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        var p = start + 4 // skip encoding + language
        val end = start + len
        // skip descriptor until the terminator (UTF-16 uses two bytes)
        val wide = enc == 1 || enc == 2
        while (p < end) {
            if (wide) { if (p + 1 < end && b[p].toInt() == 0 && b[p + 1].toInt() == 0) { p += 2; break }; p += 2 }
            else { if (b[p].toInt() == 0) { p++; break }; p++ }
        }
        if (p >= end) return null
        return String(b, p, end - p, charset).trim()
    }

    /** LYRICS / UNSYNCEDLYRICS field in the FLAC VORBIS_COMMENT (block type 4). */
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

    /** BOM → strict UTF-8 → the encodings the user has ordered in settings, see [com.twig.app.TextCodec]. */
    private fun decodeText(bytes: ByteArray): String = TextCodec.decode(bytes).text
}
