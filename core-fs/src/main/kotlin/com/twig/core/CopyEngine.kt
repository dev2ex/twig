package com.twig.core

/**
 * Cross-file-system copy / move engine.
 *
 * The essence: copying from any source to any destination = source.openInput() ->
 * destination.openOutput() streamed. So combinations like "copy a file from FTP
 * into a local archive" come for free, no NxN specialised code needed.
 *
 * All operations are blocking IO; the caller must run them on a worker thread,
 * and can receive progress via [ProgressListener], cancel via [Cancelled], and
 * decide same-name conflicts via [ConflictResolver] (overwrite / skip / rename).
 */
object CopyEngine {

    fun interface Cancelled {
        fun isCancelled(): Boolean
    }

    /** How to resolve a same-name conflict. */
    enum class Decision { OVERWRITE, SKIP, RENAME }

    fun interface ConflictResolver {
        /**
         * Called when the destination directory already contains an entry with
         * the same name as [src]; may block (UI dialog waiting on the user).
         * Returning null cancels the whole task. Same-name directories are not
         * asked about — they are merged directly.
         */
        fun resolve(src: XFile, existing: XFile): Decision?
    }

    /** Statistics for a batch of entries (total bytes / file count / dir count), for confirmation and progress display. */
    data class Plan(val bytes: Long, val files: Int, val dirs: Int)

    interface ProgressListener {
        /** Started processing a file. */
        fun onFile(file: XFile) {}
        /** Bytes already transferred for the current file. */
        fun onFileBytes(copied: Long, size: Long) {}
        /** Cumulative bytes transferred (across the whole task). */
        fun onBytes(copiedTotal: Long, totalBytes: Long) {}
        /** Finished (or skipped) one file / finished one directory; used for the remaining-count. */
        fun onItemDone(isDir: Boolean) {}
        /** Whole task finished. */
        fun onDone() {}
    }

    /** Recursively tallies a batch of entries (directories are accumulated recursively). */
    fun plan(items: List<XFile>): Plan {
        var bytes = 0L
        var files = 0
        var dirs = 0
        fun walk(f: XFile) {
            if (f.isDir) {
                dirs++
                for (c in FsRegistry.of(f).list(f)) walk(c)
            } else {
                files++
                bytes += f.size
            }
        }
        items.forEach { walk(it) }
        return Plan(bytes, files, dirs)
    }

    /** Estimate total bytes for a batch (used by the progress bar); directories are accumulated recursively. */
    fun totalSize(items: List<XFile>): Long = plan(items).bytes

    /** Mutable task state shared during recursion. */
    private class Task(
        val listener: ProgressListener?,
        val cancelled: Cancelled,
        val resolver: ConflictResolver,
        val total: Long,
    ) {
        var bytes = 0L
        var aborted = false // user picked "Cancel" in the conflict dialog
        /** Cache of destination directory listings (dir uri -> name -> entry), to avoid re-listing remote dirs per file. */
        val listed = HashMap<String, MutableMap<String, XFile>>()
    }

    /** Existing entries of a destination directory (cached; newly created entries are added for later conflict / same-name checks). */
    private fun childrenOf(task: Task, destFs: FileSystem, destDir: XFile): MutableMap<String, XFile> =
        task.listed.getOrPut(destDir.toUri()) {
            runCatching { destFs.list(destDir).associateBy { it.name }.toMutableMap() }
                .getOrDefault(HashMap())
        }

    /**
     * Copy a batch of entries into a destination directory.
     * @param move when true, delete the source after a successful copy (or, if the
     *             same FileSystem supports moveWithin, move in place); skipped /
     *             cancelled entries are not deleted.
     * @param resolver same-name file conflict decision; default is overwrite (matches legacy behaviour).
     */
    fun transfer(
        items: List<XFile>,
        destDir: XFile,
        move: Boolean,
        listener: ProgressListener? = null,
        cancelled: Cancelled = Cancelled { false },
        resolver: ConflictResolver = ConflictResolver { _, _ -> Decision.OVERWRITE },
        plannedBytes: Long = -1, // when the caller has already called plan(), pass it in to avoid a second recursive scan
    ) {
        val task = Task(listener, cancelled, resolver, if (plannedBytes >= 0) plannedBytes else totalSize(items))
        val destFs = FsRegistry.of(destDir)
        for (item in items) {
            if (task.cancelled.isCancelled() || task.aborted) break
            // Same file system and no same-name conflict at the destination: prefer
            // in-place move, saving a full copy.
            if (move && item.scheme == destDir.scheme &&
                childrenOf(task, destFs, destDir)[item.name] == null &&
                destFs.moveWithin(item, destDir, item.name)
            ) {
                listener?.onItemDone(item.isDir)
                continue
            }
            val complete = copyRecursive(item, destDir, task)
            if (move && complete) FsRegistry.of(item).delete(item)
        }
        listener?.onDone()
    }

