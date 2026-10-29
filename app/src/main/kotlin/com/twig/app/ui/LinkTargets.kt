package com.twig.app.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.widget.TextView
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Link subtitles share listing metadata; only visible privileged entries need a background read. */
internal object LinkTargets {
    private data class Key(val fs: LocalFileSystem, val path: String, val size: Long, val modified: Long)
    private data class Cached(val target: String?, val at: Long)
    private data class Waiting(val view: WeakReference<TextView>, val token: Any)
    private val cache = LruCache<Key, Cached>(256)
    private val pending = HashMap<Key, MutableList<Waiting>>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "twig-link-targets").apply { isDaemon = true } })

    fun clear(view: TextView) {
        view.setTag(R.id.link_target, null)
        view.text = ""
        view.visibility = View.GONE
    }

    fun bind(view: TextView, file: XFile) {
        clear(view)
        if (!file.isSymbolicLink) return
        file.linkTarget?.let { show(view, it); return }
        val fs = runCatching { FsRegistry.of(file) }.getOrNull() as? LocalFileSystem ?: return
        val key = Key(fs, file.path, file.size, file.lastModified)
        cache.get(key)?.takeIf { SystemClock.elapsedRealtime() - it.at < 10_000L }?.let {
            it.target?.let { target -> show(view, target) }
            return
        }
        val token = Any()
        view.setTag(R.id.link_target, token)
        val waiting = Waiting(WeakReference(view), token)
        pending[key]?.let { it.add(waiting); return }
        pending[key] = mutableListOf(waiting)
        try {
            worker.execute {
                val target = runCatching { fs.linkTarget(file) }.getOrNull()
                main.post {
                    cache.put(key, Cached(target, SystemClock.elapsedRealtime()))
                    pending.remove(key)?.forEach { waiter ->
                        val current = waiter.view.get()
                        if (current != null && current.getTag(R.id.link_target) === waiter.token)
                            target?.let { show(current, it) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    private fun show(view: TextView, target: String) {
        view.text = "→ $target"
        view.visibility = View.VISIBLE
    }
}
