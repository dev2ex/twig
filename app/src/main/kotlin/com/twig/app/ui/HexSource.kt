package com.twig.app.ui

import android.util.LruCache
import com.twig.core.FileSystem
import com.twig.core.RandomSource
import com.twig.core.XFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * 十六进制查看器的字节来源:按 [CHUNK] 分块经 [FileSystem.openRandom] 定位读,块进 LRU
 * 缓存。**不整包读进内存**——滚到哪读哪,所以多大的文件都能打开(也就不再有从前那个
 * 64KB 上限)。
 *
 * [peek] 只看缓存、绝不阻塞(给主线程的绑定用),缺块时返回 null 由调用方去排一次
 * [load];[load] 是 suspend 且整段串在 [mutex] 里——`RandomSource` 普遍非线程安全
 * (SMB 的 smb2_context 尤其),多行同时缺块时并发进去会踩坏底层状态。
 *
 * 拿不到文件长度的来源(size<=0 且 resolve 也问不出)退回"整读进内存"兜底,封顶
 * [MAX_MEM],截断时置 [truncated] 让 UI 提示。
 */
class HexSource(private val fs: FileSystem, private val file: XFile) : Closeable {

    var size = 0L
        private set

    /** 仅内存兜底模式可能为 true:文件比 [MAX_MEM] 大,只读到了前面一段。 */
    var truncated = false
        private set

    private var random: RandomSource? = null
    private var mem: ByteArray? = null
    private val cache = LruCache<Int, ByteArray>(MAX_CHUNKS)
    private val mutex = Mutex()

    /** 阻塞 IO,放后台调用。[declaredSize] 是调用方已知的长度(<=0 表示不知道)。 */
    fun open(declaredSize: Long) {
        var s = declaredSize
        // XFile 是从 Intent 里拼出来的,size 常常丢了;能 resolve 就问一次真长度
        if (s <= 0) s = runCatching { fs.resolve(file.path).size }.getOrDefault(0L)
        if (s > 0) {
            random = fs.openRandom(file)
            size = s
            return
        }
        val buf = ByteArray(MAX_MEM)
        var read = 0
        fs.openInput(file).use { input ->
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            truncated = input.read() >= 0
        }
        mem = buf.copyOf(read)
        size = read.toLong()
    }

    /**
     * 从缓存里取 [off] 起的 [len] 字节。返回 null = 还没读到,调用方该去 [load];
     * 返回的数组可能短于 [len](读到文件尾)。
     */
    fun peek(off: Long, len: Int): ByteArray? {
        if (len <= 0 || off < 0) return ByteArray(0)
        mem?.let { m ->
            if (off >= m.size) return ByteArray(0)
            val n = minOf(len.toLong(), m.size - off).toInt()
            return m.copyOfRange(off.toInt(), off.toInt() + n)
        }
        val out = ByteArray(len)
        var got = 0
        while (got < len) {
            val p = off + got
            val ci = (p / CHUNK).toInt()
            val chunk = cache.get(ci) ?: return null
            val inChunk = (p - ci.toLong() * CHUNK).toInt()
            if (inChunk >= chunk.size) break // 该块就到这儿(文件尾)
            val n = minOf(len - got, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, out, got, n)
            got += n
        }
        return if (got == len) out else out.copyOf(got)
    }

    /** 读入第 [ci] 块;已在缓存里则立刻返回。 */
    suspend fun load(ci: Int) {
        if (mem != null || cache.get(ci) != null) return
        val start = ci.toLong() * CHUNK
        if (start >= size) return
        val want = minOf(CHUNK.toLong(), size - start).toInt()
        mutex.withLock {
            if (cache.get(ci) != null) return
            val rs = random ?: return
            val buf = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = rs.readAt(start + got, buf, got, want - got)
                if (n <= 0) break
                got += n
            }
            cache.put(ci, if (got == want) buf else buf.copyOf(got))
        }
    }

    /**
     * 顺序扫全文找 [pattern],返回命中处的绝对偏移(最多 [limit] 条)。
     *
     * 走 [FileSystem.openInput] 顺序读而不是定位读:FTP/SFTP/压缩包这类"定位 = 重开+跳过"
     * 的来源,顺序读才是唯一划算的扫法。缓冲区之间保留 `pattern.size - 1` 字节的重叠,
     * 跨块的命中才不会漏;重叠区不会重复命中——上一轮能完整放下的匹配起点最大是
     * `n - pattern.size`,正好落在保留区之前。
     *
     * [fold] = true 时按 ASCII 大小写不敏感比较(文本搜索用)。[active] 返回 false 即中止,
     * 用来接住协程取消(大文件一次扫描可能要几秒)。
     */
    fun search(pattern: ByteArray, fold: Boolean, limit: Int, active: () -> Boolean): List<Long> {
        val out = ArrayList<Long>()
        if (pattern.isEmpty()) return out
        val pat = if (fold) ByteArray(pattern.size) { lower(pattern[it]) } else pattern
        mem?.let { m ->
            var i = 0
            while (i <= m.size - pat.size && out.size < limit) {
                if (matchAt(m, i, pat, fold)) out.add(i.toLong())
                i++
            }
            return out
        }
        val buf = ByteArray(SCAN_BUF)
        var base = 0L
        var keep = 0
        fs.openInput(file).use { input ->
            while (out.size < limit && active()) {
                var n = keep
                while (n < buf.size) {
                    val k = input.read(buf, n, buf.size - n)
                    if (k < 0) break
                    n += k
                }
                if (n < pat.size) break
                var i = 0
                val last = n - pat.size
                while (i <= last) {
                    if (matchAt(buf, i, pat, fold)) {
                        out.add(base + i)
                        if (out.size >= limit) break
                    }
                    i++
                }
                if (n < buf.size) break // 读到文件尾了
                keep = pat.size - 1
                System.arraycopy(buf, n - keep, buf, 0, keep)
                base += n - keep
            }
        }
        return out
    }

    override fun close() {
        runCatching { random?.close() }
        random = null
        mem = null
        cache.evictAll()
    }

    companion object {
        const val CHUNK = 64 * 1024
        private const val SCAN_BUF = 256 * 1024

        private fun lower(b: Byte): Byte {
            val v = b.toInt() and 0xFF
            return if (v in 0x41..0x5A) (v + 0x20).toByte() else b
        }

        private fun matchAt(data: ByteArray, at: Int, pat: ByteArray, fold: Boolean): Boolean {
            for (j in pat.indices) {
                val d = if (fold) lower(data[at + j]) else data[at + j]
                if (d != pat[j]) return false
            }
            return true
        }

        private const val MAX_CHUNKS = 24 // ≈1.5MB 常驻,足够覆盖来回滚动
        const val MAX_MEM = 8 * 1024 * 1024 // 问不出长度时的整读上限
    }
}
