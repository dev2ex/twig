package com.twig.core

import java.io.InputStream
import java.io.OutputStream

/**
 * A "file system provider". One implementation each for local, archives, FTP, SMB,
 * each cloud drive.
 *
 * This is the project's foundation: the UI only talks to [FileSystem] + [XFile],
 * and cross-source copy / move is handled uniformly by [CopyEngine] via
 * openInput / openOutput. So adding a new source = adding one new FileSystem
 * implementation, with zero changes to the UI or to copying.
 *
 * Implementation contract:
 *  - All methods are blocking IO; the caller is responsible for putting them on a
 *    worker thread.
 *  - Failures throw [FsException]; don't return null and swallow the error.
 */
interface FileSystem {

    /** This file system's scheme; must correspond to [XFile.scheme] and be globally unique. */
    val scheme: String

    /** Human-readable name for the sidebar, e.g. "Internal storage" / "FTP". */
    val displayName: String

    /** The root entry of this file system. */
    fun root(): XFile

    /** Resolve a path to its entry (used for navigation / validation); throws [FsException] if missing. */
    fun resolve(path: String): XFile

    /** List entries under a directory; [dir] must be isDir. */
    fun list(dir: XFile): List<XFile>

    /** Open a read stream; caller is responsible for closing it. */
    fun openInput(file: XFile): InputStream

    /**
     * Open a write stream; create the file if it does not exist, otherwise append
     * or overwrite per [append]. Caller is responsible for closing it.
     */
    fun openOutput(file: XFile, append: Boolean = false): OutputStream

    /** Create a subdirectory under [parent] and return its [XFile]. */
    fun mkdir(parent: XFile, name: String): XFile

    /**
     * Determine the target [XFile] under [parent] for a "soon-to-be-written" new
     * file, for [CopyEngine] to later pass to openOutput. The default uses simple
     * path concatenation (file / ftp / zip only need to create on demand at write
     * time); implementations that must call createDocument first to obtain a URI
     * (SAF, etc.) should override this method.
     */
    fun createFile(parent: XFile, name: String): XFile {
        val sep = if (parent.path.endsWith("/")) "" else "/"
        return XFile(scheme = scheme, path = "${parent.path}$sep$name", isDir = false)
    }

    /** Delete a file or directory (directories are deleted recursively). */
    fun delete(file: XFile): Unit

    /**
     * Rename (within the same directory); returns the new entry.
     *
     * **When a target with the same name already exists, you must throw
     * [FsException]; never silently overwrite.**
     * This rule has to be spelled out because several underlying APIs default to
     * the opposite: POSIX `rename(2)` (and `File.renameTo`) atomically replaces
     * an existing target, and WebDAV `MOVE` can carry `Overwrite: T` — copying
     * those defaults silently makes "rename" eat another file. To overwrite,
     * the caller deletes first and then renames; at least that path involves
     * an explicit user confirmation.
     */
    fun rename(file: XFile, newName: String): XFile

    /** Whether the entry exists. */
    fun exists(file: XFile): Boolean

    /**
     * Whether this whole file system supports writing (a different question from
     * an individual entry's [XFile.canWrite] — the latter is computed by
     * resolve()/list(), but some call sites such as favorites bypass them and
     * assemble XFile directly, in which case this "per-scheme" judgment is the
     * fallback that catches them). Sources that are read-only end-to-end, such
     * as restic / 7z / RAR / git view, override to false; default is true.
     */
    fun writable(): Boolean = true

    /**
     * Whether [openOutput] overwriting itself is already atomic (either the file
     * is fully replaced with new content, or the original is left bit-for-bit
     * intact). Default false — most implementations "truncate the original then
     * write into it", so a half-written file after a disconnect / power loss is
     * just a fragment. In-place save (e.g. a text editor writing back) therefore
     * has to go through "write temp -> delete original -> rename".
     * Zip's overwrite of an entry already rewrites the whole archive to a temp
     * file and then swaps it in, which is already atomic, so it overrides to true
     * and saves the two extra full-archive rewrites.
     */
    fun atomicOverwrite(): Boolean = false

    /**
     * Whether `readAt` from [openRandom] is "true random access" (cost of a
     * positional read is independent of position). SMB (pread) / WebDAV
     * (HTTP Range) = true; FTP / SFTP and the default implementation can only
     * "reopen and skip" (cost O(position)) = false. For non-MP4 containers
     * (MKV / AVI / etc., which have no off-line-parsable sample table), the
     * thumbnail path hands a truly random-access data source to
     * MediaMetadataRetriever and lets it demux + seek itself — this is only
     * done when the flag is true; otherwise any seek pulls the whole file down.
     */
    fun randomAccessEfficient(): Boolean = false

    /**
     * Open a source that supports "positional reads" (used by the media player
     * for random seek). The default is built on [openInput] + reopen-and-skip
     * (usable but slow for sources without positional read); implementations
     * with native positional reads (e.g. SMB via pread) should override this
     * method to get efficient seek.
     */
    fun openRandom(file: XFile): RandomSource = object : RandomSource {
        private var input: java.io.InputStream? = null
        private var pos = -1L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position != pos || input == null) {
                runCatching { input?.close() }
                val ins = openInput(file)
                var skipped = 0L
                while (skipped < position) {
                    val k = ins.skip(position - skipped)
                    if (k <= 0) { if (ins.read() < 0) break else skipped++ } else skipped += k
                }
                input = ins; pos = position
            }
            val n = input!!.read(buffer, offset, length)
            if (n > 0) pos += n
            return n
        }
        override fun length(): Long = file.size
        override fun close() { runCatching { input?.close() } }
    }

    /**
     * Resolve the parent directory; returns null when already at the top of this
     * file system. Default uses [XFile.parentPath]; implementations like zip,
     * which has a "!/" boundary or needs to "jump back to the host file system"
     * from the top level, may override this method.
     */
    fun parentOf(file: XFile): XFile? {
        if (file.path == "/" || file.path.isEmpty()) return null
        return resolve(file.parentPath)
    }

    /**
     * Efficient move within the same file system (optional optimisation).
     * Returning true means it was done in place; returning false means it is not
     * supported and [CopyEngine] falls back to "copy + delete source". Default
     * is "not supported".
     */
    fun moveWithin(src: XFile, destDir: XFile, newName: String = src.name): Boolean = false

    /**
     * After a copy, write the source's modification time back to the destination
     * (optional optimisation). true = set; false = unsupported or failed;
     * [CopyEngine] treats this as best-effort and not a copy failure — the
     * destination keeps the "moment of writing" timestamp.
     *
     * Default is unsupported. Implemented for: local (direct setLastModified),
     * SFTP (setattr writes mtime), FTP (MFMT, RFC 3659; older servers may not
     * understand it — failure is not an error). **Not implemented for**:
     * SMB (libsmb2's JNI wrapper doesn't expose utime yet; needs native code
     * changes), WebDAV (no common standard, server extensions vary), archive
     * writers (zip / 7z entry times must be set when the entry is written; not
     * suitable for a "fill in afterwards" interface like this).
     */
    fun setModifiedTime(file: XFile, time: Long): Boolean = false
}

/** Unified type for file system operation exceptions. */
class FsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Source supporting positional reads (used by the media player for random seek). */
interface RandomSource : java.io.Closeable {
    /** Read at most [length] bytes from [position] into [buffer]; returns the byte count, or -1 at EOF. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    /** Total length; returns <=0 when unknown. */
    fun length(): Long
}