    /** @return whether the copy completed cleanly (no skips / cancels); `move` uses this to decide whether to delete the source. */
    private fun copyRecursive(src: XFile, destDir: XFile, task: Task): Boolean {
        if (task.cancelled.isCancelled() || task.aborted) return false
        val srcFs = FsRegistry.of(src)
        val destFs = FsRegistry.of(destDir)
        val siblings = childrenOf(task, destFs, destDir)
        val existing = siblings[src.name]

        if (src.isDir) {
            // Same-name directory: merge into the existing directory directly.
            val newDir = if (existing?.isDir == true) existing
            else destFs.mkdir(destDir, src.name).also { siblings[it.name] = it }
            var complete = true
            for (child in srcFs.list(src)) {
                if (!copyRecursive(child, newDir, task)) complete = false
                if (task.cancelled.isCancelled() || task.aborted) return false
            }
            task.listener?.onItemDone(isDir = true)
            return complete
        }

        var name = src.name
        if (existing != null) {
            when (task.resolver.resolve(src, existing)) {
                Decision.OVERWRITE -> Unit
                Decision.SKIP -> {
                    task.bytes += src.size // advance the total progress even on skip, to keep the bar consistent
                    task.listener?.onBytes(task.bytes, task.total)
                    task.listener?.onItemDone(isDir = false)
                    return false
                }
                Decision.RENAME -> name = freeName(siblings.keys, src.name)
                null -> { task.aborted = true; return false }
            }
        }

        task.listener?.onFile(src)
        val target = destFs.createFile(destDir, name).also { siblings[name] = it }
        var aborted = false
        srcFs.openInput(src).use { input ->
            destFs.openOutput(target, append = false).use { output ->
                if (!pump(input, output, task, src.size)) aborted = true
            }
        }
        if (aborted) {
            // Cancelled: the destination is only half-written, leaving it is just a
            // corrupt file — and in overwrite mode the original has already been
            // truncated by openOutput's TRUNC, so leaving it is even more pointless.
            // Delete outside the two `use` blocks: deleting while the streams are
            // still open isn't always honoured by SFTP / SMB.
            runCatching { destFs.delete(target) }
            siblings.remove(name)
            return false
        }
        // Best-effort: write the source's modification time back to the destination.
        // This step is not part of "did the copy succeed" — write failure /
        // unsupported both leave the copy valid; the destination simply keeps the
        // "moment of writing" timestamp (see setModifiedTime docs).
        if (src.lastModified > 0) runCatching { destFs.setModifiedTime(target, src.lastModified) }
        task.listener?.onItemDone(isDir = false)
        return true
    }

    private fun pump(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        task: Task,
        srcSize: Long,
    ): Boolean {
        var fileCopied = 0L
        return pipe(input, output, { task.cancelled.isCancelled() || task.aborted }) { n ->
            fileCopied += n
            task.bytes += n
            task.listener?.onFileBytes(fileCopied, srcSize)
            task.listener?.onBytes(task.bytes, task.total)
        }
    }

    /**
     * Read / write pipeline: a background thread continuously reads from the
     * source to fill a buffer pool, while the current thread consumes and writes
     * to the destination — reads (network round trips) overlap with writes;
     * serial "read one block, write one block" throughput gets killed by latency
     * on remote sources. Does not close the two streams. Compression
     * ([com.twig.fs.archive.ArchiveWriter]) reuses this as well.
     * @param onChunk called on the caller thread for each chunk written, with
     *                its byte count (used to advance progress).
     * @return false means cancelled.
     */
    fun pipe(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        cancelled: Cancelled,
        onChunk: (Int) -> Unit = {},
    ): Boolean {
        val pool = java.util.concurrent.ArrayBlockingQueue<ByteArray>(PIPE_DEPTH)
        repeat(PIPE_DEPTH) { pool.put(ByteArray(DEFAULT_BUFFER_SIZE)) }
        // (buffer, length); length < 0 means EOF or read error (the error is in `err`)
        val filled = java.util.concurrent.ArrayBlockingQueue<Pair<ByteArray, Int>>(PIPE_DEPTH + 1)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val reader = Thread({
            try {
                while (true) {
                    val buf = pool.take()
                    val n = try {
                        input.read(buf, 0, buf.size)
                    } catch (t: Throwable) {
                        err.set(t); -1
                    }
                    // Consumer exited (cancelled) and nothing is taking; let the offer time out and exit on its own
                    if (!filled.offer(buf to n, 30, java.util.concurrent.TimeUnit.SECONDS)) return@Thread
                    if (n < 0) return@Thread
                }
            } catch (ignored: InterruptedException) {
            }
        }, "twig-copy-read").apply { isDaemon = true; start() }

        try {
            while (true) {
                if (cancelled.isCancelled()) return false
                val (buf, n) = filled.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (n < 0) break
                output.write(buf, 0, n)
                pool.put(buf)
                onChunk(n)
            }
            err.get()?.let { throw it as? RuntimeException ?: FsException("Read failed: ${it.message}", it) }
            return true
        } finally {
            reader.interrupt()
        }
    }

    /** Generate a non-conflicting rename: "name (1).ext", "name (2).ext" ... */
    private fun freeName(taken: Set<String>, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }

    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1 MB: SMB3 single-read ceiling is usually >= 1 MB; large blocks amortise round trips
    private const val PIPE_DEPTH = 3               // pipeline buffer chunk count (peak memory ~3 MB)
}
