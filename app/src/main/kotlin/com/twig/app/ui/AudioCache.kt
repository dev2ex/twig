package com.twig.app.ui

import android.content.Context
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 网络音频的"每曲磁盘缓存"注册表——一首歌的播放、seek、波形计算共用**同一份**下载到本地
 * 的字节:
 *
 * - **只下一次**:播放器与波形都向 [source] 要同一个 [Entry],按 1MB 分块下载,谁先要到某块
 *   谁下载,另一方直接命中,不重复下载(解决"波形和播放各下一遍""seek 重新下")。
 * - **seek 零流量**:已下载的块落在本地缓存文件里,seek 回去直接 pread 磁盘,不碰网络。
 * - **预取下一首**:[prefetch] 后台把整首拉进缓存(配合波形/封面预取),切歌即时出声。
 * - **LRU 保留最近 [MAX] 首**:第 6 首进来时关掉并删掉最久未用的那首的连接与缓存文件。
 *
 * 共享源的 [Entry.close] 是空操作——生命周期只由注册表(LRU 淘汰 / [releaseAll])掌管,
 * 避免了旧版"ExoPlayer 每次 seek/循环 close 一次就把连接和缓存全丢掉重来"的流量浪费与状态错乱。
 * 本地文件(scheme=="file")不进缓存,调用方照旧直接播。
 */
object AudioCache {

