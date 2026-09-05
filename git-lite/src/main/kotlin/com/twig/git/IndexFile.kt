package com.twig.git

/** Key information for one entry in the index. */
class IndexEntry(val mtimeSec: Long, val size: Long, val sha: String)

/**
 * .git/index (DIRC) read-only parser, supports v2/v3/v4.
 * Only extracts what status needs: path, mtime seconds, size, blob SHA.
 * Conflicting entries (stage>0) are deduplicated by path and any one is kept
 * (the upper layer will display them as modified).
 */
object IndexFile {

    /**
     * Parses the index. **A corrupted/truncated file returns only the portion already
     * parsed, without throwing** — everything below is bare index access
     * (`b[start + 40]`, `while (b[e] != 0) e++`), and `.git/index` may very well be read
     * in a half-written state (another process writing it, a network repo transfer
     * interrupted). The upstream caller is `GitRepo.status()`, which doesn't catch,
     * so an out-of-bounds exception would crash the whole app; returning as much as
     * we managed to parse is more appropriate.
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
            val mtime = u32(b, start + 8) // mtime seconds (ctime occupies the first 8 bytes)
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
                // the whole entry is 8-byte aligned (at least 1 NUL)
                val entryLen = p - start
                p = start + ((entryLen + 8) / 8) * 8
            } else {
                // v4: prefix-compressed with the previous path: varint (same big-endian +1 encoding as ofs-delta) for tail-strip length + NUL-terminated suffix
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
