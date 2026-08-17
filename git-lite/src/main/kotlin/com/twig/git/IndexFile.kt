package com.twig.git

/** index 中一个条目的关键信息。 */
class IndexEntry(val mtimeSec: Long, val size: Long, val sha: String)

/**
 * .git/index(DIRC)只读解析,支持 v2/v3/v4。
 * 只取 status 需要的字段:路径、mtime 秒、大小、blob SHA。
 * 冲突条目(stage>0)按路径去重保留任意一份(上层会呈现为已修改)。
 */
object IndexFile {

    /**
     * 解析 index。**损坏/截断的文件只返回已经解出来的部分,不抛异常**——
     * 下面全是裸下标访问(`b[start + 40]`、`while (b[e] != 0) e++`),而 `.git/index`
     * 完全可能读到一半的状态(另一个进程正在写、网络仓库传输中断)。这条链路上游是
     * `GitRepo.status()`,没人 catch,越界异常会直接把应用带崩;能解多少算多少更合适。
     */
    fun read(bytes: ByteArray?): Map<String, IndexEntry> {
        val b = bytes ?: return emptyMap()
        if (b.size < 12 || String(b, 0, 4, Charsets.US_ASCII) != "DIRC") return emptyMap()
        val version = u32(b, 4).toInt()
        if (version !in 2..4) return emptyMap()
        val count = u32(b, 8).toInt()

        val out = LinkedHashMap<String, IndexEntry>()
        runCatching { parseEntries(b, version, count, out) }
        return out
    }

    private fun parseEntries(
        b: ByteArray,
        version: Int,
        count: Int,
        out: MutableMap<String, IndexEntry>,
    ) {
        var pos = 12
        var prevName = ""
        repeat(count) {
            val start = pos
            val mtime = u32(b, start + 8) // mtime 秒(ctime 占前 8 字节)
            val size = u32(b, start + 36)
            val sha = ObjectStore.bytesToHex(b.copyOfRange(start + 40, start + 60))
            val flags = ((b[start + 60].toInt() and 0xff) shl 8) or (b[start + 61].toInt() and 0xff)
            var p = start + 62
            if (version >= 3 && flags and 0x4000 != 0) p += 2 // extended flags

            val name: String
            if (version < 4) {
                val nameLen = flags and 0xfff
                val end = if (nameLen < 0xfff) p + nameLen else run {
                    var e = p; while (b[e].toInt() != 0) e++; e
                }
                name = String(b, p, end - p, Charsets.UTF_8)
                p = end
                // 整个条目按 8 字节对齐(至少 1 个 NUL)
                val entryLen = p - start
                p = start + ((entryLen + 8) / 8) * 8
            } else {
                // v4:与上个路径前缀压缩:varint(ofs-delta 同款大端 +1 编码)去尾长度 + NUL 结尾后缀
                var x = b[p++].toInt() and 0xff
                var strip = (x and 0x7f).toLong()
                while (x and 0x80 != 0) {
                    x = b[p++].toInt() and 0xff
                    strip = ((strip + 1) shl 7) or (x and 0x7f).toLong()
                }
                var e = p; while (b[e].toInt() != 0) e++
                name = prevName.substring(0, prevName.length - strip.toInt()) + String(b, p, e - p, Charsets.UTF_8)
                p = e + 1
            }
            prevName = name
            out[name] = IndexEntry(mtime, size, sha)
            pos = p
        }
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)
}