    const val DEFAULT_MAX = 5
    private const val BLOCK = 1 shl 20 // 1MB/块
    private const val MAX_ATTEMPTS = 3 // 单块下载失败(连接掉)重连重试次数
    private const val BACKOFF_MS = 300L // 重试退避基数(第 n 次 × n)

    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var maxTracks = DEFAULT_MAX // 保留最近几首,可在设置里调
    private val prefetchExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-audio-prefetch").apply { isDaemon = true }
    }

    // accessOrder=true → get/put 即刷新为最近使用,eldest 在头部
    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            if (size <= maxTracks) return false
            eldest?.value?.dispose()
            return true
        }
    }

    /** 首次使用前置好缓存目录;清掉上次运行的残留(内存里的已下载位图不跨进程,旧文件不可信)。 */
    fun init(ctx: Context) {
        maxTracks = com.twig.app.Prefs.audioCacheCount(ctx)
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            val d = File(ctx.applicationContext.cacheDir, "audiocache").apply { mkdirs() }
            // 持锁同步清残留:此刻还没有任何 Entry 能创建缓存文件(entryOf 也要抢同一把锁,
            // 且 dir 尚未置位),故只会删到上次运行的旧文件,不会误删本次正在写的文件。
            d.listFiles()?.forEach { runCatching { it.delete() } }
            dir = d
        }
    }

    /** 调整保留首数;调小则立即淘汰多出来的最久未用项(关闭连接、删缓存文件)。 */
    fun setMax(n: Int) = synchronized(lock) {
        maxTracks = n.coerceIn(1, 20)
        val it = entries.entries.iterator() // 迭代顺序 = 最久未用在前
        while (entries.size > maxTracks && it.hasNext()) {
            it.next().value.dispose()
            it.remove()
        }
    }

    /** 该曲的共享源;close() 为空操作,真正释放交注册表。 */
    fun source(file: XFile): RandomSource = entryOf(file)

    /** 后台把整首下进缓存(预取下一首用)。 */
    fun prefetch(file: XFile) {
        if (file.scheme == "file") return
        val e = entryOf(file)
        prefetchExec.execute { runCatching { e.downloadAll() } }
    }

    fun releaseAll() = synchronized(lock) {
        entries.values.forEach { it.dispose() }
        entries.clear()
    }

    /** 当前磁盘缓存占用(供设置页显示)。 */
    fun cacheBytes(ctx: Context): Long =
        cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** 清空音乐缓存:释放所有条目(关连接、删缓存文件)再兜底删残留。播放中清理会短暂打断当前曲。 */
    fun clearCache(ctx: Context) {
        releaseAll()
        cacheDir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun cacheDir(ctx: Context): File =
        dir ?: File(ctx.applicationContext.cacheDir, "audiocache")

    private fun entryOf(file: XFile): Entry = synchronized(lock) {
        val k = keyOf(file)
        entries[k] ?: Entry(file, dir?.let { File(it, k) }).also { entries[k] = it }
    }

    private fun keyOf(f: XFile): String =
        MessageDigest.getInstance("MD5").digest("${f.scheme}:${f.path}:${f.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- 单曲条目 ----

    /**
     * 一首歌的共享随机源 + 本地缓存文件。[readAt] 按绝对位置定位——多个消费者(播放 loader 线程、
     * 波形解码线程、预取线程)可并发读同一 Entry,同一块只允许一个线程真正下载(inflight 闩),
     * 其余等它写完直接读磁盘。总长未知(size<=0,理论不该发生)时退化成普通 [BufferedRandomSource]
     * 直读,不落盘。
     */
    private class Entry(private val file: XFile, private val cacheFile: File?) : RandomSource {

        private val total: Long = file.size
        private val diskMode = cacheFile != null && total > 0

        private val state = Any()
        private val downloaded = BitSet()
        private val inflight = HashMap<Int, CountDownLatch>()
        private var upstream: RandomSource? = null
        private var raf: RandomAccessFile? = null
        private var channel: FileChannel? = null
        private var passthrough: BufferedRandomSource? = null
        private var disposed = false
        @Volatile private var downloadAllDone = false

        override fun length(): Long = if (total > 0) total else passthrough().length()

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (!diskMode) return passthrough().readAt(position, buffer, offset, length)
            if (position >= total) return -1
            val bi = (position / BLOCK).toInt()
            if (!ensureBlock(bi)) {
                if (synchronized(state) { disposed }) return -1
                // 块没下来(连接掉了且重连没救回来)——position<total 不是真 EOF,抛 IO 错让
                // ExoPlayer 走它自己的退避重试(下次读会触发重连),而不是误当文件结束/死循环。
                throw IOException("block $bi fetch failed: ${file.name}")
            }
            val ch = synchronized(state) { channel } ?: return -1
            val blockStart = bi.toLong() * BLOCK
            val blockLen = minOf(BLOCK.toLong(), total - blockStart).toInt()
            val within = (position - blockStart).toInt()
            val avail = blockLen - within
            if (avail <= 0) return -1
            val n = ch.read(ByteBuffer.wrap(buffer, offset, minOf(length, avail)), position)
            return if (n <= 0) -1 else n
        }

        /** 确保第 bi 块已在本地。返回是否成功(命中即返回;有人在下就等;否则本线程下载)。 */
        private fun ensureBlock(bi: Int): Boolean {
            while (true) {
                var wait: CountDownLatch? = null
                synchronized(state) {
                    if (disposed) return false
                    if (downloaded.get(bi)) return true
                    val l = inflight[bi]
                    if (l != null) wait = l else inflight[bi] = CountDownLatch(1)
                }
                if (wait == null) return fetchBlock(bi)
                wait!!.await()
                synchronized(state) {
                    if (downloaded.get(bi)) return true
                    if (disposed) return false
                    // 下载方失败且没人在下 → 本线程下轮成为下载者重试;否则继续等新的下载者
                }
            }
        }

        /** 下载一块;连接掉了(POLLHUP 等)就丢弃死连接、重连、退避重试几次。成功返回 true。 */
        private fun fetchBlock(bi: Int): Boolean {
            try {
                val off = bi.toLong() * BLOCK
                val size = minOf(BLOCK.toLong(), total - off).toInt()
                val buf = ByteArray(size)
                var attempt = 0
                while (attempt < MAX_ATTEMPTS) {
                    if (synchronized(state) { disposed }) return false
                    val ok = runCatching {
                        val up = ensureOpen()
                        var read = 0
                        while (read < size) {
                            val k = up.readAt(off + read, buf, read, size - read)
                            if (k <= 0) break
                            read += k
                        }
                        read == size
                    }.getOrDefault(false)
                    if (ok) {
                        synchronized(state) {
                            channel?.write(ByteBuffer.wrap(buf, 0, size), off)
                            downloaded.set(bi)
                        }
                        return true
                    }
                    dropUpstream() // 死连接/短读 → 丢掉,下轮 ensureOpen 重连
                    attempt++
                    if (attempt < MAX_ATTEMPTS) runCatching { Thread.sleep(BACKOFF_MS * attempt) }
                }
                return false
            } finally {
                synchronized(state) { inflight.remove(bi)?.countDown() }
            }
        }

        /** 打开上游连接 + 缓存文件通道(通道只开一次;上游断开后可重开)。
         *  网络连接(可能阻塞数秒)放在锁外,避免拖住 dispose/其它块的读。 */
        private fun ensureOpen(): RandomSource {
            synchronized(state) {
                if (disposed) throw IllegalStateException("disposed")
                if (channel == null) {
                    val r = RandomAccessFile(cacheFile, "rw").apply { setLength(total) }
                    raf = r; channel = r.channel
                }
                upstream?.let { return it }
            }
            val up = FsRegistry.of(file).openRandom(file) // 锁外连接
            synchronized(state) {
                if (disposed) { runCatching { up.close() }; throw IllegalStateException("disposed") }
                upstream?.let { runCatching { up.close() }; return it } // 竞争:别人已开好,关掉我这条
                upstream = up
                return up
            }
        }

        private fun dropUpstream() = synchronized(state) {
            runCatching { upstream?.close() }
            upstream = null
        }

        private fun passthrough(): RandomSource = synchronized(state) {
            if (disposed) throw IllegalStateException("disposed")
            passthrough ?: BufferedRandomSource(FsRegistry.of(file).openRandom(file)).also { passthrough = it }
        }

        /** 顺序拉完整首;全下完关掉上游连接省资源,之后读全走磁盘。 */
        fun downloadAll() {
            if (!diskMode || downloadAllDone) return
            val blocks = ((total + BLOCK - 1) / BLOCK).toInt()
            for (bi in 0 until blocks) {
                synchronized(state) { if (disposed) return }
                if (!ensureBlock(bi)) return // 网络掉了,预取先放弃(别每块都退避重试拖时间)
            }
            synchronized(state) {
                downloadAllDone = true
                if (downloaded.cardinality() == blocks) {
                    runCatching { upstream?.close() }
                    upstream = null
                }
            }
        }

        /** 共享源:close 空操作,生命周期归 [AudioCache]。 */
        override fun close() {}

        fun dispose() {
            synchronized(state) {
                if (disposed) return
                disposed = true
                runCatching { upstream?.close() }
                runCatching { passthrough?.close() }
                runCatching { channel?.close() }
                runCatching { raf?.close() }
                upstream = null; passthrough = null; channel = null; raf = null
            }
            runCatching { cacheFile?.delete() }
        }
    }
}
