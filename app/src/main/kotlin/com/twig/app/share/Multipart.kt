package com.twig.app.share

import java.io.InputStream
import java.io.OutputStream

/** 把流读空丢掉(不要的 multipart 部分)。 */
internal fun InputStream.drain() {
    val buf = ByteArray(8192)
    while (read(buf) >= 0) { /* 丢弃 */ }
}

/** 整流搬到 [out];不叫 copyTo 免得和 stdlib 那个同名扩展打架。 */
internal fun InputStream.pump(out: OutputStream) {
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
    }
}

/**
 * 流式 `multipart/form-data` 解析。
 *
 * **不落临时文件**:每个 part 的正文以 [InputStream] 交出去,上传时直接串到
 * `FileSystem.openOutput()` 上——传 2GB 视频不需要设备上先腾出 2GB。
 *
 * 扫边界用带前瞻的块搜索而不是逐字节 `read()`:后者在 BufferedInputStream 上是
 * 每字节一次同步方法调用,大文件上传时能把吞吐拖到远低于 WiFi 的水平。
 */
class Multipart(private val src: InputStream, boundary: String) {

    /** part 之间的分隔符;第一个 part 前面那个没有前导 CRLF,单独处理。 */
    private val delim = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)

    private val buf = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var eof = false

    fun forEachPart(onPart: (name: String?, filename: String?, body: InputStream) -> Unit) {
        if (!skipPreamble()) return
        while (true) {
            // 分隔符之后:"--" = 结束,CRLF = 还有下一个 part
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
            body.finish() // 处理方没读完也要推进到边界,否则下一个 part 会错位
        }
    }

    /** 跳到第一个分隔符之后。 */
    private fun skipPreamble(): Boolean {
        // 首个分隔符没有前导 CRLF,先按 "--boundary" 找;找不到就按通用分隔符找
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

    /** 缓冲区里至少凑够 [n] 字节;流已尽且不够返回 false。 */
    private fun ensure(n: Int): Boolean {
        while (end - start < n) {
            if (eof) return false
            compact()
            val room = buf.size - end
            if (room <= 0) return true // 缓冲区满了(n 不会大于缓冲区,不可能到这)
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
     * [at] 处是不是一个**真**分隔符。
     *
     * 光匹配 `\r\n--boundary` 不够:正文里恰好出现这串前缀(比如上传的正是一份
     * multipart 报文、或者文件里就带着这段字节)会被当成边界,文件从那里被腰斩,
     * 而且断口两侧都是合法数据——事后根本查不出来。RFC 2046 规定分隔符后面只能跟
     * `--`(整个表单结束)或 CRLF(下一个 part),多校验这两个字节就排除了误判。
     *
     * 调用方须先 `ensure(delim.size + 2)`,这里不负责把数据读进来。
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
     * 一路推进到下一个分隔符,途中的字节写给 [sink](null = 丢弃)。
     * 返回 false = 没找到分隔符就到流末尾了(正文被截断)。
     */
    private fun scanTo(sink: OutputStream?): Boolean {
        while (true) {
            if (!ensure(delim.size + 2)) {
                // 剩下的全是正文
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
            // 前面这段确定不含分隔符;末尾要留够"分隔符 + 后随两字节"减一,等下一轮拼
            val keep = delim.size + 1
            val flushEnd = end - keep
            if (sink != null && flushEnd > start) sink.write(buf, start, flushEnd - start)
            start = maxOf(start, flushEnd)
            compact()
            if (end - start >= buf.size) return false // 理论上到不了
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

    /** 一个 part 的正文流:读到分隔符就报末尾。 */
    private inner class PartStream : InputStream() {
        private var done = false
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            // 缓冲区里"确定不属于分隔符"的那一段可以直接吐出去
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
            // ensure() 保证了 end-start >= delim.size+2,所以 safe 至少是 1
            val safe = end - start - (delim.size + 1)
            val n = minOf(len, safe)
            System.arraycopy(buf, start, b, off, n)
            start += n
            return n
        }

        /** 处理方提前不读了:把剩下的推到分隔符,好让下一个 part 对齐。 */
        fun finish() {
            if (done) return
            scanTo(null)
            done = true
        }
    }
}
