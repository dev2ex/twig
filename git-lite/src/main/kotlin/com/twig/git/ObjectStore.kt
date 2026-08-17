package com.twig.git

import java.util.zip.Inflater

/** git 对象类型常量(pack 编码)。 */
object ObjType {
    const val COMMIT = 1
    const val TREE = 2
    const val BLOB = 3
    const val TAG = 4
    const val OFS_DELTA = 6
    const val REF_DELTA = 7
}

/** 一个已解出的 git 对象。 */
class RawObject(val type: Int, val data: ByteArray)

/**
 * 只读对象库:松散对象(objects/xx/…,zlib)+ packfile(idx v2 定位,
 * OFS/REF delta 还原)。[fs] 以 .git 目录为根;本地/远程通用,
 * pack 经 [PackReader] 块缓冲定位读,远程也不用整包下载。
 */
class ObjectStore(private val fs: GitFs) : java.io.Closeable {

    /** pack 定位读 + 128KB 单块缓冲(远程免得逐字节往返)。 */
    private class PackReader(private val src: GitRandom) : java.io.Closeable {
        var pos = 0L
        private val block = ByteArray(1 shl 17)
        private var bStart = -1L
        private var bLen = 0

        fun seek(p: Long) { pos = p }

        private fun ensure(): Boolean {
            if (bStart >= 0 && pos >= bStart && pos < bStart + bLen) return true
            var n = 0
            while (n < block.size) {
                val k = src.read(pos + n, block, n, block.size - n)
                if (k <= 0) break
                n += k
            }
            bStart = pos; bLen = n
            return n > 0
        }

        fun readByte(): Int {
            if (!ensure()) return -1
            val b = block[(pos - bStart).toInt()].toInt() and 0xff
            pos++
            return b
        }

        fun read(dst: ByteArray, off: Int, len: Int): Int {
            if (!ensure()) return -1
            val a = (pos - bStart).toInt()
            val n = minOf(len, bLen - a)
            System.arraycopy(block, a, dst, off, n)
            pos += n
            return n
        }

        fun readFully(dst: ByteArray) {
            var o = 0
            while (o < dst.size) {
                val n = read(dst, o, dst.size - o)
                if (n <= 0) throw IllegalStateException("pack read out of bounds")
                o += n
            }
        }

        override fun close() = src.close()
    }

    private class PackFile(val reader: PackReader, val count: Int, val shas: ByteArray, val offsets: LongArray)

    // ★ 必须 @Volatile:下面是双检锁,少了它另一个线程可能读到"非 null 但尚未构造完"
    // 的列表(经典的 broken double-checked locking)。git 视图展开与 diff 渲染是并发的。
    @Volatile private var packsLoaded: List<PackFile>? = null
    private val packs: List<PackFile>
        get() = packsLoaded ?: synchronized(this) { packsLoaded ?: loadPacks().also { packsLoaded = it } }

    // 小对象缓存(commit/tree 反复访问)。并发读写,不能用裸 HashMap
    private val cache = java.util.concurrent.ConcurrentHashMap<String, RawObject>()

    /**
     * 读一个对象;不存在返回 null。
     *
     * 解析失败也返回 null 而不是把异常抛出去:[readLoose]/[readPacked] 底下是一堆
     * 裸下标的二进制解析([applyDelta] 完全信任 delta 里的偏移和长度),损坏或被
     * 截断的仓库(另一个进程正在写、网络仓库读了一半)会抛 AIOOBE。Twig 是"浏览任意
     * 仓库的只读查看器",遇到坏仓库该显示不出内容,而不是把整个应用带崩。
     */
    fun read(sha: String): RawObject? {
        cache[sha]?.let { return it }
        val obj = runCatching { readLoose(sha) ?: readPacked(sha) }.getOrNull() ?: return null
        if (obj.data.size <= 1 shl 16) cache[sha] = obj
        return obj
    }

    // ---- 松散对象 ----

    private fun readLoose(sha: String): RawObject? {
        val comp = fs.readBytes("objects/${sha.substring(0, 2)}/${sha.substring(2)}") ?: return null
        val raw = inflateAll(comp)
        val nul = raw.indexOf(0)
        val header = String(raw, 0, nul, Charsets.US_ASCII) // "commit 123"
        val type = when (header.substringBefore(' ')) {
            "commit" -> ObjType.COMMIT; "tree" -> ObjType.TREE
            "blob" -> ObjType.BLOB; "tag" -> ObjType.TAG
            else -> return null
        }
        return RawObject(type, raw.copyOfRange(nul + 1, raw.size))
    }

