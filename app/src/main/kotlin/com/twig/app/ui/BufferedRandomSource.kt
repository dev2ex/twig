package com.twig.app.ui

import com.twig.core.RandomSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 给 [RandomSource] 套一层"分块 LRU 缓存 + 后台预读"。
 *
 * 媒体框架读 proxy fd 会发大量小读,且 mp4 的音频轨/视频轨在文件里位置不同——
 * 单个滑动缓冲会在两处间反复重填(抖动)导致视频饿死。这里按 1MB 分块缓存多块
 * (音频块、视频块各自常驻),并后台预取后续块,大块顺序读喂饱高码率视频。
 *
 * 并发约定:主读与预读线程会同时要块。同一块只允许一个线程真正下载,
 * 其余等它完成(否则同一 MB 被下载两次,SMB 单线程 IO 还要排队,
 * WebDAV 则打断顺序流,高码率下带宽全浪费在重复读上)。
 */
class BufferedRandomSource(
    private val src: RandomSource,
    private val block: Int = 1 shl 20,   // 1MB 每块
    private val maxBlocks: Int = 24,     // 约 24MB 缓存
    private val ahead: Int = 6,          // 预读深度(块):约 1.5s@32Mbps 的余量
) : RandomSource {

    private val total = src.length()
    private val cache = LinkedHashMap<Long, ByteArray>(maxBlocks + 2, 0.75f, true) // accessOrder=LRU
    private val inflight = HashMap<Long, CountDownLatch>() // 正在下载的块
    private val queued = HashSet<Long>()                   // 已排队待预读的块
    private val prefetch = Executors.newSingleThreadExecutor { r -> Thread(r, "twig-prefetch").apply { isDaemon = true } }
    @Volatile private var gen = 0    // 世代:跳读(seek)时 +1,作废还在排队的旧预读
    private var lastBi = -1L         // 上次读的块号(cache 锁保护)

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

    /** 取一块:缓存命中直接回;有人正在下载就等它;否则自己下载。 */
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
            // 醒来后重查:正常应命中缓存;下载方失败时这里会接手重试
        }
    }

    /** 真正下载一块,放入缓存并唤醒等待者(失败也唤醒,由等待者接手)。 */
    private fun fetch(bi: Long): ByteArray {
        try {
            val off = bi * block
            val buf = ByteArray(block)
            var read = 0
            while (read < block) {
                val k = src.readAt(off + read, buf, read, block - read) // 定位读(不持缓存锁)
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
