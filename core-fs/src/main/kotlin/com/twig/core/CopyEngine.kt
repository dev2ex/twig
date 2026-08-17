package com.twig.core

/**
 * 跨文件系统的复制 / 移动引擎。
 *
 * 精髓:任意来源到任意目标的拷贝 = 源.openInput() -> 目标.openOutput() 流式搬运。
 * 因此"把 FTP 里的文件复制到本地压缩包"这类组合天然支持,无需 N×N 的专用代码。
 *
 * 全部为阻塞 IO,调用方需在工作线程执行,并可通过 [ProgressListener] 接收进度、
 * 通过 [Cancelled] 取消、通过 [ConflictResolver] 决定同名冲突(覆盖/跳过/重命名)。
 */
object CopyEngine {

    fun interface Cancelled {
        fun isCancelled(): Boolean
    }

    /** 同名冲突的处理方式。 */
    enum class Decision { OVERWRITE, SKIP, RENAME }

    fun interface ConflictResolver {
        /**
         * 目标目录已有与 [src] 同名的文件时询问;可阻塞(UI 弹框等待用户)。
         * 返回 null 取消整个任务。目录同名不询问,直接合并。
         */
        fun resolve(src: XFile, existing: XFile): Decision?
    }

    /** 一批条目的统计(总字节 / 文件数 / 目录数),供确认与进度展示。 */
    data class Plan(val bytes: Long, val files: Int, val dirs: Int)

    interface ProgressListener {
        /** 开始处理某个文件。 */
        fun onFile(file: XFile) {}
        /** 当前文件已搬运字节数。 */
        fun onFileBytes(copied: Long, size: Long) {}
        /** 已搬运的累计字节数(整个任务维度)。 */
        fun onBytes(copiedTotal: Long, totalBytes: Long) {}
        /** 完成(或跳过)一个文件 / 完成一个目录,用于剩余计数。 */
        fun onItemDone(isDir: Boolean) {}
        /** 整个任务完成。 */
        fun onDone() {}
    }

    /** 递归统计一批条目(目录会递归累加)。 */
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

    /** 估算一批条目的总字节数(用于进度条);目录会递归累加。 */
    fun totalSize(items: List<XFile>): Long = plan(items).bytes

    /** 递归中共享的可变任务状态。 */
    private class Task(
        val listener: ProgressListener?,
        val cancelled: Cancelled,
        val resolver: ConflictResolver,
        val total: Long,
    ) {
        var bytes = 0L
        var aborted = false // 冲突框选了取消
        /** 目标目录清单缓存(目录 uri → 名字→条目),避免逐文件重复 list 远程目录。 */
        val listed = HashMap<String, MutableMap<String, XFile>>()
    }

    /** 目标目录的既有条目表(缓存;新建的条目会补录进来供后续冲突/重名判断)。 */
    private fun childrenOf(task: Task, destFs: FileSystem, destDir: XFile): MutableMap<String, XFile> =
        task.listed.getOrPut(destDir.toUri()) {
            runCatching { destFs.list(destDir).associateBy { it.name }.toMutableMap() }
                .getOrDefault(HashMap())
        }

    /**
     * 复制一批条目到目标目录。
     * @param move 为 true 时复制成功后删除源(若同一 FileSystem 支持 moveWithin 则就地移动);
     *             有跳过/取消时不删除对应源。
     * @param resolver 同名文件冲突决策;默认覆盖(与旧行为一致)。
     */
    fun transfer(
        items: List<XFile>,
        destDir: XFile,
        move: Boolean,
        listener: ProgressListener? = null,
        cancelled: Cancelled = Cancelled { false },
        resolver: ConflictResolver = ConflictResolver { _, _ -> Decision.OVERWRITE },
        plannedBytes: Long = -1, // 调用方已 plan() 过时传入,避免重复递归扫描
    ) {
        val task = Task(listener, cancelled, resolver, if (plannedBytes >= 0) plannedBytes else totalSize(items))
        val destFs = FsRegistry.of(destDir)
        for (item in items) {
            if (task.cancelled.isCancelled() || task.aborted) break
            // 同一文件系统且目标无同名冲突的移动:优先就地,省去整份拷贝
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

    /** @return 是否完整复制(无跳过/取消),move 据此决定能否删源。 */
    private fun copyRecursive(src: XFile, destDir: XFile, task: Task): Boolean {
        if (task.cancelled.isCancelled() || task.aborted) return false
        val srcFs = FsRegistry.of(src)
        val destFs = FsRegistry.of(destDir)
        val siblings = childrenOf(task, destFs, destDir)
        val existing = siblings[src.name]

        if (src.isDir) {
            // 目录同名:直接合并进已有目录
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
                    task.bytes += src.size // 跳过也推进总进度,保持进度条一致
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
            // 取消:目标只搬了一半,留着就是个坏文件——覆盖模式下原文件也已经被
            // openOutput 的 TRUNC 清掉了,留着更没意义。删在两个 use 之外:流还开着
            // 就删,SFTP/SMB 那边不一定认。
            runCatching { destFs.delete(target) }
            siblings.remove(name)
            return false
        }
        // 尽力而为地把源的修改时间写回目标。这一步不是复制成功与否的一部分——写失败/
        // 不支持都不影响这次复制,目标就留着"刚写入"那一刻的时间(见 setModifiedTime 文档)。
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
     * 读写流水线:后台线程持续读源填充缓冲池,当前线程消费写目标——
     * 读(网络往返)与写重叠,串行"读一块写一块"在远程源上吞吐会被延迟卡死。
     * 不负责关闭两端的流。压缩([com.twig.fs.archive.ArchiveWriter])也复用这里。
     * @param onChunk 每写出一块回调其字节数(调用线程,用于推进进度)。
     * @return false 表示被取消。
     */
    fun pipe(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        cancelled: Cancelled,
        onChunk: (Int) -> Unit = {},
    ): Boolean {
        val pool = java.util.concurrent.ArrayBlockingQueue<ByteArray>(PIPE_DEPTH)
        repeat(PIPE_DEPTH) { pool.put(ByteArray(DEFAULT_BUFFER_SIZE)) }
        // (缓冲, 长度);长度 <0 表示读完或读出错(错误在 err 里)
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
                    // 消费方退出(取消)后无人取,超时自行结束
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

    /** 生成不冲突的重命名:name (1).ext, name (2).ext … */
    private fun freeName(taken: Set<String>, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }

    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1MB:SMB3 单次读上限通常 ≥1MB,大块摊薄往返
    private const val PIPE_DEPTH = 3               // 流水线缓冲块数(峰值内存 ~3MB)
}
