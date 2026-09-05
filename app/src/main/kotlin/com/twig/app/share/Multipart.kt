package com.twig.app.share

import java.io.InputStream
import java.io.OutputStream

/** Drain a stream to nowhere (a multipart part we don't want). */
internal fun InputStream.drain() {
    val buf = ByteArray(8192)
    while (read(buf) >= 0) { /* discard */ }
}

/** Move the whole stream into [out]; named differently from `copyTo` to avoid clashing with the stdlib extension of the same name. */
internal fun InputStream.pump(out: OutputStream) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
}

/**
 * Streaming `multipart/form-data` parser.
 *
 * **No temporary file**: each part's body is handed out as an [InputStream],
 * and the upload is piped straight to `FileSystem.openOutput()` — uploading
 * a 2 GB video does not require first clearing 2 GB on the device.
 *
 * The boundary scan uses block-search with a lookahead instead of byte-by-byte
 * `read()`: the latter makes one synchronised method call per byte on a
 * BufferedInputStream, which can drag large-file upload throughput far below
 * the WiFi line rate.
 */
class Multipart(private val src: InputStream, boundary: String) {

    /** Separator between parts; the one before the first part has no leading CRLF and is handled separately. */
    private val delim = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)

    private val buf = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var eof = false

    fun forEachPart(onPart: (name: String?, filename: String?, body: InputStream) -> Unit) {
        if (!skipPreamble()) return
        while (true) {
            // After the separator: "--" = end, CRLF = another part follows
            if (!ensure(2)) return
            if (buf[start] == '-'.code.toByte() && buf[start + 1] == '-'.code.toByte()) return
            skipLine()

            var name: String? = null
            var filename: String? = null
            while (true) {
                val line = readHeaderLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("content-disposition", true)) {
                    name = paramOf(line, "name")
                    filename = paramOf(line, "filename")
                }
            }
            val body = PartStream()
            onPart(name, filename, body)
            body.finish() // even if the handler did not read it all, push forward to the boundary, otherwise the next part is misaligned
        }
    }

    /** Skip up to and past the first separator. */
    private fun skipPreamble(): Boolean {
        // The first separator has no leading CRLF; look for "--boundary" first; if that fails, fall back to the generic separator
        val first = delim.copyOfRange(2, delim.size)
        if (!ensure(first.size)) return false
        if (regionMatches(start, first)) { start += first.size; return true }
        return scanTo(null)
    }

    private fun paramOf(header: String, key: String): String? {
        val i = header.indexOf("$key=", ignoreCase = true)
        if (i < 0) return null
        var v = header.substring(i + key.length + 1).trim()
        return if (v.startsWith("\"")) {
            v = v.substring(1)
            v.substring(0, v.indexOf('"').takeIf { it >= 0 } ?: v.length)
        } else {
            v.substringBefore(';').trim()
        }
    }

    private fun readHeaderLine(): String? {
        val out = java.io.ByteArrayOutputStream(128)
        while (true) {
            if (!ensure(1)) return null
            val c = buf[start].toInt() and 0xFF
            start++
            if (c == '\n'.code) {
                var s = out.toString("UTF-8")
                if (s.endsWith("\r")) s = s.dropLast(1)
                return s
            }
            if (out.size() > 8192) return null
            out.write(c)
        }
    }

    private fun skipLine() {
        while (ensure(1)) {
            val c = buf[start].toInt() and 0xFF
            start++
            if (c == '\n'.code) return
        }
    }

    /** Ensure the buffer holds at least [n] bytes; returns false if the stream ended first. */
    private fun ensure(n: Int): Boolean {
        while (end - start < n) {
            if (eof) return false
            compact()
            val room = buf.size - end
            if (room <= 0) return true // buffer is full (n cannot be larger than the buffer, so this branch is unreachable)
            val k = src.read(buf, end, room)
            if (k < 0) { eof = true; return end - start >= n }
            end += k
        }
        return true
    }

    private fun compact() {
        if (start == 0) return
        System.arraycopy(buf, start, buf, 0, end - start)
        end -= start
        start = 0
    }

    private fun regionMatches(at: Int, pat: ByteArray): Boolean {
        if (at + pat.size > end) return false
        for (i in pat.indices) if (buf[at + i] != pat[i]) return false
        return true
    }

    /**
     * Whether [at] is a **real** separator.
     *
     * Matching `\r\n--boundary` alone is not enough: if the body happens to
     * contain that exact prefix (for example, the upload itself is a
     * multipart document, or the file just happens to have those bytes),
     * the parser will treat it as a boundary and chop the file in two — and
     * both halves of the cut are perfectly valid data, so there is no way
     * to detect it afterwards. RFC 2046 says the separator must be followed
     * by either `--` (end of form) or CRLF (next part); checking those two
     * extra bytes rules out the false positive.
     *
     * The caller must have already called `ensure(delim.size + 2)`; this
     * method does not pull more data in.
     */
    private fun isDelimAt(at: Int): Boolean {
        if (!regionMatches(at, delim)) return false
        val after = at + delim.size
        if (after + 2 > end) return false
        val a = buf[after]
        val b = buf[after + 1]
        return (a == '-'.code.toByte() && b == '-'.code.toByte()) ||
            (a == '\r'.code.toByte() && b == '\n'.code.toByte())
    }

    /**
     * Walk forward to the next separator, writing bytes along the way to
     * [sink] (null = discard). Return false = the stream ended before a
     * separator was found (the body is truncated).
     */
    private fun scanTo(sink: OutputStream?): Boolean {
        while (true) {
            if (!ensure(delim.size + 2)) {
                // What's left is all body
                if (sink != null && end > start) sink.write(buf, start, end - start)
                start = end
                return false
            }
            var i = start
            val limit = end - delim.size - 2
            while (i <= limit) {
                if (buf[i] == delim[0] && isDelimAt(i)) {
                    if (sink != null && i > start) sink.write(buf, start, i - start)
                    start = i + delim.size
                    return true
                }
                i++
            }
            // The leading part of the buffer is known to contain no
            // separator; keep the last "delimiter + two trailing bytes - 1"
            // bytes around for the next round
            val keep = delim.size + 1
            val flushEnd = end - keep
            if (sink != null && flushEnd > start) sink.write(buf, start, flushEnd - start)
            start = maxOf(start, flushEnd)
            compact()
            if (end - start >= buf.size) return false // should be unreachable
            val k = src.read(buf, end, buf.size - end)
            if (k < 0) {
                eof = true
                if (sink != null && end > start) sink.write(buf, start, end - start)
                start = end
                return false
            }
            end += k
        }
    }

    /** A part's body stream: returns EOF as soon as the separator is hit. */
    private inner class PartStream : InputStream() {
        private var done = false
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            // The portion of the buffer that is "definitely not part of a
            // separator" can be emitted directly
            if (!ensure(delim.size + 2)) {
                val avail = end - start
                if (avail <= 0) { done = true; return -1 }
                val n = minOf(len, avail)
                System.arraycopy(buf, start, b, off, n)
                start += n
                return n
            }
            var i = start
            val limit = end - delim.size - 2
            while (i <= limit) {
                if (buf[i] == delim[0] && isDelimAt(i)) {
                    if (i == start) { start = i + delim.size; done = true; return -1 }
                    val n = minOf(len, i - start)
                    System.arraycopy(buf, start, b, off, n)
                    start += n
                    return n
                }
                i++
            }
            // ensure() guarantees end-start >= delim.size+2, so safe >= 1
            val safe = end - start - (delim.size + 1)
            val n = minOf(len, safe)
            System.arraycopy(buf, start, b, off, n)
            start += n
            return n
        }

        /** The handler stopped reading early: push the rest forward to the separator so the next part is aligned. */
        fun finish() {
            if (done) return
            scanTo(null)
            done = true
        }
    }
}
