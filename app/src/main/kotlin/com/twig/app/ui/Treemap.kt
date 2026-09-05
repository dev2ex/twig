package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.archive.ArchiveFileSystem
import com.twig.fs.archive.Archives

/** A treemap node: directories hold a child list and accumulate size, files are leaves. */
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
 * Thrown by [TreemapScanner] when a scan cannot proceed. The scanner is a pure class with
 * no [android.content.Context], so message strings are kept here as plain English for logs;
 * the UI layer ([PaneFragment]) catches these and substitutes a localised string.
 */
class TreemapException(message: String) : RuntimeException(message)

/**
 * Full recursive scan of a directory / archive into a [TreemapEntry] tree via
 * FileSystem.list(); works the same for any source. [scanned]/[bytes] are polled
 * by the UI for progress; set [stop] to wind down (e.g. when leaving the occupying
 * view). Blocking IO — must be called from a worker thread.
 */
class TreemapScanner(private val cacheDir: java.io.File) {

    @Volatile var scanned = 0
        private set

    @Volatile var bytes = 0L
        private set

    @Volatile var stop = false

    /** Directories recurse directly; archives are mounted first (materialised if needed, sharing the arc cache with the pane) then the archive root is recursed. */
    fun scanRoot(target: XFile): TreemapEntry {
        val rootFile = if (target.isDir) {
            target
        } else {
            val scheme = Archives.schemeFor(target) ?: throw TreemapException("unsupported target: ${target.scheme}")
            val afs = FsRegistry.of(scheme) as ArchiveFileSystem
            val hostFs = runCatching { FsRegistry.of(target) }.getOrNull()
            val needLocal = scheme == Archives.RAR_SCHEME ||
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

    /** Shares the cache directory/key with PaneViewModel.localArchive, so repeat opens don't redownload. */
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
                // 1MB buffer (the default 8KB amplifies network round-trip latency into many small reads, see CopyEngine)
                FsRegistry.of(file).openInput(file).use { ins ->
                    tmp.outputStream().use { ins.copyTo(it, 1 shl 20) }
                }
                out.delete()
                if (!tmp.renameTo(out)) throw TreemapException("failed to materialise archive cache for ${file.path}")
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
