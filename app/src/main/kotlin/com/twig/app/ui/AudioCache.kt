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
 * Per-track on-disk cache registry for network audio — playback, seeking, and waveform
 * computation for one song share **the same** downloaded bytes:
 *
 * - **Download once**: both the player and the waveform ask [source] for the same [Entry],
 *   split into 1 MB blocks; whoever asks first downloads the block, the other side hits
 *   directly with no duplicate download (solves "waveform and playback each download once",
 *   "seek triggers a redownload").
 * - **Zero traffic on seek**: already-downloaded blocks live in the local cache file; seeking
 *   back uses pread on disk, never the network.
 * - **Prefetch the next track**: [prefetch] pulls the whole song into the cache in the
 *   background (paired with waveform/cover prefetch), so the next song starts instantly.
 * - **LRU keeps the last [MAX] tracks**: when the 6th track arrives, the least-recently-used
 *   one's connection and cache file are closed and deleted.
 *
 * The shared source's [Entry.close] is a no-op — lifecycle is owned solely by the registry
 * (LRU eviction / [releaseAll]), avoiding the old version's "ExoPlayer calls close on every
 * seek/loop, dropping connection and cache and starting over" traffic waste and state confusion.
 * Local files (scheme=="file") do not enter the cache; the caller plays them directly as before.
 */
object AudioCache {

    const val DEFAULT_MAX = 5
    private const val BLOCK = 1 shl 20 // 1MB per block
    private const val MAX_ATTEMPTS = 3 // retry count on per-block download failure (connection dropped)
    private const val BACKOFF_MS = 300L // retry backoff base (nth attempt × n)

    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var maxTracks = DEFAULT_MAX // how many recent tracks to keep; configurable in settings
    private val prefetchExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "twig-audio-prefetch").apply { isDaemon = true }
    }

    // accessOrder=true → get/put refreshes the entry as most-recently-used; eldest is at the head
    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            if (size <= maxTracks) return false
            eldest?.value?.dispose()
            return true
        }
    }

    /** Prepare the cache directory on first use; clear leftovers from the previous run (the in-memory downloaded bitmap set does not survive across processes, so the old files are untrustworthy). */
    fun init(ctx: Context) {
        maxTracks = com.twig.app.Prefs.audioCacheCount(ctx)
        if (dir != null) return
        synchronized(lock) {
            if (dir != null) return
            val d = File(ctx.applicationContext.cacheDir, "audiocache").apply { mkdirs() }
            // Cleanup under lock: no Entry can create a cache file at this moment (entryOf also
            // grabs the same lock, and dir is not yet set), so only the previous run's old files
            // get deleted — never a file being written by the current run.
            d.listFiles()?.forEach { runCatching { it.delete() } }
            dir = d
        }
    }

    /** Adjust the retention count; if reduced, immediately evict the extra least-recently-used entries (close connection, delete cache file). */
    fun setMax(n: Int) = synchronized(lock) {
        maxTracks = n.coerceIn(1, 20)
        val it = entries.entries.iterator() // iteration order = least-recently-used first
        while (entries.size > maxTracks && it.hasNext()) {
            it.next().value.dispose()
            it.remove()
        }
    }

    /** Shared source for this track; close() is a no-op, real release is the registry's job. */
    fun source(file: XFile): RandomSource = entryOf(file)

    /** In the background, download the whole track into the cache (used for prefetching the next track). */
    fun prefetch(file: XFile) {
        if (file.scheme == "file") return
        val e = entryOf(file)
        prefetchExec.execute { runCatching { e.downloadAll() } }
    }

    fun releaseAll() = synchronized(lock) {
        entries.values.forEach { it.dispose() }
        entries.clear()
    }

    /** Current disk cache usage (for display on the settings page). */
    fun cacheBytes(ctx: Context): Long =
        cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** Clear the music cache: release all entries (close connections, delete cache files) then sweep up any leftover. Clearing during playback briefly interrupts the current track. */
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

    // ---- Single-track entry ----

    /**
     * A shared random source + local cache file for one song. [readAt] addresses by absolute
     * position — multiple consumers (the playback loader thread, the waveform decoder thread,
     * the prefetch thread) may read the same Entry concurrently; only one thread is allowed to
     * actually download a given block (inflight latch), the rest wait for it to be written then
     * read from disk. When the total length is unknown (size<=0, which theoretically should not
     * happen), it falls back to a plain [BufferedRandomSource] reading straight through, with no
     * disk caching.
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
                // The block never arrived (connection dropped and reconnect did not save it) —
                // position<total is not a real EOF, so throw an IO error and let ExoPlayer apply
                // its own backoff retry (the next read triggers a reconnect) instead of treating
                // it as end-of-file and spinning.
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

        /** Ensure block bi is local. Returns success (cache hit returns immediately; if someone is downloading, wait; otherwise this thread downloads). */
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
                    // Downloader failed and no one is downloading → this thread becomes the
                    // downloader and retries on the next round; otherwise keep waiting for the
                    // new downloader
                }
            }
        }

        /** Download a block; on connection drop (POLLHUP etc.), discard the dead connection, reconnect, and backoff-retry a few times. Returns success. */
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
                    dropUpstream() // dead connection / short read → drop it; next ensureOpen reconnects
                    attempt++
                    if (attempt < MAX_ATTEMPTS) runCatching { Thread.sleep(BACKOFF_MS * attempt) }
                }
                return false
            } finally {
                synchronized(state) { inflight.remove(bi)?.countDown() }
            }
        }

        /** Open the upstream connection + the cache file channel (channel is opened once; the upstream can be reopened on disconnect).
         *  The network connection (which can block for several seconds) is done outside the lock to avoid blocking dispose / reads of other blocks. */
        private fun ensureOpen(): RandomSource {
            synchronized(state) {
                if (disposed) throw IllegalStateException("disposed")
                if (channel == null) {
                    val r = RandomAccessFile(cacheFile, "rw").apply { setLength(total) }
                    raf = r; channel = r.channel
                }
                upstream?.let { return it }
            }
            val up = FsRegistry.of(file).openRandom(file) // connection outside the lock
            synchronized(state) {
                if (disposed) { runCatching { up.close() }; throw IllegalStateException("disposed") }
                upstream?.let { runCatching { up.close() }; return it } // race: someone already opened it; close mine
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

        /** Pull the whole track sequentially; when fully downloaded, close the upstream connection to save resources, after which all reads go through disk. */
        fun downloadAll() {
            if (!diskMode || downloadAllDone) return
            val blocks = ((total + BLOCK - 1) / BLOCK).toInt()
            for (bi in 0 until blocks) {
                synchronized(state) { if (disposed) return }
                if (!ensureBlock(bi)) return // network dropped; prefetch gives up (no per-block backoff retry dragging it out)
            }
            synchronized(state) {
                downloadAllDone = true
                if (downloaded.cardinality() == blocks) {
                    runCatching { upstream?.close() }
                    upstream = null
                }
            }
        }

        /** Shared source: close is a no-op; lifecycle belongs to [AudioCache]. */
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
