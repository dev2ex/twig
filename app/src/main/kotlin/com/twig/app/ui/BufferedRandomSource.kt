package com.twig.app.ui

import com.twig.core.RandomSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Wraps [RandomSource] with a "block LRU cache + background prefetch".
 *
 * Media frameworks issue many small reads on the proxy fd, and mp4's audio and video
 * tracks live at different positions in the file — a single sliding buffer would refill
 * back and forth between the two (thrashing), starving the video. This implementation
 * caches multiple 1 MB blocks (audio and video blocks each stay resident) and prefetches
 * subsequent blocks in the background, so large sequential reads feed high-bitrate video.
 *
 * Concurrency contract: the main reader and prefetch threads may ask for blocks at the
 * same time. Only one thread is allowed to actually download a given block; the rest wait
 * for it to complete (otherwise the same MB gets downloaded twice, SMB's single-threaded
 * I/O has to queue, and WebDAV breaks the sequential stream — at high bitrates bandwidth
 * is entirely wasted on duplicate reads).
 */
class BufferedRandomSource(
    private val src: RandomSource,
    private val block: Int = 1 shl 20,   // 1MB per block
    private val maxBlocks: Int = 24,     // about 24MB cache
    private val ahead: Int = 6,          // prefetch depth (in blocks): about 1.5s of headroom at 32Mbps
) : RandomSource {

    private val total = src.length()
    private val cache = LinkedHashMap<Long, ByteArray>(maxBlocks + 2, 0.75f, true) // accessOrder=LRU
    private val inflight = HashMap<Long, CountDownLatch>() // blocks currently being downloaded
    private val queued = HashSet<Long>()                   // blocks already queued for prefetch
    private val prefetch = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-prefetch").apply { isDaemon = true } }
    @Volatile private var gen = 0    // generation: incremented on a seek, invalidating prefetches still queued
    private var lastBi = -1L         // block index of the last read (cache-lock-protected)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val bi = position / block
        synchronized(cache) {
            if (lastBi >= 0 && bi !in (lastBi - 1)..(lastBi + ahead)) gen++
            lastBi = bi
        }
        val data = blockOf(bi)
        if (data.isEmpty()) return -1
        val within = (position - bi * block).toInt()
        val avail = data.size - within
        if (avail <= 0) return -1
        val n = minOf(length, avail)
        System.arraycopy(data, within, buffer, offset, n)
        for (k in 1..ahead) prefetch(bi + k)
        return n
    }

    /** Get a block: return immediately on cache hit; if someone is already downloading it, wait; otherwise download it ourselves. */
    private fun blockOf(bi: Long): ByteArray {
        while (true) {
            var waitFor: CountDownLatch? = null
            synchronized(cache) {
                cache[bi]?.let { return it }
                val l = inflight[bi]
                if (l != null) waitFor = l else inflight[bi] = CountDownLatch(1)
            }
            if (waitFor == null) return fetch(bi)
            waitFor!!.await()
            // On wake-up, recheck: normally we hit the cache; if the downloader failed,
            // we will pick up the retry here.
        }
    }

    /** Actually download a block, put it into the cache and wake the waiters (also wakes on failure, so the waiters can pick up the retry). */
    private fun fetch(bi: Long): ByteArray {
        try {
            val off = bi * block
            val buf = ByteArray(block)
            var read = 0
            while (read < block) {
                val k = src.readAt(off + read, buf, read, block - read) // positional read (does not hold the cache lock)
                if (k <= 0) break
                read += k
            }
            val data = if (read == block) buf else buf.copyOf(read)
            synchronized(cache) {
                cache[bi] = data
                while (cache.size > maxBlocks) cache.remove(cache.keys.iterator().next())
            }
            return data
        } finally {
            synchronized(cache) { inflight.remove(bi)?.countDown() }
        }
    }

    private fun prefetch(bi: Long) {
        if (total in 1..(bi * block)) return
        synchronized(cache) {
            if (cache.containsKey(bi) || inflight.containsKey(bi) || !queued.add(bi)) return
        }
        val g = gen
        prefetch.execute {
            try {
                if (g == gen) runCatching { blockOf(bi) }
            } finally {
                synchronized(cache) { queued.remove(bi) }
            }
        }
    }

    override fun length(): Long = total

    override fun close() {
        prefetch.shutdownNow()
        runCatching { src.close() }
    }
}
