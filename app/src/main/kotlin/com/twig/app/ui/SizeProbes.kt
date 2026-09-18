package com.twig.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.SizeProbe
import com.twig.core.XFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backfills the byte size of rows whose source cannot report one in a listing — media
 * server photos, see [SizeProbe].
 *
 * Same shape as [Thumbs], for the same reasons: the work is per row, it must not run on the
 * main thread, and the row it belongs to may have been recycled by the time the answer
 * arrives (hence the tag check). Much smaller, though — one request whose answer is a
 * header, cached in the backend from then on.
 *
 * ★ **The queue is LIFO.** Rows are enqueued in bind order, so during a fast scroll the
 * oldest entries are the ones that have already left the screen; serving the newest first
 * gives what the user is looking at its number first. The cap ([MAX_QUEUE]) drops from the
 * far end for the same reason — scrolling through a 3000-photo album must not leave 3000
 * pending requests behind.
 */
object SizeProbes {

    private const val THREADS = 3
    private const val MAX_QUEUE = 64
    private const val FAIL_COOLDOWN_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())

    private class Job(val fs: SizeProbe, val file: XFile, val key: String)

    private val queue = ArrayDeque<Job>()
    private val inFlight = HashSet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val workers = AtomicInteger(0)

    /** Callbacks waiting on one key; the same entry can be on screen in both panes. */
    private val waiters = HashMap<String, MutableList<(Long) -> Unit>>()

    private fun keyOf(file: XFile) = file.scheme + "|" + file.path

    /**
     * The size to render right now: the entry's own, or one probed earlier. 0 = unknown, and
     * [request] is what asks for it.
     */
    fun known(file: XFile): Long {
        if (file.size > 0) return file.size
        val fs = runCatching { FsRegistry.of(file) }.getOrNull() as? SizeProbe ?: return 0L
        return runCatching { fs.knownSize(file) }.getOrDefault(0L)
    }

    /**
     * Ask for [file]'s size on behalf of a row.
     *
     * [tagOwner] is the view the number will be written into: it carries which entry the
     * pending answer belongs to, so a recycled row is never given another row's size.
     * [onSize] runs on the main thread, only while the row is still current, and only when a
     * real size came back.
     */
    fun request(tagOwner: View, file: XFile, onSize: (Long) -> Unit) {
        if (file.isDir || file.size > 0) return
        val fs = runCatching { FsRegistry.of(file) }.getOrNull() as? SizeProbe ?: return
        val key = keyOf(file)
        tagOwner.setTag(R.id.size_probe, key)
        val cached = runCatching { fs.knownSize(file) }.getOrDefault(0L)
        if (cached > 0) {
            onSize(cached)
            return
        }
        failed[key]?.let { if (System.currentTimeMillis() - it < FAIL_COOLDOWN_MS) return }

        val deliver: (Long) -> Unit = { size ->
            if (size > 0 && tagOwner.getTag(R.id.size_probe) == key) onSize(size)
        }
        synchronized(queue) {
            waiters.getOrPut(key) { ArrayList() }.add(deliver)
            if (key in inFlight || queue.any { it.key == key }) return
            queue.addLast(Job(fs, file, key))
            while (queue.size > MAX_QUEUE) {
                val dropped = queue.pollFirst() ?: break // oldest = furthest off screen
                waiters.remove(dropped.key)
            }
        }
        startWorkerIfNeeded()
    }

    /** Forget a directory's pending probes (its rows are gone — collapsed, or navigated away). */
    fun cancelPending(files: Collection<XFile>) {
        if (files.isEmpty()) return
        val keys = files.mapTo(HashSet()) { keyOf(it) }
        synchronized(queue) {
            queue.removeAll { it.key in keys }
            keys.forEach { waiters.remove(it) }
        }
    }

    private fun startWorkerIfNeeded() {
        if (workers.get() >= THREADS) return
        workers.incrementAndGet()
        Thread({
            try {
                while (true) {
                    val job = synchronized(queue) {
                        val next = queue.pollLast() // newest first
                        if (next != null) inFlight += next.key
                        next
                    } ?: return@Thread
                    val size = runCatching { job.fs.probeSize(job.file) }.getOrDefault(0L)
                    if (size <= 0) failed[job.key] = System.currentTimeMillis()
                    val calls = synchronized(queue) {
                        inFlight -= job.key
                        waiters.remove(job.key).orEmpty()
                    }
                    if (calls.isNotEmpty()) main.post { calls.forEach { it(size) } }
                }
            } finally {
                workers.decrementAndGet()
            }
        }, "twig-size-probe").apply { isDaemon = true }.start()
    }
}
