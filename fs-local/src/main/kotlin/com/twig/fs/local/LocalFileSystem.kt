package com.twig.fs.local

import com.twig.core.FileSystem
import com.twig.core.FsException
import com.twig.core.XFile
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedShell
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Local storage file system, based on java.io.File.
 *
 * Phase 1 uses the File API directly (requires runtime storage permission).
 * Phase 1.5 plan: on Android 10+ without MANAGE_EXTERNAL_STORAGE, fall back to
 * SAF / DocumentFile. When that lands, just add a new SafFileSystem registered
 * under a different scheme, or split internally here; the UI won't notice.
 *
 * @param rootDir the root directory this file system exposes (defaults to external storage root).
 */
class LocalFileSystem(
    private val rootDir: File = File("/"),
    override val displayName: String = "Local storage",
) : FileSystem {

    override val scheme: String = SCHEME

    override fun root(): XFile = toXFile(rootDir)

    override fun resolve(path: String): XFile {
        val f = File(path)
        if (!f.exists()) {
            if (maybeHidden(f)) elevation?.stat(path)?.let { return it }
            throw FsException("No such path: $path")
        }
        return toXFile(f)
    }

    override fun list(dir: XFile): List<XFile> {
        val f = File(dir.path)
        // isDirectory needs a successful stat(2); on a path we may traverse but not
        // inspect it comes back false, which is indistinguishable from "not a
        // directory". Ask the elevated shell before believing it.
        if (!f.isDirectory) {
            elevated(dir.path)?.let { return sorted(it) }
            throw FsException("Not a directory: ${dir.path}")
        }
        val children = f.listFiles()
            ?: return elevated(dir.path)?.let { sorted(it) }
                ?: throw FsException("Cannot read directory (permission?): ${dir.path}")
        return sorted(children.map { toXFile(it) })
    }

    /** Directories first, then by name case-insensitively — the conventional file manager sort. */
    private fun sorted(items: List<XFile>): List<XFile> = items
        .sortedWith(compareByDescending<XFile> { it.isDir }.thenBy { it.name.lowercase() })

    private fun elevated(path: String): List<XFile>? = elevation?.list(path)

    override fun openInput(file: XFile): InputStream =
        runCatching { File(file.path).inputStream() }.getOrElse { e ->
            elevation?.openInput(file.path) ?: throw e
        }

    /**
     * The notification moment for a write is **stream close**, not open — the
     * media library needs the file as it is after writing (at open the length is
     * still 0, and MediaStore would index an invalid record).
     * `FilterOutputStream.write(ByteArray,Int,Int)` forwards byte by byte by
     * default and must be overridden, otherwise every copy degrades to one
     * syscall per byte.
     */
    override fun openOutput(file: XFile, append: Boolean): OutputStream {
        val f = File(file.path)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val out = runCatching { java.io.FileOutputStream(f, append) as OutputStream }.getOrElse { e ->
            val priv = elevation ?: throw e
            // The unprivileged mkdirs above may also have been the thing that failed;
            // redo it with privileges before opening, or the write hits a missing parent.
            f.parent?.let { priv.shell.exec("mkdir -p ${PrivilegedShell.quote(it)}") }
            priv.openOutput(file.path, append)
        }
        val hook = changed ?: return out
        return object : java.io.FilterOutputStream(out) {
            private var done = false
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                super.close()
                if (!done) { done = true; hook(f.absolutePath) }
            }
        }
    }

    override fun mkdir(parent: XFile, name: String): XFile {
        val dir = File(parent.path, name)
        if (!dir.exists() && !dir.mkdirs()) {
            if (elevation?.mkdir(dir.path) != true) {
                throw FsException("Could not create directory: ${dir.path}")
            }
            return XFile(SCHEME, dir.path, isDir = true)
        }
        return toXFile(dir)
    }

    override fun delete(file: XFile) {
        val f = File(file.path)
        if (!deleteRecursively(f) && elevation?.delete(file.path) != true) {
            throw FsException("Delete failed: ${file.path}")
        }
        changed?.invoke(f.absolutePath)
    }

    override fun rename(file: XFile, newName: String): XFile {
        val src = File(file.path)
        val dst = File(src.parentFile, newName)
        // ★ POSIX rename(2) atomically replaces the existing target, and
        // File.renameTo maps straight to it — without the pre-check, renaming
        // a.txt to b.txt when b.txt already exists in the same directory
        // silently eats b.txt.
        if (dst.exists()) throw FsException("Target already exists: $newName")
        if (!src.renameTo(dst)) {
            val priv = elevation ?: throw FsException("Rename failed: ${file.path}")
            // dst.exists() above can be a false negative on a path we cannot stat,
            // so the privileged path re-checks before moving — never silently replace.
            if (priv.exists(dst.path)) throw FsException("Target already exists: $newName")
            if (!priv.rename(src.path, dst.path)) throw FsException("Rename failed: ${file.path}")
            changed?.invoke(src.absolutePath)
            changed?.invoke(dst.absolutePath)
            return XFile(SCHEME, dst.path, isDir = file.isDir)
        }
        changed?.invoke(src.absolutePath) // also report the old path: MediaStore needs to drop it
        changed?.invoke(dst.absolutePath)
        return toXFile(dst)
    }

    override fun exists(file: XFile): Boolean {
        val f = File(file.path)
        if (f.exists()) return true
        if (!maybeHidden(f)) return false
        return elevation?.exists(file.path) == true
    }

    /**
     * Could the answer "does not exist" actually be "just invisible"?
     *
     * `File.exists()` cannot distinguish ENOENT from EACCES, and [exists] is on
     * the hot path of a batch copy (each file asks once whether the destination
     * is there). Without this guard, copying a thousand files to `/sdcard`
     * wastes a thousand extra shell round trips — and the parents of those
     * paths are normally readable, so the answer there is trustworthy. Only
     * when the parent itself is unreadable is it worth elevating and asking
     * again. `canRead` is a single access(2); it doesn't fork a process.
     */
    private fun maybeHidden(f: File): Boolean {
        if (elevation == null) return false
        val parent = f.parentFile ?: return true
        return !parent.canRead()
    }

    override fun setModifiedTime(file: XFile, time: Long): Boolean =
        File(file.path).setLastModified(time) || elevation?.setModifiedTime(file.path, time) == true

    override fun moveWithin(src: XFile, destDir: XFile, newName: String): Boolean {
        // Same-volume rename is the O(1) optimal move; cross-volume renameTo fails,
        // return false to fall back to the copy engine.
        val from = File(src.path)
        val to = File(destDir.path, newName)
        // Same as rename: renameTo silently replaces an existing target. CopyEngine
        // only calls this when there is no same-name conflict, but its check is
        // based on a directory listing snapshot — the destination dir may have been
        // mutated externally in the meantime — so as a final safety net we bail
        // back to "copy + delete source", where the full conflict prompt runs.
        if (to.exists()) return false
        val ok = runCatching { from.renameTo(to) }.getOrDefault(false)
        if (ok) {
            changed?.invoke(from.absolutePath)
            changed?.invoke(to.absolutePath)
        }
        return ok
    }

    /**
     * Recursive delete. **Symlinks delete only the link itself, never into the
     * target** — both `File.isDirectory` and `listFiles()` follow symlinks, so
     * without this check, deleting a directory containing a "directory symlink
     * pointing elsewhere" would first wipe out everything at the link's
     * destination before removing the link itself. On Android this isn't a
     * theoretical concern: a local shell can create such links, and the system
     * is full of them already.
     */
    private fun deleteRecursively(f: File): Boolean {
        if (f.isDirectory && !isSymlink(f)) {
            f.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        }
        return f.delete()
    }

    /**
     * Whether the path is a symlink. **Canonicalise the parent directory first
     * before comparing**; you can't just do `f.canonicalFile != f.absoluteFile`
     * — that fires when *any* level of the path is a link, and on Android
     * `/sdcard` itself is a link to `/storage/emulated/0`, so every
     * `/sdcard/anything` would be mis-flagged as a symlink and recursive delete
     * would silently stop working. (Same approach as Apache Commons IO's
     * `FileUtils.isSymlink`; this is a pure JVM module, so `android.system.Os.lstat`
     * isn't available, and `java.nio.file` needs API 26 while minSdk is 24.)
     */
    private fun isSymlink(f: File): Boolean = runCatching {
        val parent = f.parentFile ?: return false
        val inCanonicalDir = File(parent.canonicalFile, f.name)
        inCanonicalDir.canonicalFile != inCanonicalDir.absoluteFile
    }.getOrDefault(false)

    private fun toXFile(f: File): XFile = XFile(
        scheme = SCHEME,
        path = f.absolutePath,
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0L else f.length(),
        lastModified = f.lastModified(),
        canRead = f.canRead(),
        canWrite = f.canWrite(),
    )

    companion object {
        const val SCHEME = "file"

        /**
         * Notification hook for local file writes / deletes / renames (path is
         * absolute and may already no longer exist).
         *
         * There is exactly one reason this exists: **to tell the system media
         * library that something changed** — without notification, images
         * copied into `DCIM/` never show up in the gallery (MediaStore only
         * recognises what it has indexed itself, and writing files via
         * `java.io` doesn't trigger a scan). All local writes go through
         * `openOutput` here, so attaching at this layer covers every entry
         * point: over-copy copy, archive extraction, editor save, WiFi share
         * upload — all of them.
         *
         * This is a pure JVM module: it has no Context and no MediaStore; the
         * concrete implementation is installed at startup by `:app` (see
         * `com.twig.app.MediaScan`). Callbacks may arrive from any thread;
         * the implementation handles that itself.
         */
        @Volatile
        @JvmStatic
        var changed: ((path: String) -> Unit)? = null

        /**
         * Privileged fallback (root / Shizuku). Once installed, only the steps
         * where the normal API fails switch to the privileged shell:
         * directories that can't be listed, files that can't be read, entries
         * that can't be deleted. When nothing is installed it is null, and
         * behaviour is identical to before this hook existed.
         *
         * ★ Why this is a "fallback" rather than an independent scheme: users
         * want to be able to tap into **the root** tree, not have a separate
         * privileged tree sitting next to it that looks identical. By sharing
         * the scheme, every path with a stored `file:` favourite, every
         * cross-source copy (which `CopyEngine` only knows via
         * openInput / openOutput), thumbnails, and search — all of them benefit
         * for free with zero changes.
         *
         * ★ And precisely because it is a fallback, **paths we can read
         * ourselves never go through it**: every command forks a process, and
         * using it to list `/sdcard` is dozens of times slower for no reason;
         * furthermore, permission bits on the normal path are real, whereas
         * the privileged path can only report canWrite=true across the board.
         *
         * Same pattern as [changed]: pure JVM module provides the mount point,
         * `:app` installs / uninstalls based on the user's toggle (see
         * `com.twig.app.Privileged`). Callbacks may arrive from any thread.
         */
        @Volatile
        @JvmStatic
        var elevation: PrivilegedFs? = null
    }
}
