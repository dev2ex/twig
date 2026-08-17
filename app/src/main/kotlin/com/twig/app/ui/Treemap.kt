package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.Archives
import com.twig.fs.archive.RarFileSystem

/** 矩形树图的一个节点:目录含子项列表并累计大小,文件为叶子。 */
class TreemapEntry(
    val file: XFile,
    val isDir: Boolean,
    var size: Long,
    val children: MutableList<TreemapEntry>?,
    val parent: TreemapEntry?,
) {
    val name: String get() = file.name
}

/**
 * 全量扫描目录/压缩包成 [TreemapEntry] 树,FileSystem.list() 递归,任意来源同一套。
 * [scanned]/[bytes] 供 UI 轮询进度;[stop] 置位后尽快收尾(退出占用视图时)。
 * 阻塞 IO,须在工作线程调用。
 */
class TreemapScanner(private val cacheDir: java.io.File) {

    @Volatile var scanned = 0
        private set

    @Volatile var bytes = 0L
        private set

    @Volatile var stop = false

    /** 目录直接递归;压缩包先挂载(必要时物化,与面板共用 arc 缓存)再递归包根。 */
    fun scanRoot(target: XFile): TreemapEntry {
        val rootFile = if (target.isDir) {
            target
        } else {
            val scheme = Archives.schemeFor(target) ?: throw IllegalStateException("不支持的类型")
            val afs = FsRegistry.of(scheme) as ArchiveFileSystem
            val hostFs = runCatching { FsRegistry.of(target) }.getOrNull()
            val needLocal = scheme == RarFileSystem.SCHEME ||
                (hostFs is ArchiveFileSystem && !hostFs.fastRandom(target))
            afs.rootOf(if (needLocal) localArchive(target) else target)
        }
        val root = TreemapEntry(rootFile, true, 0, ArrayList(), null)
        scanInto(root)
        return root
    }

    private fun scanInto(dir: TreemapEntry) {
        if (stop) return
        val kids = runCatching { FsRegistry.of(dir.file).list(dir.file) }.getOrDefault(emptyList())
        for (k in kids) {
            if (stop || scanned >= MAX_ENTRIES) return
            scanned++
            if (k.isDir) {
                val c = TreemapEntry(k, true, 0, ArrayList(), dir)
                scanInto(c)
                dir.children!!.add(c)
                dir.size += c.size
            } else {
                val sz = k.size.coerceAtLeast(0)
                dir.children!!.add(TreemapEntry(k, false, sz, null, dir))
                dir.size += sz
                bytes += sz
            }
        }
        dir.children!!.sortByDescending { it.size }
    }

    /** 与 PaneViewModel.localArchive 同一缓存目录/键,重复打开不重复下载。 */
    private fun localArchive(file: XFile): XFile {
        if (file.scheme == ArchiveFileSystem.HOST_SCHEME) return file
        val dir = java.io.File(cacheDir, "arc").apply { mkdirs() }
        val key = Integer.toHexString(
            "${file.scheme}:${file.path}:${file.size}:${file.lastModified}".hashCode(),
        )
        val out = java.io.File(dir, "${key}_${file.name}")
        if (!out.exists() || out.length() != file.size) {
            val tmp = java.io.File(dir, "$key.part")
            try {
                // 1MB 缓冲(默认 8KB 会把网络往返延迟放大成大量小读,参照 CopyEngine)
                FsRegistry.of(file).openInput(file).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it, 1 shl 20) }
                }
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("缓存归档失败")
            } finally {
                tmp.delete()
            }
        }
        return XFile(
            scheme = ArchiveFileSystem.HOST_SCHEME, path = out.path,
            isDir = false, size = out.length(), lastModified = file.lastModified,
        )
    }

    companion object {
        private const val MAX_ENTRIES = 250_000
    }
}
