package com.twig.app.ui

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.File

/** Size scans need type and byte count, but neither permissions nor name sorting. */
internal fun listForSize(dir: XFile): List<XFile> {
    val fs = FsRegistry.of(dir)
    if (fs !is LocalFileSystem) return fs.list(dir)
    return fs.listForSize(dir, ::localSizeEntry)
}

internal fun walkLocalForSize(
    dir: XFile,
    maxDepth: Int,
    stopped: () -> Boolean,
    visit: (parent: XFile, file: XFile) -> Unit,
) {
    (FsRegistry.of(dir) as LocalFileSystem).walk(dir, maxDepth, ::localSizeEntry, stopped, visit)
}

private fun localSizeEntry(file: File): XFile = try {
    // stat follows links, matching LocalFileSystem.list (including /sdcard).
    val stat = Os.stat(file.path)
    val isDir = OsConstants.S_ISDIR(stat.st_mode)
    XFile(LocalFileSystem.SCHEME, file.absolutePath, isDir,
        size = if (isDir) 0 else stat.st_size,
        lastModified = stat.st_mtime * 1000)
} catch (_: ErrnoException) {
    // A file can disappear or become inaccessible between readdir and stat.
    // Preserve the ordinary File API's best-effort result for that entry.
    val isDir = file.isDirectory
    XFile(LocalFileSystem.SCHEME, file.absolutePath, isDir, size = if (isDir) 0 else file.length())
}

/** Fetch permissions only for the entry whose action menu is being opened. */
internal fun sizeScanActionFile(file: XFile): XFile {
    val fs = FsRegistry.of(file)
    return if (fs is LocalFileSystem) {
        fs.stat(file.path) ?: file.copy(canRead = false, canWrite = false)
    } else file
}
