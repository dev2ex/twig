package com.twig.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.twig.app.Format
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.DirectoryCountSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors

/** Count-only cache; isolated local/remote workers, no recursive scans. Main-thread API. */
object DirectoryCounts {
    data class Count(val directories: Int, val files: Int)
    private data class Entry(val all: Count, val visible: Count, val mtime: Long, val time: Long)
    private class Lane(name: String) {
        val queue = ArrayDeque<Job>()
        var working = false
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, name).apply { isDaemon = true }
        }
    }
    private class Job(val file: XFile, val lane: Lane) {
        val active = AtomicBoolean(true)
        val waiters = ArrayList<Waiter>()
    }
    private class Waiter(
        val job: Job, val view: View, val showHidden: Boolean,
        val order: () -> Int, val deliver: (Count) -> Unit,
    ) {
        fun current() = view.getTag(R.id.directory_count) === this
    }
    private const val MAX_CACHE = 512
    private const val MAX_PENDING = 32
    private const val PROBE_TIMEOUT = 3_000L
    private const val TTL = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, Entry>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_CACHE
    }
    private val failed = object : LinkedHashMap<String, Long>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_CACHE
    }
    private val pending = HashMap<String, Job>()
    private val local = Lane("twig-directory-count-local")
    private val remote = Lane("twig-directory-count-remote")
    internal fun isIdle(): Boolean = !local.working && !remote.working && pending.isEmpty()

    private fun key(file: XFile) = file.scheme + "\u0000" + file.path
    private fun order(job: Job) = job.waiters.asSequence().filter { it.current() }
        .minOfOrNull { it.order() } ?: Int.MAX_VALUE
    fun supports(file: XFile) = file.isDir && when (Format.schemeLabel(file.scheme)) {
        "jellyfin", "emby" -> false
        "trash", "apps" -> file.path != "/"
        else -> true
    }

    fun known(file: XFile, showHidden: Boolean): Count? {
        if (!supports(file)) return null
        val entry = cache[key(file)] ?: return null
        if (SystemClock.elapsedRealtime() - entry.time >= TTL ||
            (file.lastModified > 0 && file.lastModified != entry.mtime)) return null
        return if (showHidden) entry.all else entry.visible
    }

    private fun entry(file: XFile, children: List<XFile>): Entry {
        var dirs = 0
        var files = 0
        var visibleDirs = 0
        var visibleFiles = 0
        for (child in children) {
            if (child.isDir) dirs++ else files++
            if (!child.name.startsWith('.')) {
                if (child.isDir) visibleDirs++ else visibleFiles++
            }
        }
        return Entry(Count(dirs, files), Count(visibleDirs, visibleFiles),
            file.lastModified, SystemClock.elapsedRealtime())
    }

    /** Reuse ordinary expansion/refresh results and supersede an older probe in flight. */
    fun remember(file: XFile, children: List<XFile>) {
        if (!supports(file)) return
        val k = key(file)
        val result = entry(file, children)
        cache[k] = result
        failed.remove(k)
        pending[k]?.let { job ->
            finish(job, result)
        }
    }

    private fun cancel(job: Job) {
        job.active.set(false)
        if (pending[key(job.file)] === job) pending.remove(key(job.file))
        job.lane.queue.remove(job)
        job.waiters.forEach { if (it.current()) it.view.setTag(R.id.directory_count, null) }
        job.waiters.clear()
    }

    /** Release just this row; another pane may still be waiting for the same directory. */
    fun release(view: View) {
        val waiter = view.getTag(R.id.directory_count) as? Waiter
        view.setTag(R.id.directory_count, null)
        if (waiter == null) return
        waiter.job.waiters.remove(waiter)
        if (waiter.job.waiters.none { it.current() }) cancel(waiter.job)
    }

    fun invalidate(file: XFile) {
        val k = key(file)
        cache.remove(k); failed.remove(k)
        pending[k]?.let(::cancel)
    }

    fun clear() {
        cache.clear(); failed.clear()
        pending.values.toList().forEach(::cancel)
    }

    /** Attached rows own their work; detach, recycling and pane hiding call [release]. */
    fun request(view: View, file: XFile, showHidden: Boolean,
                order: () -> Int = { Int.MAX_VALUE }, deliver: (Count) -> Unit) {
        if (!supports(file)) { release(view); return }
        known(file, showHidden)?.let { release(view); deliver(it); return }
        val k = key(file)
        if (failed[k]?.let { SystemClock.elapsedRealtime() - it < TTL } == true) {
            release(view)
            return
        }
        val lane = if (file.scheme == "file") local else remote
        val job = pending[k] ?: Job(file, lane)
        val previous = view.getTag(R.id.directory_count) as? Waiter
        if (previous?.job === job) job.waiters.remove(previous) else release(view)
        val waiter = Waiter(job, view, showHidden, order, deliver)
        view.setTag(R.id.directory_count, waiter)
        job.waiters.add(waiter)
        if (pending[k] === job) return
        pending[k] = job
        lane.queue.addLast(job)
        while (lane.queue.size > MAX_PENDING) cancel(lane.queue.maxBy(::order))
        // Coalesce binds/detaches before starting blocking filesystem work.
        main.post { startWorker(lane) }
    }

    private fun finish(job: Job, result: Entry?) {
        if (pending[key(job.file)] !== job) return
        pending.remove(key(job.file))
        job.active.set(false)
        job.lane.queue.remove(job)
        if (result == null) failed[key(job.file)] = SystemClock.elapsedRealtime()
        else cache[key(job.file)] = result
        val waiters = job.waiters.toList()
        job.waiters.clear()
        waiters.forEach { waiter ->
            if (waiter.current()) {
                waiter.view.setTag(R.id.directory_count, null)
                if (result != null) waiter.deliver(if (waiter.showHidden) result.all else result.visible)
            }
        }
    }

    private fun startWorker(lane: Lane) {
        if (lane.working) return
        var next: Job? = null
        while (lane.queue.isNotEmpty()) {
            // Read live adapter positions: attachment order and sort changes need not
            // match display order. Equal positions retain FIFO order across panes.
            val job = lane.queue.minBy(::order)
            lane.queue.remove(job)
            if (job.active.get() && job.waiters.any { it.current() }) {
                next = job
                break
            }
            cancel(job)
        }
        val job = next ?: return
        lane.working = true
        lane.executor.execute {
            val result = runCatching {
                // Queue wait is free. Blocking backend calls cannot be forcibly stopped;
                // reject late results and let streaming backends stop between entries.
                val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT
                fun keepGoing() = job.active.get() && SystemClock.elapsedRealtime() < deadline
                check(keepGoing())
                val fs = FsRegistry.of(job.file)
                if (fs is DirectoryCountSource) {
                    val count = fs.countChildren(job.file, 1000, ::keepGoing)
                    return@runCatching count?.takeIf { keepGoing() }?.let {
                        Entry(Count(it.directories, it.files), Count(it.visibleDirectories, it.visibleFiles),
                            job.file.lastModified, SystemClock.elapsedRealtime())
                    }
                }
                // Local counts need only names/types; check for abandoned work between entries.
                val children = if (fs is LocalFileSystem) fs.listForSize(job.file) {
                    check(keepGoing())
                    XFile(job.file.scheme, it.path, isDir = it.isDirectory)
                } else fs.list(job.file)
                if (keepGoing()) entry(job.file, children).takeIf { keepGoing() } else null
            }.getOrNull()
            main.post {
                try { finish(job, result) }
                finally {
                    lane.working = false
                    startWorker(lane)
                }
            }
        }
    }
}