    private fun inflateAll(comp: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(comp)
        val out = java.io.ByteArrayOutputStream(comp.size * 3)
        val buf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    // ---- packfile ----

    private fun loadPacks(): List<PackFile> =
        fs.list("objects/pack").filter { it.name.endsWith(".idx") }.mapNotNull { e ->
            runCatching {
                val bytes = fs.readBytes("objects/pack/${e.name}") ?: return@mapNotNull null
                require(u32(bytes, 0) == 0xff744f63L && u32(bytes, 4) == 2L) { "only idx v2 is supported" }
                val count = u32(bytes, 8 + 255 * 4).toInt()
                val shaOff = 8 + 256 * 4
                val shas = bytes.copyOfRange(shaOff, shaOff + count * 20)
                val ofs32 = shaOff + count * 20 + count * 4
                val ofs64 = ofs32 + count * 4
                val offsets = LongArray(count) { i ->
                    val v = u32(bytes, ofs32 + i * 4)
                    if (v and 0x80000000L != 0L) u64(bytes, ofs64 + ((v and 0x7fffffffL).toInt() * 8))
                    else v
                }
                val rnd = fs.openRandom("objects/pack/${e.name.removeSuffix(".idx")}.pack")
                    ?: return@mapNotNull null
                PackFile(PackReader(rnd), count, shas, offsets)
            }.getOrNull()
        }

    private fun readPacked(sha: String): RawObject? {
        val bin = hexToBytes(sha)
        for (p in packs) {
            val i = findSha(p, bin)
            if (i >= 0) return readAt(p, p.offsets[i])
        }
        return null
    }

    private fun findSha(p: PackFile, bin: ByteArray): Int {
        var lo = 0; var hi = p.count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareSha(p.shas, mid * 20, bin)
            when {
                c == 0 -> return mid
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    private fun compareSha(arr: ByteArray, off: Int, bin: ByteArray): Int {
        for (k in 0 until 20) {
            val a = arr[off + k].toInt() and 0xff
            val b = bin[k].toInt() and 0xff
            if (a != b) return a - b
        }
        return 0
    }

    /** 读 pack 内某偏移处的对象(递归还原 delta)。 */
    private fun readAt(p: PackFile, offset: Long): RawObject? {
        synchronized(p.reader) {
            val r = p.reader
            r.seek(offset)
            var b = r.readByte()
            val type = (b shr 4) and 7
            while (b and 0x80 != 0) b = r.readByte()
            when (type) {
                ObjType.OFS_DELTA -> {
                    b = r.readByte()
                    var ofs = (b and 0x7f).toLong()
                    while (b and 0x80 != 0) {
                        b = r.readByte()
                        ofs = ((ofs + 1) shl 7) or (b and 0x7f).toLong()
                    }
                    val deltaStart = r.pos
                    val base = readAt(p, offset - ofs) ?: return null
                    return RawObject(base.type, applyDelta(base.data, inflateAt(r, deltaStart)))
                }
                ObjType.REF_DELTA -> {
                    val baseSha = ByteArray(20)
                    r.readFully(baseSha)
                    val deltaStart = r.pos
                    val base = read(bytesToHex(baseSha)) ?: return null
                    return RawObject(base.type, applyDelta(base.data, inflateAt(r, deltaStart)))
                }
                else -> return RawObject(type, inflateAt(r, r.pos))
            }
        }
    }

    private fun inflateAt(r: PackReader, start: Long): ByteArray {
        r.seek(start)
        val inf = Inflater()
        val out = java.io.ByteArrayOutputStream()
        val inBuf = ByteArray(64 * 1024)
        val outBuf = ByteArray(64 * 1024)
        while (!inf.finished()) {
            if (inf.needsInput()) {
                val n = r.read(inBuf, 0, inBuf.size)
                if (n <= 0) break
                inf.setInput(inBuf, 0, n)
            }
            val n = inf.inflate(outBuf)
            out.write(outBuf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    /** git delta 还原:copy(带偏移/长度位)与 insert 指令。 */
    private fun applyDelta(base: ByteArray, delta: ByteArray): ByteArray {
        var pos = 0
        fun varint(): Long {
            var v = 0L; var s = 0
            while (true) {
                val b = delta[pos++].toInt() and 0xff
                v = v or ((b and 0x7f).toLong() shl s)
                if (b and 0x80 == 0) return v
                s += 7
            }
        }
        varint() // 源长度(校验略)
        val outLen = varint().toInt()
        val out = ByteArray(outLen)
        var o = 0
        while (pos < delta.size) {
            val cmd = delta[pos++].toInt() and 0xff
            if (cmd and 0x80 != 0) { // copy
                var cpOff = 0L; var cpLen = 0L
                for (k in 0 until 4) if (cmd and (1 shl k) != 0) cpOff = cpOff or ((delta[pos++].toLong() and 0xff) shl (k * 8))
                for (k in 0 until 3) if (cmd and (1 shl (4 + k)) != 0) cpLen = cpLen or ((delta[pos++].toLong() and 0xff) shl (k * 8))
                if (cpLen == 0L) cpLen = 0x10000
                System.arraycopy(base, cpOff.toInt(), out, o, cpLen.toInt())
                o += cpLen.toInt()
            } else { // insert
                System.arraycopy(delta, pos, out, o, cmd)
                pos += cmd; o += cmd
            }
        }
        return out
    }

    override fun close() {
        packsLoaded?.forEach { runCatching { it.reader.close() } }
    }

    companion object {
        private fun u32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
                ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

        private fun u64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (b[off + k].toLong() and 0xff)
            return v
        }

        fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

        fun bytesToHex(b: ByteArray): String = buildString(b.size * 2) {
            for (x in b) append("%02x".format(x.toInt() and 0xff))
        }
    }
}
