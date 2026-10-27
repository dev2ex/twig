package com.twig.app

import android.content.Context
import com.twig.core.CopyEngine
import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Immutable completed files can be reused without disturbing URIs already handed to another app. */
internal object SharePreparation {
    private const val RETENTION_MS = 24 * 60 * 60 * 1000L
    private val active = HashMap<File, Int>()

    data class Progress(val name: String, val index: Int, val count: Int, val done: Long, val total: Long,
                        val fileDone: Long, val fileTotal: Long)
    class Prepared(val files: List<XFile>, private val held: Set<File>, val cacheOnly: Boolean = false) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(active) {
                held.forEach { file ->
                    file.setLastModified(System.currentTimeMillis())
                    val count = active.getValue(file) - 1
                    if (count == 0) active.remove(file) else active[file] = count
                }
            }
        }
    }

    /** Missing size/time cannot establish identity reliably; such sources are prepared afresh. */
    private fun cacheKey(file: XFile): String? {
        if (file.size <= 0 || file.lastModified <= 0) return null
        val identity = buildString {
            listOf(file.scheme, file.path, file.name, file.size.toString(), file.lastModified.toString()).forEach {
                append(it.length).append(':').append(it)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Blocking IO. Only directly readable local files can bypass preparation. */
    fun prepare(ctx: Context, files: List<XFile>, cancelled: () -> Boolean,
                progress: (Progress) -> Unit): Prepared {
        val root = File(ctx.cacheDir, CacheDirs.SHARE)
        val held = LinkedHashSet<File>()
        val temporary = HashSet<File>()
        fun checkCancelled() { if (cancelled()) throw CancellationException() }
        fun hold(file: File) {
            if (held.add(file)) active[file] = (active[file] ?: 0) + 1
            file.setLastModified(System.currentTimeMillis())
        }
        var complete = false
        try {
            checkCancelled()
            synchronized(active) {
                if (!root.isDirectory && !root.mkdirs()) throw IOException(ctx.getString(R.string.share_prepare_failed))
                CacheDirs.trim(root, cap = 0, minAgeMs = RETENTION_MS, keepFiles = active.keys)
            }
            val pending = files.filterNot(OpenFiles::directlyReadable).distinct()
            // Reserve hits before checking free space: they consume no additional storage.
            val cached = synchronized(active) {
                pending.associateWith { file ->
                    cacheKey(file)?.let { File(root, it) }?.takeIf { it.isFile && it.length() == file.size }
                        ?.also(::hold)
                }.toMutableMap()
            }
            val missing = pending.filter { cached[it] == null }
            val total = if (missing.all { it.size > 0 }) missing.sumOf { it.size } else 0L
            if (total > root.usableSpace) throw IOException(ctx.getString(R.string.share_prepare_space))
            var done = 0L
            var index = 0
            val prepared = files.map { file ->
                checkCancelled()
                if (OpenFiles.directlyReadable(file)) return@map file
                fun local(output: File) = XFile("file", output.absolutePath, false, output.length(), displayName = file.name)
                cached[file]?.let { return@map local(it) }
                index++
                var bytes = 0L
                fun report() = progress(Progress(file.name, index, missing.size, done, total, bytes, file.size))
                report()
                val output = synchronized(active) {
                    File(root, ".partial-${UUID.randomUUID()}").also {
                        active[it] = 1
                        temporary.add(it)
                    }
                }
                FsRegistry.of(file).openInput(file).use { input ->
                    output.outputStream().use { out ->
                        val copied = CopyEngine.pipe(input, out, { cancelled() }) { n ->
                            bytes += n
                            done += n
                            report()
                        }
                        if (!copied) throw CancellationException()
                    }
                }
                if (file.size > 0 && bytes != file.size) throw IOException(ctx.getString(R.string.share_prepare_incomplete))
                checkCancelled()
                val ready = synchronized(active) {
                    val target = File(root, cacheKey(file) ?: UUID.randomUUID().toString())
                    // Another preparation may have completed the same source while this one read it.
                    if (target.isFile && target.length() == bytes) {
                        output.delete()
                    } else if (!output.renameTo(target)) {
                        throw IOException(ctx.getString(R.string.share_prepare_failed))
                    }
                    active.remove(output)
                    temporary.remove(output)
                    hold(target)
                    target
                }
                cached[file] = ready
                local(ready)
            }
            checkCancelled()
            complete = true
            return Prepared(prepared, held, cacheOnly = missing.isEmpty())
        } finally {
            synchronized(active) {
                temporary.forEach { it.delete(); active.remove(it) }
            }
            // Complete entries remain reusable even if a later file fails; partial files never do.
            if (!complete) Prepared(emptyList(), held).release()
        }
    }
}
