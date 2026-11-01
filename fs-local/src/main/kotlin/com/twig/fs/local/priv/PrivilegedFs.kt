package com.twig.fs.local.priv

import com.twig.core.XFile
import com.twig.core.UnixMetadata
import com.twig.core.UnixPermissions
import com.twig.core.RandomSource
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.local.priv.PrivilegedShell.Companion.quote
import java.io.InputStream
import java.io.IOException
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

    @Volatile private var findPrintf: Boolean? = null

    private fun supportsFindPrintf(): Boolean {
        findPrintf?.let { return it }
        // Probe the complete format: older Android find versions lack -printf.
        // The probe must end in a newline for the shared shell's marker framing.
        val result = shell.exec("find / -maxdepth 0 -printf '%M|%s|%T@|%p\\0\\n'", quiet = true)
        return (result.ok && result.lines.any {
            it.endsWith('\u0000') && parseFind(it.dropLast(1)) != null
        }).also { findPrintf = it }
    }

    /**
     * Streams an entire subtree in one find invocation instead of one command per
     * directory. A separate process keeps cancellation out of the shared shell's
     * framing; exec lets closing the stream kill find itself. -printf reuses find's
     * own metadata, avoiding toybox's oversized -exec stat batches (E2BIG). NUL
     * records preserve filenames containing newlines. Older find versions keep
     * the ordinary per-directory path. Listing failures leave the partial scan intact,
     * matching the size scanners' best-effort handling of unreadable directories.
     */
    fun walk(
        path: String,
        maxDepth: Int,
        stopped: () -> Boolean,
        visit: (XFile) -> Unit,
    ) {
        if (stopped()) return
        if (!supportsFindPrintf()) {
            walkByListing(path, maxDepth, stopped, visit)
            return
        }
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.addLast(path to maxDepth)
        while (pending.isNotEmpty() && !stopped()) {
            val (root, depth) = pending.removeFirst()
            if (depth <= 0) continue
            val prefix = root.trimEnd('/') + "/"
            val cmd = "exec find -H ${quote(root)} -mindepth 1 -maxdepth $depth -printf '%M|%s|%T@|%p\\0'"
            try {
                shell.openInput(cmd).reader().buffered(1 shl 16).use { reader ->
                    val buffer = CharArray(1 shl 14)
                    val record = StringBuilder()
                    while (!stopped()) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        for (i in 0 until n) {
                            if (buffer[i] != '\u0000') { record.append(buffer[i]); continue }
                            if (stopped()) return@use
                            val entry = parseFind(record.toString())
                            record.setLength(0)
                            if (entry == null || !entry.path.startsWith(prefix)) continue
                            val file = if (entry.isLink) {
                                // Query only the target mode: printing its filename
                                // through stat's line format would lose newlines.
                                val target = shell.exec("stat -L -c '%f' ${quote(entry.path)}", quiet = true)
                                    .lines.firstOrNull()?.trim()?.toIntOrNull(16)
                                entry.toXFile().copy(isDir = target != null && target and S_IFMT == S_IFDIR)
                            } else entry.toXFile()
                            visit(file)
                            // find -H follows only its starting path. Scan directory
                            // links separately, within the same depth/entry budget.
                            if (entry.isLink && file.isDir) {
                                val usedDepth = entry.path.substring(prefix.length).count { it == '/' } + 1
                                pending.addLast(entry.path to (depth - usedDepth))
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // find may exit non-zero after delivering readable siblings.
            }
        }
    }

    private fun walkByListing(path: String, maxDepth: Int, stopped: () -> Boolean, visit: (XFile) -> Unit) {
        val pending = ArrayDeque<Pair<String, Int>>().apply { addLast(path to 0) }
        while (pending.isNotEmpty() && !stopped()) {
            val (root, depth) = pending.removeFirst()
            if (depth >= maxDepth) continue
            for (file in list(root) ?: emptyList()) {
                if (stopped()) return
                visit(file)
                if (file.isDir) pending.addLast(file.path to (depth + 1))
            }
        }
    }

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

    /** Fixed metadata fields only: filenames, including newlines and separators, are never parsed. */
    fun unixMetadata(path: String): UnixMetadata? {
        val result = shell.exec("stat -c '%f|%u|%g|%s|%Y|%X|%Z' ${quote(path)}", quiet = true)
        if (!result.ok) return null
        val fields = result.lines.firstOrNull()?.split('|') ?: return null
        if (fields.size != 7) return null
        val mode = fields[0].toIntOrNull(16) ?: return null
        val numbers = fields.drop(1).map { it.toLongOrNull() ?: return null }
        return UnixMetadata(mode, numbers[0], numbers[1], numbers[2],
            numbers[3] * 1000L, numbers[4] * 1000L, numbers[5] * 1000L, selinuxContext(path))
    }

    fun selinuxContext(path: String): String? {
        // stat %C on toybox follows the target even when its other fields use lstat.
        val result = shell.exec("ls -Zd -- ${quote(path)}", quiet = true)
        return if (result.ok) parseContext(result.text) else null
    }

    fun readLink(path: String): String? {
        val result = shell.exec("readlink -n ${quote(path)}; echo", quiet = true)
        return result.lines.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    fun exists(path: String): Boolean = shell.exec("[ -e ${quote(path)} ]", quiet = true).ok

    /** Reads a file. EOF of the one-shot `cat` is the end of the file. */
    fun openInput(path: String): InputStream = shell.openInput("cat ${quote(path)}")

    /** dd seeks on its input file; only the remainder of one block is skipped in the pipe.
     * Sequential reads reuse that pipe, while a seek closes it and starts at the new block.
     * No whole-file copy or scan from byte zero, including for MP4's trailing moov.
     */
    fun openRandom(path: String): RandomSource {
        val result = shell.exec("stat -L -c '%s' ${quote(path)}", quiet = true)
        val size = result.lines.firstOrNull()?.trim()?.toLongOrNull()
        if (!result.ok || size == null) throw IOException("Cannot stat file: $path")
        return object : RandomSource {
            private var input: InputStream? = null
            private var pos = -1L
            private var closed = false

            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                check(!closed) { "Source is closed" }
                require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length)
                if (length == 0) return 0
                if (position != pos || input == null) {
                    input?.close()
                    input = null
                    val block = 65536L
                    val stream = shell.openInput("exec dd if=${quote(path)} bs=$block skip=${position / block}")
                    input = stream
                    var remaining = position % block
                    while (remaining > 0) {
                        val n = stream.skip(remaining)
                        if (n > 0) remaining -= n
                        else if (stream.read() < 0) break else remaining--
                    }
                    pos = position
                }
                val n = input!!.read(buffer, offset, length)
                if (n > 0) pos += n
                return n
            }
            override fun length(): Long = size
            @Synchronized override fun close() {
                closed = true
                input?.close()
                input = null
            }
        }
    }

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
            isSymbolicLink = isLink,
            unixMode = mode,
        )
    }

    companion object {
        /** ls -Zd emits the context before the filename. Reject unsupported/unknown markers. */
        fun parseContext(output: String): String? = output.trimStart().takeWhile { !it.isWhitespace() }
            .takeIf { it.matches(Regex("[^:\\s]+:[^:\\s]+:[^:\\s]+:[^\\s]+")) }

        /** Symlinks re-stat'd per command; keeps one argv well under ARG_MAX. */
        private const val LINK_BATCH = 200
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFLNK = 0xA000

        /** `%M|%s|%T@|%p` from GNU/toybox find; split only the metadata fields. */
        private fun parseFind(record: String): Entry? {
            val a = record.indexOf('|'); if (a <= 0) return null
            val b = record.indexOf('|', a + 1); if (b < 0) return null
            val c = record.indexOf('|', b + 1); if (c < 0) return null
            val type = when (record[0]) {
                'd' -> S_IFDIR; 'l' -> S_IFLNK; '-' -> 0x8000
                'b' -> 0x6000; 'c' -> 0x2000; 'p' -> 0x1000; 's' -> 0xC000
                else -> return null
            }
            val mode = type or (UnixPermissions.parseSymbolic(record.substring(1, a)) ?: return null)
            val size = record.substring(a + 1, b).toLongOrNull() ?: return null
            val time = record.substring(b + 1, c).substringBefore('.').toLongOrNull() ?: return null
            val path = record.substring(c + 1).takeIf { it.isNotEmpty() } ?: return null
            return Entry(mode, size, time, path)
        }

        /** `%f|%s|%Y|%n` → raw mode (hex), size, mtime (seconds), name. */
        private fun parseStat(line: String): Entry? {
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
