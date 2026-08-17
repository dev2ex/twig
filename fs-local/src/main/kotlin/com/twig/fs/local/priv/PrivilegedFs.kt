package com.twig.fs.local.priv

import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.local.priv.PrivilegedShell.Companion.quote
import java.io.InputStream
import java.io.OutputStream

/**
 * File operations carried out through a [PrivilegedShell].
 *
 * This is not a [com.twig.core.FileSystem] of its own on purpose. Elevation is a
 * *fallback* for the ordinary local file system, not a separate source: the user
 * browses "/" as they always did and the directories the app cannot read itself
 * simply start working. Modelling it as a second scheme would fork the tree, break
 * favourites and bookmarks that already point at `file:` paths, and force the user
 * to know which of two identical-looking trees to use.
 *
 * Every command here is built with [quote]d paths — see the note on that function.
 */
class PrivilegedFs(val shell: PrivilegedShell) {

    /**
     * Lists a directory in a single process.
     *
     * ★ `-H` is not optional: without it `find` does not follow a symlink given as
     * its starting point, and `-mindepth 1` then discards the only entry it found.
     * `/sdcard` is exactly that symlink (to `/storage/emulated/0`), so the whole
     * listing comes back empty and, worse, exit status is 0 — it looks like an
     * empty directory rather than a failure.
     *
     * `%n` is last in the format because a filename may contain the separator (it
     * may contain anything except NUL and `/`); parsing splits off the first three
     * fields and takes the rest verbatim. A filename containing a newline is the
     * one case this cannot represent, and is accepted as a known gap.
     */
    fun list(path: String): List<XFile>? {
        val r = shell.exec("find -H ${quote(path)} -maxdepth 1 -mindepth 1 -exec stat -c '%f|%s|%Y|%n' {} +")
        if (!r.ok && r.lines.isEmpty()) return null
        val out = ArrayList<XFile>(r.lines.size)
        val links = ArrayList<String>()
        for (line in r.lines) {
            val e = parseStat(line) ?: continue
            if (e.isLink) links += e.path
            out += e.toXFile()
        }
        if (links.isEmpty()) return out
        // Symlinks are stat'd again with -L to learn what they point at. A link to a
        // directory has to be expandable in the tree, and lstat cannot tell us that.
        // Broken links simply produce no output line and stay non-directories.
        val targets = HashSet<String>()
        // Chunked because the whole batch becomes one argv: a directory full of
        // symlinks (/system/bin is exactly that) with long names would otherwise
        // push the command past ARG_MAX and fail as a whole rather than degrade.
        links.chunked(LINK_BATCH).forEach { batch ->
            val cmd = batch.joinToString(" ") { quote(it) }
            shell.exec("stat -L -c '%f|%n' $cmd", quiet = true).lines.forEach { line ->
                val i = line.indexOf('|')
                if (i > 0) {
                    val mode = line.substring(0, i).toIntOrNull(16) ?: return@forEach
                    if (mode and S_IFMT == S_IFDIR) targets += line.substring(i + 1)
                }
            }
        }
        if (targets.isEmpty()) return out
        return out.map { if (it.path in targets) it.copy(isDir = true) else it }
    }

    /** Stats a single entry; null when it does not exist or cannot be reached. */
    fun stat(path: String): XFile? {
        val r = shell.exec("stat -c '%f|%s|%Y|%n' ${quote(path)}", quiet = true)
        if (!r.ok) return null
        val e = r.lines.firstNotNullOfOrNull { parseStat(it) } ?: return null
        if (!e.isLink) return e.toXFile()
        val t = shell.exec("stat -L -c '%f' ${quote(path)}", quiet = true)
        val mode = t.lines.firstOrNull()?.trim()?.toIntOrNull(16)
        return e.toXFile().copy(isDir = mode != null && mode and S_IFMT == S_IFDIR)
    }

    fun exists(path: String): Boolean = shell.exec("[ -e ${quote(path)} ]", quiet = true).ok

    /** Reads a file. EOF of the one-shot `cat` is the end of the file. */
    fun openInput(path: String): InputStream = shell.openInput("cat ${quote(path)}")

    /**
     * Writes a file. `cat >` truncates and `cat >>` appends, matching the
     * [LocalFileSystem.openOutput] contract the callers already rely on.
     */
    fun openOutput(path: String, append: Boolean): OutputStream =
        shell.openOutput("cat " + (if (append) ">>" else ">") + " " + quote(path))

    /** Creates one directory. Not `-p`: a already-existing path must be reported. */
    fun mkdir(path: String): Boolean = shell.exec("mkdir ${quote(path)}").ok

    fun delete(path: String): Boolean = shell.exec("rm -rf ${quote(path)}").ok

    /**
     * Renames. The existence check is separate and first because `mv` silently
     * replaces an existing target — the same trap [LocalFileSystem.rename] guards
     * against for `File.renameTo`, and the reason that method's contract says
     * overwriting must be the caller's explicit decision.
     */
    fun rename(from: String, to: String): Boolean {
        if (exists(to)) return false
        return shell.exec("mv ${quote(from)} ${quote(to)}").ok
    }

    /**
     * ★ `touch -t` reads its argument as **local** time, so the stamp must be
     * formatted in the default zone. Formatting as UTC silently shifts every
     * copied file's mtime by the device's offset.
     */
    fun setModifiedTime(path: String, timeMs: Long): Boolean {
        val t = java.text.SimpleDateFormat("yyyyMMddHHmm.ss", java.util.Locale.US)
            .format(java.util.Date(timeMs))
        return shell.exec("touch -t $t ${quote(path)}").ok
    }

    private class Entry(val mode: Int, val size: Long, val mtime: Long, val path: String) {
        val isLink: Boolean get() = mode and S_IFMT == S_IFLNK
        val isDir: Boolean get() = mode and S_IFMT == S_IFDIR
        fun toXFile() = XFile(
            scheme = LocalFileSystem.SCHEME,
            path = path,
            isDir = isDir,
            size = if (isDir) 0L else size,
            lastModified = mtime * 1000L,
            canRead = true,
            // Anything reached through the shell is reachable for writing too: the
            // session is uid 0 (or shell uid, which owns what it can see here).
            // Per-entry permission bits describe the *file's* owner, not ours.
            canWrite = true,
        )
    }

    private companion object {
        /** Symlinks re-stat'd per command; keeps one argv well under ARG_MAX. */
        const val LINK_BATCH = 200
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFLNK = 0xA000

        /** `%f|%s|%Y|%n` → raw mode (hex), size, mtime (seconds), name. */
        fun parseStat(line: String): Entry? {
            val a = line.indexOf('|'); if (a <= 0) return null
            val b = line.indexOf('|', a + 1); if (b < 0) return null
            val c = line.indexOf('|', b + 1); if (c < 0) return null
            val mode = line.substring(0, a).toIntOrNull(16) ?: return null
            val size = line.substring(a + 1, b).toLongOrNull() ?: return null
            val mtime = line.substring(b + 1, c).toLongOrNull() ?: return null
            val path = line.substring(c + 1)
            if (path.isEmpty()) return null
            return Entry(mode, size, mtime, path)
        }
    }
}
