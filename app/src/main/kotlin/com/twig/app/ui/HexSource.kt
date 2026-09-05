package com.twig.app.ui

import android.util.LruCache
import com.twig.core.FileSystem
import com.twig.core.RandomSource
import com.twig.core.XFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * Byte source for the hex viewer: reads in [CHUNK]-sized chunks via [FileSystem.openRandom] seeking, with
 * the chunks held in an LRU cache. **Does not load the whole file into memory** — it reads as you scroll,
 * so files of any size can be opened (the old 64KB cap is gone).
 *
 * [peek] only consults the cache and never blocks (used by main-thread bindings); when a chunk is missing
 * it returns null so the caller can schedule a [load]; [load] is suspend and runs entirely under [mutex] —
 * `RandomSource` is generally not thread-safe (SMB's smb2_context especially), so concurrent calls from
 * multiple rows missing chunks would corrupt the underlying state.
 *
 * Sources whose length can't be obtained (size<=0 and resolve returns nothing either) fall back to a
 * "read whole file into memory" emergency path, capped at [MAX_MEM]; if truncation occurs, set
 * [truncated] so the UI can warn.
 */
class HexSource(private val fs: FileSystem, private val file: XFile) : Closeable {

    var size = 0L
        private set

    /** Only true in the in-memory fallback mode: the file is bigger than [MAX_MEM] and only the first portion was read. */
    var truncated = false
        private set

    private var random: RandomSource? = null
    private var mem: ByteArray? = null
    private val cache = LruCache<Int, ByteArray>(MAX_CHUNKS)
    private val mutex = Mutex()

    /** Blocking IO; call from a background thread. [declaredSize] is the length known by the caller (<=0 means unknown). */
    fun open(declaredSize: Long) {
        var s = declaredSize
        // XFile is assembled from an Intent and often loses size; if resolve works, ask for the real length once
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
     * Pull [len] bytes starting at [off] from the cache. Returning null means not yet read; the caller should
     * schedule a [load]; the returned array may be shorter than [len] (reached the file end).
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
            if (inChunk >= chunk.size) break // this chunk stops here (file end)
            val n = minOf(len - got, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, out, got, n)
            got += n
        }
        return if (got == len) out else out.copyOf(got)
    }

    /** Read in chunk [ci]; returns immediately if already cached. */
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
     * Sequentially scan the entire file for [pattern], returning the absolute offsets of the hits (up to [limit]).
     *
     * Uses sequential reads via [FileSystem.openInput] rather than seeking: for sources where "seeking = reopen +
     * skip" (FTP / SFTP / archives), sequential reading is the only affordable way to scan. Keep `pattern.size - 1`
     * bytes of overlap between buffers so cross-chunk hits don't get missed; the overlap region never produces a
     * duplicate hit — the latest starting position that could fit fully in the previous round is `n - pattern.size`,
     * which is just before the overlap region.
     *
     * When [fold] = true, comparisons are ASCII case-insensitive (for text search). When [active] returns false,
     * the scan stops — used to catch coroutine cancellation (a scan of a large file may take several seconds).
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
                if (n < buf.size) break // reached the end of the file
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

        private const val MAX_CHUNKS = 24 // ≈1.5MB resident, enough to cover back-and-forth scrolling
        const val MAX_MEM = 8 * 1024 * 1024 // upper limit for "read whole file" when length can't be obtained
    }
}
