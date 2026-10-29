package com.twig.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.twig.app.Format
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** Count-only cache; one worker, no recursive scans, no retained child listings. Main-thread API. */
object DirectoryCounts {
    data class Count(val directories: Int, val files: Int)
    private data class Entry(val all: Count, val visible: Count, val mtime: Long, val time: Long)
    private class Job(val file: XFile) {
        val active = AtomicBoolean(true)
        val callbacks = ArrayList<(Entry) -> Unit>()
    }
    private const val MAX_CACHE = 512
    private const val MAX_PENDING = 32
    private const val TTL = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, Entry>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_CACHE
    }
    private val failed = object : LinkedHashMap<String, Long>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_CACHE
    }
    private val pending = HashMap<String, Job>()
    private val queue = ArrayDeque<Job>()
    private val working = AtomicBoolean(false)
    private fun key(file: XFile) = file.scheme + "\u0000" + file.path
    fun supports(file: XFile) = file.isDir && when (Format.schemeLabel(file.scheme)) {
        "jellyfin", "emby" -> false
        "trash" -> file.path != "/"
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
        pending.remove(k)?.let { job ->
            job.active.set(false)
            job.callbacks.forEach { it(result) }
        }
    }

    fun invalidate(file: XFile) {
        val k = key(file)
        cache.remove(k); failed.remove(k)
        pending.remove(k)?.active?.set(false)
    }

    fun clear() {
        cache.clear(); failed.clear()
        pending.values.forEach { it.active.set(false) }
        pending.clear()
        synchronized(queue) { queue.clear() }
    }

    fun cancelPending(files: Collection<XFile>) {
        val keys = files.mapTo(HashSet()) { key(it) }
        keys.forEach { pending.remove(it)?.active?.set(false) }
        synchronized(queue) { queue.removeAll { key(it.file) in keys } }
    }

    /** Only bound rows request work; a recycled view cannot receive another directory's count. */
    fun request(view: View, file: XFile, showHidden: Boolean, deliver: (Count) -> Unit) {
        if (!supports(file)) return
        val token = Any()
        view.setTag(R.id.directory_count, token)
        known(file, showHidden)?.let { deliver(it); return }
        val k = key(file)
        if (failed[k]?.let { SystemClock.elapsedRealtime() - it < TTL } == true) return
        val callback: (Entry) -> Unit = { result ->
            if (view.getTag(R.id.directory_count) === token)
                deliver(if (showHidden) result.all else result.visible)
        }
        pending[k]?.let { it.callbacks.add(callback); return }
        val job = Job(file).also { it.callbacks.add(callback) }
        pending[k] = job
        synchronized(queue) {
            queue.addLast(job)
            while (queue.size > MAX_PENDING) {
                val dropped = queue.removeFirst()
                dropped.active.set(false)
                if (pending[key(dropped.file)] === dropped) pending.remove(key(dropped.file))
            }
        }
        startWorker()
    }

    private fun startWorker() {
        if (synchronized(queue) { queue.isEmpty() } || !working.compareAndSet(false, true)) return
        Thread({
            try {
                while (true) {
                    val job = synchronized(queue) { queue.pollLast() } ?: break
                    if (!job.active.get()) continue
                    val result = runCatching {
                        val fs = FsRegistry.of(job.file)
                        // Local counts need only the name and type: skip sorting, size, time and permissions.
                        val children = if (fs is LocalFileSystem) fs.listForSize(job.file) {
                            XFile(job.file.scheme, it.path, isDir = it.isDirectory)
                        } else fs.list(job.file)
                        entry(job.file, children)
                    }.getOrNull()
                    main.post {
                        val k = key(job.file)
                        if (pending[k] !== job) return@post
                        pending.remove(k)
                        if (result == null) failed[k] = SystemClock.elapsedRealtime()
                        else {
                            cache[k] = result
                            job.callbacks.forEach { it(result) }
                        }
                    }
                }
            } finally {
                working.set(false)
                main.post { startWorker() }
            }
        }, "twig-directory-count").apply { isDaemon = true }.start()
    }
}
